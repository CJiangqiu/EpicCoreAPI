package net.eca.util.health.internal;

import net.eca.util.EcaLogger;
import net.eca.util.reflect.UnsafeUtil;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 数值反演处理无法符号求逆的自定义解码节点。它从相关运行期对象中收集可写数值单元，
 * 通过扰动估算斜率并迭代逼近目标血量。写入使用引用替换，并由快照、超时和异常处理限制副作用。
 * 搜索范围由静态分析结果限定，对象图遍历还设有深度和时间上限。
 */
public final class ProtocolNumericInverter {

    private ProtocolNumericInverter() {}

    private static final long TIME_BUDGET_NANOS = 200_000_000L;   // 单次搜索 wall-clock 预算
    private static final int MAX_PASSES = 64;                      // 坐标下降迭代上限(超时为主，本值为备)
    private static final int MAX_WALK_DEPTH = 64;                  // 对象图递归深度上限，防深图爆栈(StackOverflowError)
    private static final double PERTURB = 1.0;                     // 测斜率的单位微扰
    private static final int AUTHORITY_CELL_CAP = 256;

    /* 搜索结局诊断去重：每类每原因只打一次，避免每-tick 改血刷屏 */
    private static final Set<String> DIAG_DUMPED = ConcurrentHashMap.newKeySet();

    private static void diag(LivingEntity entity, String reason) {
        if (DIAG_DUMPED.add(entity.getClass().getName() + "|" + reason))
            EcaLogger.info("[ProtocolNumericInverter] {} entity={}", reason, entity.getClass().getName());
    }

    public static boolean searchAuthority(LivingEntity entity, float target,
                                          ProtocolDataflowAnalyzer.Source authority) {
        if (entity == null || authority == null) return false;
        Object root = authority.read(entity);
        if (root == null || root instanceof Entity) return false;
        return search(entity, target, List.of(root), AUTHORITY_CELL_CAP);
    }

    private static boolean search(LivingEntity entity, float target, List<Object> roots,
                                  int cellCap) {
        if (entity == null || roots == null || roots.isEmpty()) return false;
        long deadline = System.nanoTime() + TIME_BUDGET_NANOS;

        List<Cell> cells = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        // 只遍历权威源对象图；回指实体的边会在 isSkippable 处截断。
        for (Object root : roots) {
            walk(root, cells, visited, deadline, 0, cellCap);
        }
        if (cells.isEmpty()) {
            diag(entity, "no perturbable numeric cells reachable from dead-end roots (roots=" + roots.size() + ")");
            return false;
        }

        Object[] snapshot = new Object[cells.size()];
        for (int i = 0; i < cells.size(); i++) snapshot[i] = cells.get(i).snapshot();

        try {
            float baseline = LifeProtocolManager.readHealthAnchor(entity);
            if (!Float.isFinite(baseline)) {
                diag(entity, "baseline health anchor non-finite");
                rollback(cells, snapshot);
                return false;
            }
            if (hit(baseline, target)) return true;

            List<Cell> relevant = new ArrayList<>();
            List<Double> slopes = new ArrayList<>();
            for (Cell cell : cells) {
                if (System.nanoTime() > deadline) break;
                double cur = cell.read();
                if (!Double.isFinite(cur)) continue;
                Object exact = cell.snapshot();   // 保留原始类型和值，避免 long 与 double 转换造成精度损失
                if (!cell.write(cur + PERTURB)) continue;
                float h = LifeProtocolManager.readHealthAnchor(entity);
                cell.restore(exact);              // 使用快照恢复，避免数值类型转换
                if (!Float.isFinite(h)) continue;
                double slope = (h - baseline) / PERTURB;
                if (Math.abs(slope) > 1e-9) { relevant.add(cell); slopes.add(slope); }
            }
            if (relevant.isEmpty()) {
                diag(entity, "no cell influences health anchor (all slopes ~0, cells=" + cells.size() + ")");
                rollback(cells, snapshot);
                return false;
            }
            /* 非零斜率就是锚点随写入变化的直接证据，比原版字段联动的弱取证强。
               不补正的话，读自定义存储的 getHealth 会被判死，descent 再准也过不了 verify。 */
            LifeProtocolManager.promoteAnchorTrust(entity.getClass());

            for (int pass = 0; pass < MAX_PASSES; pass++) {
                if (System.nanoTime() > deadline) break;
                float h = LifeProtocolManager.readHealthAnchor(entity);
                if (hit(h, target)) break;
                double err = target - h;
                for (int i = 0; i < relevant.size(); i++) {
                    Cell cell = relevant.get(i);
                    double cur = cell.read();
                    if (!Double.isFinite(cur)) continue;
                    h = step(entity, target, cell, cur, err / slopes.get(i), h);
                    if (hit(h, target)) break;
                    err = target - h;
                }
            }

            boolean ok = LifeProtocolManager.verify(entity, target);
            if (!ok) {
                diag(entity, "descent did not reach target (cells=" + cells.size() + " relevant=" + relevant.size() + ")");
                rollback(cells, snapshot);
            } else {
                EcaLogger.info("[ProtocolNumericInverter] hit entity={} target={} cells={} relevant={}",
                        entity.getClass().getName(), target, cells.size(), relevant.size());
            }
            return ok;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            diag(entity, "aborted by exception: " + t.getClass().getSimpleName());
            rollback(cells, snapshot);
            return false;
        }
    }

    private static boolean hit(float actual, float target) {
        if (!Float.isFinite(actual)) return false;
        return ProtocolValueSemantics.matches(actual, target);
    }

    private static float step(LivingEntity entity, float target, Cell cell, double current, double delta, float before) {
        if (!Double.isFinite(delta)) return before;
        Object exact = cell.snapshot();
        double beforeError = Math.abs((double) target - before);
        double scale = 1.0;
        for (int attempt = 0; attempt < 12; attempt++, scale *= 0.5) {
            cell.restore(exact);
            if (!cell.write(current + delta * scale)) continue;
            float after = LifeProtocolManager.readHealthAnchor(entity);
            if (!Float.isFinite(after)) continue;
            double afterError = Math.abs((double) target - after);
            if (hit(after, target) || afterError < beforeError) return after;
        }
        cell.restore(exact);
        return before;
    }

    private static void rollback(List<Cell> cells, Object[] snapshot) {
        for (int i = cells.size() - 1; i >= 0; i--) {
            try { cells.get(i).restore(snapshot[i]); }
            catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }
        }
    }

    static final class NumericRollback {
        private final List<Cell> cells;
        private final Object[] snapshots;

        private NumericRollback(List<Cell> cells, Object[] snapshots) {
            this.cells = List.copyOf(cells);
            this.snapshots = snapshots.clone();
        }

        void restore() {
            rollback(cells, snapshots);
        }
    }

    // ==================== 对象图遍历：收集可扰动原始 cell ====================

    private static void walk(Object obj, List<Cell> cells, Set<Object> visited, long deadline, int depth, int cellCap) {
        if (obj == null || depth > MAX_WALK_DEPTH || System.nanoTime() > deadline || cells.size() >= cellCap) return;
        if (obj instanceof Number || obj instanceof Boolean || obj instanceof Character || obj instanceof String) return;
        if (!visited.add(obj)) return;
        Class<?> cls = obj.getClass();
        if (isSkippable(cls)) return;

        /* 容器必须按公开元素语义遍历。反射进入哈希表的 size、mask、桶数组会把结构元数据
           误当成业务数值；即使回滚成功，写入期间也足以破坏容器不变量。 */
        if (obj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (System.nanoTime() > deadline || cells.size() >= cellCap) return;
                Object value = entry.getValue();
                if (value instanceof Number) cells.add(new MapValueCell(map, entry.getKey()));
                else walk(value, cells, visited, deadline, depth + 1, cellCap);
            }
            return;
        }
        if (obj instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                if (System.nanoTime() > deadline || cells.size() >= cellCap) return;
                Object value = list.get(i);
                if (value instanceof Number) cells.add(new ListValueCell(list, i));
                else walk(value, cells, visited, deadline, depth + 1, cellCap);
            }
            return;
        }
        if (obj instanceof Collection<?> collection) {
            for (Object value : collection) {
                if (System.nanoTime() > deadline || cells.size() >= cellCap) return;
                if (!(value instanceof Number)) walk(value, cells, visited, deadline, depth + 1, cellCap);
            }
            return;
        }

        if (cls.isArray()) {
            Class<?> comp = cls.getComponentType();
            int len = Array.getLength(obj);
            if (comp.isPrimitive()) {
                if (isNumericPrimitive(comp)) for (int i = 0; i < len && cells.size() < cellCap; i++) cells.add(new ArrayCell(obj, i));
            } else {
                for (int i = 0; i < len; i++) {
                    if (System.nanoTime() > deadline || cells.size() >= cellCap) return;
                    Object el = Array.get(obj, i);
                    if (el instanceof Number) cells.add(new ArrayCell(obj, i));
                    else walk(el, cells, visited, deadline, depth + 1, cellCap);
                }
            }
            return;
        }

        for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (System.nanoTime() > deadline || cells.size() >= cellCap) return;
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                try {
                    f.setAccessible(true);
                    if (ft.isPrimitive()) {
                        if (isNumericPrimitive(ft)) cells.add(new FieldCell(obj, f));
                    } else {
                        Object v = f.get(obj);
                        if (v == null) continue;
                        if (v instanceof Number) cells.add(new FieldCell(obj, f));
                        else walk(v, cells, visited, deadline, depth + 1, cellCap);
                    }
                } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }
            }
        }
    }

    private static boolean isNumericPrimitive(Class<?> t) {
        return t == int.class || t == long.class || t == float.class
                || t == double.class || t == short.class || t == byte.class;
    }

    // 跳过 JDK 内部对象、DFU/Codec 图以及世界和注册表等大型对象图，避免无关候选耗尽搜索预算
    private static boolean isSkippable(Class<?> cls) {
        String n = cls.getName();
        if (n.startsWith("java.lang.Class") || n.startsWith("java.lang.ClassLoader")
                || n.startsWith("java.lang.Thread") || n.startsWith("java.lang.reflect.")
                || n.startsWith("java.lang.invoke.") || n.startsWith("java.security.")
                || n.startsWith("com.mojang.")) return true;
        return Level.class.isAssignableFrom(cls)
                || Entity.class.isAssignableFrom(cls)
                || MinecraftServer.class.isAssignableFrom(cls)
                || RegistryAccess.class.isAssignableFrom(cls)
                || Registry.class.isAssignableFrom(cls)
                || Holder.class.isAssignableFrom(cls);
    }

    // ==================== Cell：读/写(引用替换)/快照/回滚 ====================

    /* Cell 只封装已由语义数据流定位的数值槽，label 用于事务诊断和回滚核对。 */
    interface Cell {
        double read();
        boolean write(double v);
        Object snapshot();
        void restore(Object snap);
        String label();
        default int associationScore(LivingEntity entity) { return 0; }
    }

    private static final class FieldCell implements Cell {
        private final Object owner;
        private final Field field;

        private FieldCell(Object owner, Field field) { this.owner = owner; this.field = field; }

        @Override public double read() {
            try { return field.get(owner) instanceof Number n ? n.doubleValue() : Double.NaN; }
            catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return Double.NaN; }
        }

        @Override public boolean write(double v) {
            try {
                Object boxed = coerceLike(field.get(owner), field.getType(), v);
                return boxed != null && put(boxed);
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }
        }

        @Override public Object snapshot() {
            try { return field.get(owner); } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("read", t); return null; }
        }

        @Override public void restore(Object snap) { put(snap); }

        @Override public String label() {
            return field.getDeclaringClass().getSimpleName() + "." + field.getName();
        }

        private boolean put(Object value) {
            try { field.set(owner, value); return true; }
            catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return UnsafeUtil.unsafePutField(owner, field, value);   // final 字段或模块访问受限时尝试 Unsafe
            }
        }
    }

    private static final class ArrayCell implements Cell {
        private final Object array;
        private final int index;

        private ArrayCell(Object array, int index) { this.array = array; this.index = index; }

        @Override public double read() {
            try { return Array.get(array, index) instanceof Number n ? n.doubleValue() : Double.NaN; }
            catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return Double.NaN; }
        }

        @Override public boolean write(double v) {
            try {
                Class<?> comp = array.getClass().getComponentType();
                Object boxed = coerceLike(comp.isPrimitive() ? null : Array.get(array, index), comp, v);
                if (boxed == null) return false;
                Array.set(array, index, boxed);
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }
        }

        @Override public Object snapshot() {
            try { return Array.get(array, index); } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("read", t); return null; }
        }

        @Override public void restore(Object snap) {
            try { Array.set(array, index, snap); } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }
        }

        @Override public String label() {
            return array.getClass().getComponentType().getSimpleName() + "[" + index + "]";
        }
    }

    private static final class MapValueCell implements Cell {
        private final Map map;
        private final Object key;

        private MapValueCell(Map<?, ?> map, Object key) {
            this.map = map;
            this.key = key;
        }

        @Override public double read() {
            try { return map.get(key) instanceof Number number ? number.doubleValue() : Double.NaN; }
            catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return Double.NaN; }
        }

        @Override public boolean write(double value) {
            try {
                Object current = map.get(key);
                Object boxed = coerceLike(current, current == null ? Object.class : current.getClass(), value);
                if (boxed == null) return false;
                map.put(key, boxed);
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }
        }

        @Override public Object snapshot() {
            try { return map.get(key); }
            catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("read", t); return null; }
        }

        @Override public void restore(Object snapshot) {
            try { map.put(key, snapshot); }
            catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }
        }

        @Override public String label() {
            return map.getClass().getSimpleName() + "[" + String.valueOf(key) + "]";
        }

        @Override public int associationScore(LivingEntity entity) {
            if (entity == null || key == null) return 0;
            if (key == entity || key.equals(entity.getUUID())) return 100;
            if (key instanceof Number number && number.intValue() == entity.getId()) return 80;
            String text = String.valueOf(key);
            if (text.equals(entity.getUUID().toString())) return 90;
            return 0;
        }
    }

    private static final class ListValueCell implements Cell {
        private final List list;
        private final int index;

        private ListValueCell(List<?> list, int index) {
            this.list = list;
            this.index = index;
        }

        @Override public double read() {
            try { return list.get(index) instanceof Number number ? number.doubleValue() : Double.NaN; }
            catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return Double.NaN; }
        }

        @Override public boolean write(double value) {
            try {
                Object current = list.get(index);
                Object boxed = coerceLike(current, current == null ? Object.class : current.getClass(), value);
                if (boxed == null) return false;
                list.set(index, boxed);
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }
        }

        @Override public Object snapshot() {
            try { return list.get(index); }
            catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("read", t); return null; }
        }

        @Override public void restore(Object snapshot) {
            try { list.set(index, snapshot); }
            catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }
        }

        @Override public String label() {
            return list.getClass().getSimpleName() + "[" + index + "]";
        }
    }

    // 把 double 装回同型装箱值(引用替换)；原始类型也返回对应装箱，field.set/Array.set 自动拆箱
    private static Object coerceLike(Object current, Class<?> type, double v) {
        Class<?> t = current != null ? current.getClass() : type;
        if (t == int.class || t == Integer.class) return Integer.valueOf((int) Math.round(v));
        if (t == long.class || t == Long.class) return Long.valueOf(Math.round(v));
        if (t == float.class || t == Float.class) return Float.valueOf((float) v);
        if (t == double.class || t == Double.class) return Double.valueOf(v);
        if (t == short.class || t == Short.class) return Short.valueOf((short) Math.round(v));
        if (t == byte.class || t == Byte.class) return Byte.valueOf((byte) Math.round(v));
        return null;
    }
}



