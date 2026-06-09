package net.eca.util.health;

import net.eca.util.EcaLogger;
import net.eca.util.health.HealthAnalyzer.AnalysisResult;
import net.eca.util.health.HealthAnalyzer.EvalContext;
import net.eca.util.health.HealthAnalyzer.Expr;
import net.eca.util.health.HealthAnalyzer.Source;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 静态写入管理器缓存类型化分析结果与已验证写入计划。
 * 精确反演失败时只对已定位 Source 执行数值求解，不依赖方法名或容器类型猜测。
 */
public final class HealthAnalyzerManager {

    private HealthAnalyzerManager() {}

    private static final Map<Class<?>, AnalysisResult> CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, HealthWritePlan> CONFIRMED_PLANS = new ConcurrentHashMap<>();

    //已确认有效的写入路径：每个实体类记录"哪一级策略真正改动了血量"，供后续调用走快路径直达。
    //仅在某策略 verify 通过后写入——绝不缓存失败，避免一次误判把实体永久判死
    private static final Map<Class<?>, WriteStrategy> CONFIRMED_PATH = new ConcurrentHashMap<>();

    //血量写入策略，按开销从小到大
    public enum WriteStrategy { VANILLA, SYMBOLIC, PROBE, DYNAMIC }

    //获取该实体类已确认有效的写入路径，无则返回 null
    public static WriteStrategy getConfirmedPath(Class<?> entityClass) {
        return entityClass == null ? null : CONFIRMED_PATH.get(entityClass);
    }

    //记录已确认有效的写入路径。调用前必须确保该策略刚刚使 getHealth 达到目标值
    public static void confirmPath(Class<?> entityClass, WriteStrategy strategy) {
        if (entityClass != null && strategy != null) CONFIRMED_PATH.put(entityClass, strategy);
    }

    public static HealthWritePlan getConfirmedPlan(Class<?> entityClass) {
        return entityClass == null ? null : CONFIRMED_PLANS.get(entityClass);
    }

    public static void confirmPlan(Class<?> entityClass, HealthWritePlan plan) {
        if (entityClass != null && plan != null) CONFIRMED_PLANS.put(entityClass, plan);
    }

    //已完整记录过写入失败的实体类，去重用：每类只告警一次（含失败原因），之后永久静默，避免每-tick 改血的实体刷屏
    private static final Set<String> WARNED_FAILURES = ConcurrentHashMap.newKeySet();

    //已打印过完整动态轨迹的实体类：verbose dump 每类只打一次（仅控制日志详尽度，不影响是否执行动态求解）
    private static final Set<String> DYNAMIC_DUMPED = ConcurrentHashMap.newKeySet();

    /* ==================== 对外入口 ==================== */

    //按 per-class 缓存的分析结果反推每个 Source，成功后缓存可重放计划
    public static boolean writeAll(LivingEntity entity, float expected) {
        if (entity == null) return false;
        HealthWritePlan confirmed = CONFIRMED_PLANS.get(entity.getClass());
        if (confirmed != null && confirmed.execute(entity, expected)) return true;

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
                HealthSolveResult solved = HealthAnalyzer.solveDetailed(ar.returnExpr, sink, Float.valueOf(expected), ctx);
                if (!solved.solved()) {
                    HealthWritePlan numeric = numericPlan(sink);
                    if (numeric.execute(entity, expected)) {
                        anyWrote = true;
                        CONFIRMED_PLANS.put(entity.getClass(), numeric);
                        diag.add("    [" + sink.label + "] solve=NUMERIC_FALLBACK write=OK");
                        break;
                    }
                    diag.add("    [" + sink.label + "] solve=" + solved.failure() + " detail=" + solved.detail()
                        + " numeric=FAIL");
                    continue;
                }
                HealthWritePlan plan = symbolicPlan(ar.returnExpr, sink);
                boolean ok = plan.execute(entity, expected);
                if (ok) {
                    anyWrote = true;
                    CONFIRMED_PLANS.put(entity.getClass(), plan);
                    diag.add("    [" + sink.label + "] solve=" + solved.value() + " write=OK");
                    break;
                }
                diag.add("    [" + sink.label + "] solve=" + solved.value() + " write=FAIL");
            }
        }

        //失败日志按实体类去重：每个类只完整记录一次（含原因），之后永久静默
        if (!anyWrote && WARNED_FAILURES.add(entity.getClass().getName())) {
            EcaLogger.warn("writeAll(static) failed entity={} expected={} current={} (further failures for this class are suppressed)",
                entity.getClass().getName(), expected, safeGetHealth(entity));
            for (String line : diag) EcaLogger.warn(line);
        }
        return anyWrote;
    }

    private static HealthWritePlan symbolicPlan(Expr tree, Source sink) {
        HealthWritePlan.Mutation mutation = new HealthWritePlan.Mutation() {
            @Override
            public Object snapshot(LivingEntity entity) {
                return sink.read(entity);
            }

            @Override
            public boolean apply(LivingEntity entity, float target) {
                EvalCtx ctx = new EvalCtx(entity);
                Object solved = HealthAnalyzer.solveFor(tree, sink, Float.valueOf(target), ctx);
                return solved != null && sink.write(entity, solved);
            }

            @Override
            public boolean restore(LivingEntity entity, Object snapshot) {
                return sink.write(entity, snapshot);
            }

            @Override
            public String describe() {
                return sink.label;
            }
        };
        return new HealthWritePlan("symbolic", List.of(mutation));
    }

    private static HealthWritePlan numericPlan(Source sink) {
        HealthWritePlan.Mutation mutation = new HealthWritePlan.Mutation() {
            @Override
            public Object snapshot(LivingEntity entity) {
                return sink.read(entity);
            }

            @Override
            public boolean apply(LivingEntity entity, float target) {
                return SourceNumericSolver.solve(entity, sink, target);
            }

            @Override
            public boolean restore(LivingEntity entity, Object snapshot) {
                return sink.write(entity, snapshot);
            }

            @Override
            public String describe() {
                return sink.label;
            }
        };
        return new HealthWritePlan("source-numeric", List.of(mutation));
    }

    //Dynamic 阶段：静态彻底无法定位存储时，运行时插桩取证 + 反演写回。每次调用都现场探测，不做失败跳过
    public static boolean dynamicResolve(LivingEntity entity, float expected) {
        if (entity == null) return false;
        //verbose dump 每类只打一次，避免刷屏（求解本身每次都跑）
        boolean verbose = DYNAMIC_DUMPED.add(entity.getClass().getName());
        return DynamicHealthTracer.probeAndResolve(entity, expected, verbose);
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

}
