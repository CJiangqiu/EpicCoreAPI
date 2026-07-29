package net.eca.util.health;

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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 数值反演处理无法符号求逆的自定义解码节点。它从相关运行期对象中收集可写数值单元，
 * 通过扰动估算斜率并迭代逼近目标血量。写入使用引用替换，并由快照、超时和异常处理限制副作用。
 * 搜索范围由静态分析结果限定，对象图遍历还设有深度和时间上限。
 */
public final class NumericInverter {

    private NumericInverter() {}

    private static final long TIME_BUDGET_NANOS = 200_000_000L;   // 单次搜索 wall-clock 预算
    private static final int MAX_PASSES = 64;                      // 坐标下降迭代上限(超时为主，本值为备)
    private static final int MAX_WALK_DEPTH = 64;                  // 对象图递归深度上限，防深图爆栈(StackOverflowError)
    private static final double PERTURB = 1.0;                     // 测斜率的单位微扰
    private static final int ENTITY_WALK_CELL_CAP = 2048;          // 实体根遍历的 cell 上限：防 vanilla 对象图扩散耗光预算

    /* 搜索结局诊断去重：每类每原因只打一次，避免每-tick 改血刷屏 */
    private static final Set<String> DIAG_DUMPED = ConcurrentHashMap.newKeySet();

    private static void diag(LivingEntity entity, String reason) {
        if (DIAG_DUMPED.add(entity.getClass().getName() + "|" + reason))
            EcaLogger.info("[NumericInverter] {} entity={}", reason, entity.getClass().getName());
    }

    /* 从无法反演节点的运行期对象开始搜索；校验失败时恢复所有改动。 */
    public static boolean search(LivingEntity entity, float target, List<Object> roots) {
        if (entity == null || roots == null || roots.isEmpty()) return false;
        long deadline = System.nanoTime() + TIME_BUDGET_NANOS;

        List<Cell> cells = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        // 优先遍历数据流定位的容器根，再限制实体对象图的候选数量，避免无关对象耗尽搜索预算
        for (Object root : roots) {
            if (!(root instanceof Entity)) walk(root, cells, visited, deadline, 0, Integer.MAX_VALUE);
        }
        int entityCellCap = cells.size() + ENTITY_WALK_CELL_CAP;
        for (Object root : roots) {
            if (root instanceof Entity) walk(root, cells, visited, deadline, 0, entityCellCap);
        }
        if (cells.isEmpty()) {
            diag(entity, "no perturbable numeric cells reachable from dead-end roots (roots=" + roots.size() + ")");
            return false;
        }

        Object[] snapshot = new Object[cells.size()];
        for (int i = 0; i < cells.size(); i++) snapshot[i] = cells.get(i).snapshot();

        try {
            float baseline = EcaSetHealthManager.readHealthAnchor(entity);
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
                float h = EcaSetHealthManager.readHealthAnchor(entity);
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

            for (int pass = 0; pass < MAX_PASSES; pass++) {
                if (System.nanoTime() > deadline) break;
                float h = EcaSetHealthManager.readHealthAnchor(entity);
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

            boolean ok = EcaSetHealthManager.verify(entity, target);
            if (!ok) {
                diag(entity, "descent did not reach target (cells=" + cells.size() + " relevant=" + relevant.size() + ")");
                rollback(cells, snapshot);
            } else EcaLogger.info("[NumericInverter] hit entity={} target={} cells={} relevant={}",
                    entity.getClass().getName(), target, cells.size(), relevant.size());
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
        float tol = Math.max(0.5f, Math.abs(target) * 0.02f);
        return Math.abs(actual - target) <= tol;
    }

    private static float step(LivingEntity entity, float target, Cell cell, double current, double delta, float before) {
        if (!Double.isFinite(delta)) return before;
        Object exact = cell.snapshot();
        double beforeError = Math.abs((double) target - before);
        double scale = 1.0;
        for (int attempt = 0; attempt < 12; attempt++, scale *= 0.5) {
            cell.restore(exact);
            if (!cell.write(current + delta * scale)) continue;
            float after = EcaSetHealthManager.readHealthAnchor(entity);
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

    // ==================== 对象图遍历：收集可扰动原始 cell ====================

    private static void walk(Object obj, List<Cell> cells, Set<Object> visited, long deadline, int depth, int cellCap) {
        if (obj == null || depth > MAX_WALK_DEPTH || System.nanoTime() > deadline || cells.size() >= cellCap) return;
        if (obj instanceof Number || obj instanceof Boolean || obj instanceof Character || obj instanceof String) return;
        if (!visited.add(obj)) return;
        Class<?> cls = obj.getClass();
        if (isSkippable(cls)) return;

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
                || MinecraftServer.class.isAssignableFrom(cls)
                || RegistryAccess.class.isAssignableFrom(cls)
                || Registry.class.isAssignableFrom(cls)
                || Holder.class.isAssignableFrom(cls);
    }

    // ==================== Cell：读/写(引用替换)/快照/回滚 ====================

    private interface Cell {
        double read();
        boolean write(double v);
        Object snapshot();
        void restore(Object snap);
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
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
        }

        @Override public Object snapshot() {
            try { return field.get(owner); } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return null; }
        }

        @Override public void restore(Object snap) { put(snap); }

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
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
        }

        @Override public Object snapshot() {
            try { return Array.get(array, index); } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return null; }
        }

        @Override public void restore(Object snap) {
            try { Array.set(array, index, snap); } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }
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
