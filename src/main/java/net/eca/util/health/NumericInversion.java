package net.eca.util.health;

import net.eca.coremod.AccessTrace;
import net.eca.util.EcaLogger;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * 数值反演：数据流逆向与模式识别都够不着时的通用安全网。
 * 不读解码逻辑，把 getHealth 当确定性黑盒 H=F(cells)：以取证捕获的 cell 全集为输入向量，
 * 逐个扰动量响应 ∂H/∂cell 恢复局部线性结构，按灵敏度降序坐标下降逼近目标(主导位优先、自然吸收进位/钳制)。
 * 全程 snapshot→trial→restore：任何一步不达标或抛异常都整体回滚，绝不把实体改成中间脏值。
 * 不认识任何 mod，只依赖"血量是可写堆存储的确定性、单调或局部线性的函数"。
 */
public final class NumericInversion {

    private NumericInversion() {}

    //防重入：探测窗口内的 getHealth 不应再触发数值反演
    private static final ThreadLocal<Boolean> IN_PROGRESS = ThreadLocal.withInitial(() -> false);

    private static final int MAX_CELLS = 64;                          // 候选 cell 上限，宽存储实体限量
    private static final int MAX_PASSES = 12;                         // 坐标下降回合上限
    private static final int BACKTRACK_STEPS = 14;                    // 回溯线搜索每步最多减半次数
    private static final float[] PROBE_STEPS = {1f, 16f, 256f, 4096f}; // 逐级放大扰动直到响应可测
    private static final float RESPONSE_EPS = 1.0e-3f;                // 判定扰动是否引起 getHealth 变化的阈值

    //一个可写数值 cell：数组元素 或 对象字段
    private static final class Cell {
        final Object container;   // 数组对象 或 字段所属对象
        final Field field;        // 字段写；数组写时为 null
        final int index;          // 数组下标；字段写时 -1
        final Class<?> type;      // 写回时的目标数值类型(基本类型 / 装箱类型)
        final double base;        // 快照原值，用于回滚
        double slope;             // ∂H/∂cell

        Cell(Object container, Field field, int index, Class<?> type, double base) {
            this.container = container;
            this.field = field;
            this.index = index;
            this.type = type;
            this.base = base;
        }
    }

    //入口：以取证捕获的 reads 为输入向量数值反演。命中返回 true；否则保证实体已回滚到调用前状态
    static boolean solve(LivingEntity entity, float target, List<AccessTrace.Entry> reads, boolean verbose) {
        if (entity == null || reads == null || reads.isEmpty() || IN_PROGRESS.get()) return false;
        IN_PROGRESS.set(true);
        List<Cell> cells = null;
        boolean success = false;
        try {
            float h0 = HealthVerify.safeGetHealth(entity);
            if (!Float.isFinite(h0)) return false;
            if (HealthVerify.matches(entity, target)) { success = true; return true; }

            cells = collectCells(reads);
            if (verbose) EcaLogger.warn("[NumericInversion] h0={} target={} candidateCells={}", h0, target, cells.size());
            if (cells.isEmpty()) return false;

            //探灵敏度：逐 cell 在当前值附近量 ∂H/∂cell，留下确实影响血量的 cell
            List<Cell> active = new ArrayList<>();
            for (Cell c : cells) {
                double slope = localSlope(entity, c);
                if (slope != 0.0 && Double.isFinite(slope)) {
                    c.slope = slope;
                    active.add(c);
                }
            }
            if (verbose) {
                EcaLogger.warn("[NumericInversion] responsiveCells={}/{}", active.size(), cells.size());
                active.sort((a, b) -> Double.compare(Math.abs(b.slope), Math.abs(a.slope)));
                int shown = Math.min(8, active.size());
                for (int i = 0; i < shown; i++) {
                    Cell c = active.get(i);
                    EcaLogger.warn("[NumericInversion]   active#{} {} slope={}", i, label(c), c.slope);
                }
            }
            if (active.isEmpty()) return false;

            //收敛阈值远比 verify 容差(target=0 时高达 1.0)严格，避免下降在"粗略达标"处早停留下偏差(如设 0 停在 0.97)
            double eps = Math.max(1.0e-3, Math.abs(target) * 1.0e-5);

            /* 坐标下降 + 回溯线搜索：解码非线性(进位/未归一 limb 致斜率随点变化、甚至变号)，
               整步线性外推会把数字推到荒谬值、令解码爆成 ±Inf。故每回合重算局部斜率、按灵敏度降序，
               每个 cell 的预测步长按比例逐步减半回溯，只接受"getHealth 有限且残差严格减小"的写入，杜绝发散。 */
            for (int pass = 0; pass < MAX_PASSES; pass++) {
                float h = HealthVerify.safeGetHealth(entity);
                if (!Float.isFinite(h)) break;
                if (Math.abs(target - h) <= eps) break;

                for (Cell c : active) c.slope = localSlope(entity, c);
                active.sort((a, b) -> Double.compare(Math.abs(b.slope), Math.abs(a.slope)));

                boolean improved = false;
                for (Cell c : active) {
                    h = HealthVerify.safeGetHealth(entity);
                    if (!Float.isFinite(h)) break;
                    double residual = target - h;
                    if (Math.abs(residual) <= eps) break;
                    if (c.slope == 0.0) continue;
                    double cur = readCell(c);
                    if (Double.isNaN(cur)) continue;

                    double full = residual / c.slope;
                    double bestErr = Math.abs(residual);
                    boolean accepted = false;
                    double frac = 1.0;
                    for (int b = 0; b < BACKTRACK_STEPS; b++) {
                        writeCell(c, cur + full * frac);
                        float h2 = HealthVerify.safeGetHealth(entity);
                        if (Float.isFinite(h2) && Math.abs(target - h2) < bestErr) {
                            accepted = true;
                            improved = true;
                            break;
                        }
                        frac *= 0.5;
                    }
                    if (!accepted) writeCell(c, cur);
                }
                if (!improved) break;
            }

            success = HealthVerify.matches(entity, target);
            if (verbose) EcaLogger.warn("[NumericInversion] result={} finalH={} (target={})",
                success, HealthVerify.safeGetHealth(entity), target);
            return success;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.warn("[NumericInversion] exception: {}", t.toString());
            return false;
        } finally {
            //失败或异常都整体回滚，绝不留脏值；成功则保留写入
            if (!success && cells != null) {
                for (Cell c : cells) writeCell(c, c.base);
            }
            IN_PROGRESS.set(false);
        }
    }

    //在 cell 当前值附近量局部斜率 ∂H/∂cell，逐级放大步长直到响应可测；测完还原到当前值
    private static double localSlope(LivingEntity entity, Cell c) {
        double cur = readCell(c);
        if (Double.isNaN(cur)) return 0.0;
        float h0 = HealthVerify.safeGetHealth(entity);
        if (!Float.isFinite(h0)) return 0.0;
        for (float step : PROBE_STEPS) {
            writeCell(c, cur + step);
            float h = HealthVerify.safeGetHealth(entity);
            writeCell(c, cur);
            if (Float.isFinite(h) && Math.abs(h - h0) > RESPONSE_EPS) return (h - h0) / step;
        }
        return 0.0;
    }

    /* ==================== 候选 cell 收集 ==================== */

    //从取证 reads 中提取可写数值 cell(数组元素 / 数值字段 / 持有装箱数值的对象字段)，按容器身份+槽位去重
    private static List<Cell> collectCells(List<AccessTrace.Entry> reads) {
        List<Cell> cells = new ArrayList<>();
        Map<Object, Set<String>> seen = new IdentityHashMap<>();
        for (AccessTrace.Entry e : reads) {
            if (cells.size() >= MAX_CELLS) break;
            if (e.container == null || !(e.value instanceof Number num)) continue;
            double base = num.doubleValue();

            if (e.index >= 0) {
                Class<?> arrClass = e.container.getClass();
                if (!arrClass.isArray()) continue;
                Class<?> comp = arrClass.getComponentType();
                if (!isNumericType(comp) && comp != Object.class) continue;
                if (!mark(seen, e.container, "[" + e.index)) continue;
                Class<?> type = comp.isPrimitive() || isBoxed(comp) ? comp : e.value.getClass();
                cells.add(new Cell(e.container, null, (int) e.index, type, base));
            } else {
                String fname = fieldNameFromSite(e.site);
                if (fname == null) continue;
                Field f = resolveField(e.container, fname);
                if (f == null) continue;
                Class<?> ft = f.getType();
                Class<?> type = ft.isPrimitive() || isBoxed(ft) ? ft : e.value.getClass();
                if (!isNumericType(type)) continue;
                if (!mark(seen, e.container, "#" + fname)) continue;
                cells.add(new Cell(e.container, f, -1, type, base));
            }
        }
        return cells;
    }

    //read 点描述形如 "owner#method owner.field:desc"，取最后一段的字段名
    private static String fieldNameFromSite(String site) {
        if (site == null) return null;
        int sp = site.lastIndexOf(' ');
        if (sp < 0) return null;
        String ref = site.substring(sp + 1);
        int colon = ref.indexOf(':');
        if (colon >= 0) ref = ref.substring(0, colon);
        int dot = ref.lastIndexOf('.');
        if (dot < 0) return null;
        return ref.substring(dot + 1);
    }

    private static Field resolveField(Object container, String fieldName) {
        for (Class<?> c = container.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
            }
        }
        return null;
    }

    private static boolean mark(Map<Object, Set<String>> seen, Object container, String key) {
        return seen.computeIfAbsent(container, k -> new HashSet<>()).add(key);
    }

    /* ==================== cell 读写 ==================== */

    private static double readCell(Cell c) {
        try {
            Object v = c.field == null ? Array.get(c.container, c.index) : c.field.get(c.container);
            return v instanceof Number n ? n.doubleValue() : Double.NaN;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return Double.NaN;
        }
    }

    private static void writeCell(Cell c, double value) {
        try {
            if (c.field == null) {
                Class<?> comp = c.container.getClass().getComponentType();
                if (comp != null && comp.isPrimitive()) setPrimArray(c.container, c.index, comp, value);
                else Array.set(c.container, c.index, box(c.type, value));
            } else {
                c.field.set(c.container, box(c.type, value));
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
    }

    private static void setPrimArray(Object arr, int idx, Class<?> comp, double v) {
        if (comp == int.class) Array.setInt(arr, idx, (int) Math.round(v));
        else if (comp == long.class) Array.setLong(arr, idx, Math.round(v));
        else if (comp == short.class) Array.setShort(arr, idx, (short) Math.round(v));
        else if (comp == byte.class) Array.setByte(arr, idx, (byte) Math.round(v));
        else if (comp == float.class) Array.setFloat(arr, idx, (float) v);
        else if (comp == double.class) Array.setDouble(arr, idx, v);
    }

    //按目标数值类型装箱(整型四舍五入)。写入装箱字段或对象槽位时返回对应包装对象
    private static Object box(Class<?> type, double v) {
        if (type == int.class || type == Integer.class) return (int) Math.round(v);
        if (type == long.class || type == Long.class) return Math.round(v);
        if (type == short.class || type == Short.class) return (short) Math.round(v);
        if (type == byte.class || type == Byte.class) return (byte) Math.round(v);
        if (type == float.class || type == Float.class) return (float) v;
        if (type == double.class || type == Double.class) return v;
        return (int) Math.round(v);
    }

    private static boolean isNumericType(Class<?> t) {
        return t == int.class || t == long.class || t == short.class || t == byte.class
            || t == float.class || t == double.class || isBoxed(t);
    }

    private static boolean isBoxed(Class<?> t) {
        return t == Integer.class || t == Long.class || t == Short.class || t == Byte.class
            || t == Float.class || t == Double.class;
    }

    //诊断用：cell 的简短标签(容器类型 + 字段名/下标 + 原值)
    private static String label(Cell c) {
        String slot = c.field != null ? "#" + c.field.getName() : "[" + c.index + "]";
        return c.container.getClass().getSimpleName() + slot + " base=" + c.base;
    }
}
