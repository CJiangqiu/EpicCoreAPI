package net.eca.util.health;

import net.eca.coremod.AccessTrace;
import net.eca.util.EcaLogger;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/*
 * 数值黑箱求解器只操作运行轨迹解析出的 JVM 物理位置，不认识字段名、容器类型或实体类型。
 * 它适用于局部连续或分段连续的确定性血量函数；离散且无局部响应的函数交给符号求解器处理。
 */
public final class NumericInversion {

    private NumericInversion() {}

    private static final ThreadLocal<Boolean> IN_PROGRESS = ThreadLocal.withInitial(() -> false);
    private static final int MAX_CELLS = 64;
    private static final int MAX_PASSES = 12;
    private static final int BACKTRACK_STEPS = 14;
    private static final float[] PROBE_STEPS = {1f, 16f, 256f, 4096f};
    private static final float RESPONSE_EPS = 1.0e-3f;

    private static final class Cell {
        final PhysicalLocation location;
        final Class<?> type;
        final double base;
        double slope;

        Cell(PhysicalLocation location, Class<?> type, double base) {
            this.location = location;
            this.type = type;
            this.base = base;
        }
    }

    static boolean solve(LivingEntity entity, float target, List<AccessTrace.Entry> reads, boolean verbose) {
        if (entity == null || reads == null || reads.isEmpty() || IN_PROGRESS.get()) return false;
        IN_PROGRESS.set(true);
        List<Cell> cells = null;
        boolean success = false;
        try {
            float initial = HealthVerify.safeGetHealth(entity);
            if (!Float.isFinite(initial)) return false;
            if (HealthVerify.matches(entity, target)) return true;

            cells = collectCells(reads);
            if (verbose) {
                EcaLogger.warn("[NumericInversion] h0={} target={} candidateLocations={}",
                    initial, target, cells.size());
            }
            if (cells.isEmpty()) return false;

            List<Cell> active = new ArrayList<>();
            for (Cell cell : cells) {
                cell.slope = localSlope(entity, cell);
                if (cell.slope != 0.0 && Double.isFinite(cell.slope)) active.add(cell);
            }
            if (verbose) dumpActive(active, cells.size());
            if (active.isEmpty()) return false;

            double epsilon = Math.max(1.0e-3, Math.abs(target) * 1.0e-5);
            for (int pass = 0; pass < MAX_PASSES; pass++) {
                float health = HealthVerify.safeGetHealth(entity);
                if (!Float.isFinite(health) || Math.abs(target - health) <= epsilon) break;

                for (Cell cell : active) cell.slope = localSlope(entity, cell);
                active.sort(Comparator.comparingDouble((Cell c) -> Math.abs(c.slope)).reversed());

                boolean improved = false;
                for (Cell cell : active) {
                    health = HealthVerify.safeGetHealth(entity);
                    if (!Float.isFinite(health)) break;
                    double residual = target - health;
                    if (Math.abs(residual) <= epsilon || cell.slope == 0.0) break;
                    double current = readCell(cell);
                    if (!Double.isFinite(current)) continue;

                    double predicted = residual / cell.slope;
                    double bestError = Math.abs(residual);
                    boolean accepted = false;
                    double fraction = 1.0;
                    for (int step = 0; step < BACKTRACK_STEPS; step++) {
                        writeCell(cell, current + predicted * fraction);
                        float next = HealthVerify.safeGetHealth(entity);
                        if (Float.isFinite(next) && Math.abs(target - next) < bestError) {
                            accepted = true;
                            improved = true;
                            break;
                        }
                        fraction *= 0.5;
                    }
                    if (!accepted) writeCell(cell, current);
                }
                if (!improved) break;
            }

            success = HealthVerify.matches(entity, target);
            if (verbose) {
                EcaLogger.warn("[NumericInversion] result={} finalH={} target={}",
                    success, HealthVerify.safeGetHealth(entity), target);
            }
            return success;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.warn("[NumericInversion] exception: {}", t.toString());
            return false;
        } finally {
            if (!success && cells != null) {
                for (Cell cell : cells) writeCell(cell, cell.base);
            }
            IN_PROGRESS.set(false);
        }
    }

    private static List<Cell> collectCells(List<AccessTrace.Entry> reads) {
        List<Cell> cells = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AccessTrace.Entry read : reads) {
            if (cells.size() >= MAX_CELLS) break;
            if (!(read.value instanceof Number number)) continue;
            PhysicalLocation location = PhysicalLocations.fromTrace(read);
            if (location == null || !seen.add(location.describe())) continue;
            Class<?> type = numericType(location.valueType(), read.value.getClass());
            if (type != null) cells.add(new Cell(location, type, number.doubleValue()));
        }
        return cells;
    }

    private static Class<?> numericType(Class<?> declared, Class<?> runtime) {
        if (isNumericType(declared)) return declared;
        return isNumericType(runtime) ? runtime : null;
    }

    private static double localSlope(LivingEntity entity, Cell cell) {
        double current = readCell(cell);
        if (!Double.isFinite(current)) return 0.0;
        float initial = HealthVerify.safeGetHealth(entity);
        if (!Float.isFinite(initial)) return 0.0;
        for (float step : PROBE_STEPS) {
            writeCell(cell, current + step);
            float health = HealthVerify.safeGetHealth(entity);
            writeCell(cell, current);
            if (Float.isFinite(health) && Math.abs(health - initial) > RESPONSE_EPS) {
                return (health - initial) / step;
            }
        }
        return 0.0;
    }

    private static double readCell(Cell cell) {
        try {
            Object value = cell.location.read();
            return value instanceof Number number ? number.doubleValue() : Double.NaN;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return Double.NaN;
        }
    }

    private static void writeCell(Cell cell, double value) {
        try {
            cell.location.write(box(cell.type, value));
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
    }

    private static Object box(Class<?> type, double value) {
        if (type == int.class || type == Integer.class) return (int) Math.round(value);
        if (type == long.class || type == Long.class) return Math.round(value);
        if (type == short.class || type == Short.class) return (short) Math.round(value);
        if (type == byte.class || type == Byte.class) return (byte) Math.round(value);
        if (type == float.class || type == Float.class) return (float) value;
        if (type == double.class || type == Double.class) return value;
        return value;
    }

    private static boolean isNumericType(Class<?> type) {
        return type == int.class || type == long.class || type == short.class || type == byte.class
            || type == float.class || type == double.class || type == Integer.class || type == Long.class
            || type == Short.class || type == Byte.class || type == Float.class || type == Double.class;
    }

    private static void dumpActive(List<Cell> active, int total) {
        active.sort(Comparator.comparingDouble((Cell c) -> Math.abs(c.slope)).reversed());
        EcaLogger.warn("[NumericInversion] responsiveLocations={}/{}", active.size(), total);
        for (int i = 0; i < Math.min(8, active.size()); i++) {
            Cell cell = active.get(i);
            EcaLogger.warn("[NumericInversion]   active#{} {} slope={}",
                i, cell.location.describe(), cell.slope);
        }
    }
}
