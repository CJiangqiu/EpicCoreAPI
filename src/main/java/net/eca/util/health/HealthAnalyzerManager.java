package net.eca.util.health;

import net.eca.util.EcaLogger;
import net.eca.util.health.HealthAnalyzer.AnalysisResult;
import net.eca.util.health.HealthAnalyzer.Call;
import net.eca.util.health.HealthAnalyzer.Choice;
import net.eca.util.health.HealthAnalyzer.EvalContext;
import net.eca.util.health.HealthAnalyzer.Expr;
import net.eca.util.health.HealthAnalyzer.Op;
import net.eca.util.health.HealthAnalyzer.Source;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Phase 3 写入管理器：缓存 per-class 分析结果,把目标血量沿 Expr 树反推到各 Source 并写入。
 * 主路径全失败时走 sister-setter 兜底：扫表达式里数值返回的 Call(读调用),在其 owner class
 * 找参数前缀一致、末位多一个数值参的对偶 setter,重放 read 现场参数后调用。
 */
public final class HealthAnalyzerManager {

    private HealthAnalyzerManager() {}

    private static final Map<Class<?>, AnalysisResult> CACHE = new ConcurrentHashMap<>();

    //已确认有效的写入路径：每个实体类记录"哪一级策略真正改动了血量"，供后续调用走快路径直达。
    //仅在某策略 verify 通过后写入——绝不缓存失败，避免一次误判把实体永久判死
    private static final Map<Class<?>, WriteStrategy> CONFIRMED_PATH = new ConcurrentHashMap<>();

    //血量写入策略，按开销从小到大
    public enum WriteStrategy { VANILLA, ENTITY_SETTER, SYMBOLIC, PROBE, DYNAMIC }

    //获取该实体类已确认有效的写入路径，无则返回 null
    public static WriteStrategy getConfirmedPath(Class<?> entityClass) {
        return entityClass == null ? null : CONFIRMED_PATH.get(entityClass);
    }

    //记录已确认有效的写入路径。调用前必须确保该策略刚刚使 getHealth 达到目标值
    public static void confirmPath(Class<?> entityClass, WriteStrategy strategy) {
        if (entityClass != null && strategy != null) CONFIRMED_PATH.put(entityClass, strategy);
    }

    //已完整记录过写入失败的实体类，去重用：每类只告警一次（含失败原因），之后永久静默，避免每-tick 改血的实体刷屏
    private static final Set<String> WARNED_FAILURES = ConcurrentHashMap.newKeySet();

    //已打印过完整动态轨迹的实体类：verbose dump 每类只打一次（仅控制日志详尽度，不影响是否执行动态求解）
    private static final Set<String> DYNAMIC_DUMPED = ConcurrentHashMap.newKeySet();

    /* ==================== 对外入口 ==================== */

    //Phase 3 静态：按 per-class 缓存的分析结果反推每个 Source 并写入 + sister-setter 兜底。任一写成功即 true
    public static boolean writeAll(LivingEntity entity, float expected) {
        if (entity == null) return false;
        AnalysisResult ar = CACHE.computeIfAbsent(entity.getClass(), HealthAnalyzerManager::safeAnalyze);

        //诊断行只在最终失败时打印,因此使用 List 收集而非直接落日志,避免成功路径污染
        List<String> diag = new ArrayList<>();
        boolean anyWrote = false;
        if (ar.isEmpty()) {
            diag.add("  AnalysisResult.EMPTY: no Source identified (getHealth not overridden or bytecode not symbolizable)");
        } else {
            diag.add("  Source count=" + ar.sources.size());
            EvalContext ctx = new EvalCtx(entity);
            for (Source sink : ar.sources) {
                Object value = HealthAnalyzer.solveFor(ar.returnExpr, sink, Float.valueOf(expected), ctx);
                if (value == null) {
                    diag.add("    [" + sink.label + "] solve=null (missing duality rule or multiple sinks in one branch)");
                    continue;
                }
                boolean ok = sink.write(entity, value);
                if (ok) { anyWrote = true; diag.add("    [" + sink.label + "] solve=" + value + " write=OK"); }
                else      diag.add("    [" + sink.label + "] solve=" + value + " write=FAIL");
            }
        }

        if (!anyWrote) {
            boolean sister = trySisterSetters(entity, expected, ar);
            diag.add(sister ? "  sister-setter: OK" : "  sister-setter: failed");
            anyWrote = sister;
        }

        //失败日志按实体类去重：每个类只完整记录一次（含原因），之后永久静默
        if (!anyWrote && WARNED_FAILURES.add(entity.getClass().getName())) {
            EcaLogger.warn("writeAll(static) failed entity={} expected={} current={} (further failures for this class are suppressed)",
                entity.getClass().getName(), expected, safeGetHealth(entity));
            for (String line : diag) EcaLogger.warn(line);
        }
        return anyWrote;
    }

    //Phase 4 动态：静态彻底无法定位存储时，运行时插桩取证 + 反演写回。每次调用都现场探测，不做失败跳过
    public static boolean dynamicResolve(LivingEntity entity, float expected) {
        if (entity == null) return false;
        //verbose dump 每类只打一次，避免刷屏（求解本身每次都跑）
        boolean verbose = DYNAMIC_DUMPED.add(entity.getClass().getName());
        return DynamicHealthAnalyzer.probeAndResolve(entity, expected, verbose);
    }

    private static float safeGetHealth(LivingEntity entity) {
        try { return entity.getHealth(); } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return Float.NaN; }
    }

    private static AnalysisResult safeAnalyze(Class<?> c) {
        try {
            return HealthAnalyzer.analyze(c);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            EcaLogger.info("[HealthAnalyzerManager] analyze {} failed: {}", c.getName(), t.toString());
            return AnalysisResult.EMPTY;
        }
    }

    private record EvalCtx(LivingEntity entity) implements EvalContext {
        @Override public Object eval(Expr e) { return HealthAnalyzer.evaluate(e, this); }
    }

    /* ==================== sister-setter 兜底 ==================== */

    private static final List<String> SETTER_VERBS = List.of("set", "write", "store", "update", "save", "put", "modify");
    private static final List<String> SETTER_NOUNS = List.of("health", "hp", "life");

    private static boolean trySisterSetters(LivingEntity entity, float expected, AnalysisResult ar) {
        // 扫表达式树里数值返回的 Call,逐个尝试对偶 setter
        List<Call> calls = new ArrayList<>();
        collectCalls(ar.returnExpr, calls, new HashSet<>());
        EvalCtx ctx = new EvalCtx(entity);
        for (Call call : calls) {
            if (trySisterSetterForCall(entity, expected, call, ctx)) return true;
        }
        // 最后兜底：实体类自带的 set*health* 单参方法
        return trySetterByName(entity, expected);
    }

    private static void collectCalls(Expr e, List<Call> out, Set<String> seen) {
        if (e == null) return;
        if (e instanceof Call call) {
            if (isNumericReturn(Type.getReturnType(call.desc()))) {
                String sig = call.owner() + "#" + call.name() + call.desc();
                if (seen.add(sig)) out.add(call);
            }
            for (Expr a : call.args()) collectCalls(a, out, seen);
            return;
        }
        if (e instanceof Op op) {
            for (Expr a : op.args()) collectCalls(a, out, seen);
            return;
        }
        if (e instanceof Choice c) {
            for (Expr a : c.alternatives()) collectCalls(a, out, seen);
        }
    }

    private static boolean isNumericReturn(Type t) {
        return switch (t.getSort()) {
            case Type.INT, Type.FLOAT, Type.LONG, Type.DOUBLE, Type.SHORT, Type.BYTE -> true;
            default -> false;
        };
    }

    //在 read Call 的 owner class 找对偶 setter：参数前缀和 read 一致,末位多一个数值参
    private static boolean trySisterSetterForCall(LivingEntity entity, float expected, Call call, EvalCtx ctx) {
        try {
            Class<?> owner = Class.forName(call.owner().replace('/', '.'), false,
                Thread.currentThread().getContextClassLoader());
            Type[] readArgTypes = Type.getArgumentTypes(call.desc());
            boolean readHasReceiver = call.args().size() > readArgTypes.length;
            int readArgStart = readHasReceiver ? 1 : 0;

            Method writer = findWriterMethod(owner, readArgTypes);
            if (writer == null) return false;

            Class<?>[] writerParams = writer.getParameterTypes();
            Object[] args = new Object[writerParams.length];
            int valueIdx = writerParams.length - 1;
            for (int i = 0; i < valueIdx; i++) {
                int srcIdx = readArgStart + i;
                if (srcIdx >= call.args().size()) {
                    args[i] = defaultPrim(writerParams[i]);
                } else {
                    Object v = HealthAnalyzer.evaluate(call.args().get(srcIdx), ctx);
                    args[i] = coerceArg(v, writerParams[i]);
                }
            }
            args[valueIdx] = coerceNumber(expected, writerParams[valueIdx]);

            Object recv = Modifier.isStatic(writer.getModifiers()) ? null : entity;
            writer.setAccessible(true);
            writer.invoke(recv, args);
            return Math.abs(entity.getHealth() - expected) < 1.0f;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            return false;
        }
    }

    private static Method findWriterMethod(Class<?> owner, Type[] readArgTypes) {
        for (Method m : owner.getDeclaredMethods()) {
            String lname = m.getName().toLowerCase();
            boolean hasVerb = false;
            for (String v : SETTER_VERBS) if (lname.contains(v)) { hasVerb = true; break; }
            if (!hasVerb) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 0) continue;
            int expectedFirstN;
            if (params.length == readArgTypes.length) expectedFirstN = readArgTypes.length - 1;
            else if (params.length == readArgTypes.length + 1) expectedFirstN = readArgTypes.length;
            else continue;
            if (expectedFirstN < 0) continue;
            if (!isNumericPrimitive(params[params.length - 1])) continue;
            boolean prefixMatch = true;
            for (int i = 0; i < expectedFirstN; i++) {
                Class<?> rt = HealthAnalyzer.asmTypeToClass(readArgTypes[i]);
                if (rt == null || !params[i].equals(rt)) { prefixMatch = false; break; }
            }
            if (prefixMatch) return m;
        }
        return null;
    }

    //最终兜底：实体类继承链上的 set*health* 单数字参方法
    private static boolean trySetterByName(LivingEntity entity, float expected) {
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (!isNumericPrimitive(p)) continue;
                String name = m.getName().toLowerCase();
                if (name.contains("max")) continue;
                boolean verb = false, noun = false;
                for (String v : SETTER_VERBS) if (name.startsWith(v)) { verb = true; break; }
                for (String n : SETTER_NOUNS) if (name.contains(n)) { noun = true; break; }
                if (!verb || !noun) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(entity, coerceNumber(expected, p));
                    if (Math.abs(entity.getHealth() - expected) < 1.0f) return true;
                } catch (Throwable ignored) { if (ignored instanceof VirtualMachineError) throw (VirtualMachineError) ignored; }
            }
        }
        return false;
    }

    /* ==================== 工具 ==================== */

    private static boolean isNumericPrimitive(Class<?> c) {
        return c == float.class || c == double.class || c == int.class
            || c == long.class || c == short.class || c == byte.class;
    }

    private static Object coerceNumber(float v, Class<?> type) {
        if (type == float.class) return v;
        if (type == double.class) return (double) v;
        if (type == int.class) return (int) v;
        if (type == long.class) return (long) v;
        if (type == short.class) return (short) v;
        if (type == byte.class) return (byte) v;
        return v;
    }

    private static Object coerceArg(Object v, Class<?> targetType) {
        if (v == null) return targetType.isPrimitive() ? defaultPrim(targetType) : null;
        if (targetType.isInstance(v)) return v;
        if (v instanceof Number n) {
            if (targetType == int.class || targetType == Integer.class) return n.intValue();
            if (targetType == long.class || targetType == Long.class) return n.longValue();
            if (targetType == float.class || targetType == Float.class) return n.floatValue();
            if (targetType == double.class || targetType == Double.class) return n.doubleValue();
            if (targetType == short.class || targetType == Short.class) return n.shortValue();
            if (targetType == byte.class || targetType == Byte.class) return n.byteValue();
        }
        return v;
    }

    private static Object defaultPrim(Class<?> t) {
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == boolean.class) return false;
        if (t == char.class) return (char) 0;
        return null;
    }
}
