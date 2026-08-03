package net.eca.util.health;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.eca.coremod.JvmTiChannel;
import net.eca.util.EcaLogger;
import net.eca.util.health.HealthDataflowAnalyzer.AnalysisResult;
import net.eca.util.health.HealthDataflowAnalyzer.ArrayElementSource;
import net.eca.util.health.HealthDataflowAnalyzer.CapabilityDataSource;
import net.eca.util.health.HealthDataflowAnalyzer.Call;
import net.eca.util.health.HealthDataflowAnalyzer.ChainedFieldSource;
import net.eca.util.health.HealthDataflowAnalyzer.Choice;
import net.eca.util.health.HealthDataflowAnalyzer.Closure;
import net.eca.util.health.HealthDataflowAnalyzer.ConstOverrideSource;
import net.eca.util.health.HealthDataflowAnalyzer.EvalContext;
import net.eca.util.health.HealthDataflowAnalyzer.Expr;
import net.eca.util.health.HealthDataflowAnalyzer.FieldChainSource;
import net.eca.util.health.HealthDataflowAnalyzer.FieldStep;
import net.eca.util.health.HealthDataflowAnalyzer.MapEntrySource;
import net.eca.util.health.HealthDataflowAnalyzer.MaintenanceBranch;
import net.eca.util.health.HealthDataflowAnalyzer.MaintenancePlan;
import net.eca.util.health.HealthDataflowAnalyzer.MethodCallSource;
import net.eca.util.health.HealthDataflowAnalyzer.Op;
import net.eca.util.health.HealthDataflowAnalyzer.OptionalContentExpr;
import net.eca.util.health.HealthDataflowAnalyzer.Primitive;
import net.eca.util.health.HealthDataflowAnalyzer.Source;
import net.eca.util.health.HealthDataflowAnalyzer.StaticFieldSource;
import net.eca.util.health.HealthDataflowAnalyzer.StoreWrite;
import net.eca.util.health.HealthDataflowAnalyzer.SynchedDataSource;
import net.eca.util.reflect.UnsafeUtil;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.saveddata.SavedData;
import org.objectweb.asm.Type;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 数据流改血落地层：吃 HealthDataflowAnalyzer 产出的可写树(AnalysisResult)，
 * 把求解结果写进目标真实存储并校验，失败回滚。
 * Source 的写入按具体类型分派到本类，Unsafe、反射和 retransform 操作也集中在此，
 * 使分析器保持只读且不依赖 ECA 运行期状态。
 */
public final class HealthDataFlow {

    private HealthDataFlow() {}

    /* ==================== 启动注入：把分析器接入 ECA 运行期 ==================== */

    private static volatile boolean initialized = false;

    /* ECA 启动期由调用者调用一次：把 ECA 运行期字节码源和 ECA hook 类名告诉分析器，
       使其能读到 mixin/coremod 转换后字节码，并在分析树中自动剥离 ECA 注入。 */
    public static void init() {
        if (initialized) return;
        synchronized (HealthDataFlow.class) {
            if (initialized) return;
            HealthDataflowAnalyzer.setClassBytesProvider(HealthDataFlow::classBytesViaRuntime);
            HealthDataflowAnalyzer.setOverrideLookup(ConstOverride::getOverride);
            MethodProbe.setClassBytesProvider(HealthDataFlow::classBytesViaRuntime);
            // ECA 自污染排除：集中定义在 EcaOwnedState，新增注入时不会漏登记
            HealthDataflowAnalyzer.setStripConfig(
                    EcaOwnedState.hookOwners(),
                    EcaOwnedState.staticFieldLabels(),
                    EcaOwnedState.nbtKeys());
            initialized = true;
        }
    }

    /* 诊断去重：每类只报一次字节码来源 */
    private static final Set<String> BYTES_SOURCE_DUMPED = ConcurrentHashMap.newKeySet();

    /* RuntimeBytecodeProvider 优先(含 mixin/coremod 转换后)，缺失回退分析器内置默认实现 */
    private static byte[] classBytesViaRuntime(Class<?> clazz) {
        try {
            byte[] runtime = net.eca.coremod.RuntimeBytecodeProvider.get(clazz);
            if (runtime != null) {
                if (!EcaSetHealthManager.isWarmupDiagnosticsSuppressed() && BYTES_SOURCE_DUMPED.add(clazz.getName()))
                    EcaLogger.info("[HealthDataflow] bytes for {} <- RuntimeBytecodeProvider(runtime,{}B)", clazz.getName(), runtime.length);
                return runtime;
            }
        } catch (Throwable ignored) {
            if (ignored instanceof VirtualMachineError e) throw e;
        }
        if (!EcaSetHealthManager.isWarmupDiagnosticsSuppressed() && BYTES_SOURCE_DUMPED.add(clazz.getName()))
            EcaLogger.info("[HealthDataflow] bytes for {} <- DISK fallback (runtime capture MISSING)", clazz.getName());
        return HealthDataflowAnalyzer.defaultClassBytes(clazz);
    }

    /* ==================== 写入入口 ==================== */

    //每个实体类首次走数据流改血时打印一次分析结构诊断
    private static final Set<String> FIRST_WRITE_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> FIRST_EXTERNAL_WRITE_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> RUNTIME_BUDGET_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> ASSOCIATED_SUCCESS_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> ASSOCIATED_FAILURE_DUMPED = ConcurrentHashMap.newKeySet();
    private static final int MAX_ASSOCIATED_CANDIDATES_PER_SOURCE = 8;
    private static final int MAX_ASSOCIATED_COMBINATIONS = 64;
    private static final int MAX_RUNTIME_SOURCES = 32;
    private static final int MAX_DIAGNOSTIC_SOURCES = 32;
    private static final int MAX_RUNTIME_EXPRESSION_NODES = 50_000;
    private static final long RUNTIME_WRITE_BUDGET_NANOS = 100_000_000L;

    /* 数据流改血主入口：拿已分析的可写树把目标血量写进目标真实存储，verify 通过返回 true。
       REAL_HEALTH 与 NOT_REAL_HEALTH(带可写源)由本入口处理；无源 NOT_REAL_HEALTH/UNRESOLVED 在表层就被拦掉。 */
    public static boolean write(AnalysisResult tree, LivingEntity entity, float target) {
        if (tree == null || entity == null) return false;
        Class<?> cls = entity.getClass();
        boolean firstWrite = FIRST_WRITE_DUMPED.add(cls.getName());
        if (firstWrite) dumpAnalysisStructure(cls, tree, target);
        return writeViaSources(cls, tree, entity, target, firstWrite,
                (verifiedEntity, verifiedTarget, sink) -> EcaSetHealthManager.verify(verifiedEntity, verifiedTarget),
                "dataflow");
    }

    /* 外部扫描写入：与 write 同骨架，但用外部专用校验器(带死亡语义：目标≤0 需实体确实死亡)。
       供 isAlive/isDeadOrDying/hurt/actuallyHurt 逆推出的可写结构落地。 */
    public static boolean writeExternal(AnalysisResult tree, LivingEntity entity, float target) {
        if (tree == null || entity == null) return false;
        Class<?> cls = entity.getClass();
        boolean firstWrite = FIRST_EXTERNAL_WRITE_DUMPED.add(cls.getName());
        if (firstWrite) dumpExternalAnalysisStructure(cls, tree, target);
        // 只写与权威有依赖的源：实体外的常量写入源(阶段标记等)写入后回读必匹配，会抢先假成功
        AnalysisResult filtered = HealthDataflowAnalyzer.AnalysisResult.withoutConstantOnlySources(tree);
        return writeViaSources(cls, filtered, entity, target, firstWrite,
                (verifiedEntity, verifiedTarget, sink) ->
                    // 自回读只能证明候选可写，不能证明它承载真实血量。
                    HealthDataflowAnalyzer.verifyExternalDataflow(tree.returnExpr, verifiedEntity, verifiedTarget, sink)
                        && EcaSetHealthManager.verifyExternalRaw(verifiedEntity, verifiedTarget),
                "external");
    }

    private static final Set<String> CO_WRITE_DUMPED = ConcurrentHashMap.newKeySet();

    /* 联写实体外状态：只使用由权威因果闭包裁出的维护分支。上游状态从写回权威的表达式反解，
       下游镜像则在 dataflow 已写好实体权威后求值，避免遍历整个 tick 的所有写入。 */
    public static boolean coWriteExternalAuthorities(MaintenancePlan plan, LivingEntity entity, float target) {
        if (plan == null || entity == null || plan.branches().isEmpty()) return false;
        EvalContext ctx = HealthDataflowAnalyzer.newContext(entity);
        boolean anyWritten = false;
        int attempted = 0;
        for (MaintenanceBranch branch : plan.branches()) {
            for (Source dependency : branch.transactionSources()) {
                if (dependency.equals(branch.authority())
                        || !HealthDataflowAnalyzer.isExternalStorageSource(dependency)) continue;
                if (++attempted > MAX_CAUSAL_EXTERNAL_WRITES) return anyWritten;
                Constraint downstream = downstreamConstraint(branch, dependency, ctx);
                Constraint constraint = downstream.constrained()
                        ? downstream
                        : upstreamConstraint(branch, dependency, target, ctx);
                if (!constraint.constrained() || constraint.conflict()
                        || !isAddressable(dependency, entity)) continue;
                Object requiredValue = constraint.value();
                Object snapshot = dependency.read(entity);
                if (!dispatchWrite(dependency, entity, requiredValue)) {
                    dispatchWrite(dependency, entity, snapshot);
                    continue;
                }
                if (readSinkMatchesValue(dependency, entity, requiredValue)) {
                    anyWritten = true;
                    if (CO_WRITE_DUMPED.add(entity.getClass().getName() + "|" + dependency.label)) {
                        EcaLogger.info("[ExternalScan] co-write authority entity={} sink={} solved={} target={}",
                                entity.getClass().getName(), dependency.label, requiredValue, target);
                    }
                } else {
                    dispatchWrite(dependency, entity, snapshot);
                }
            }
        }
        return anyWritten;
    }

    /* dataflow 已先写好实体权威，直接由权威派生的镜像代表当前事务；路径不敏感分析同时看到的
       反向高水位分支属于旧状态恢复，不得与镜像约束合并成假冲突。 */
    private static Constraint downstreamConstraint(MaintenanceBranch branch, Source dependency,
                                                   EvalContext context) {
        Constraint result = Constraint.NONE;
        for (StoreWrite maintenance : branch.maintenanceWrites()) {
            if (!maintenance.sink().equals(dependency)
                    || !HealthDataflowAnalyzer.containsSink(maintenance.valueExpr(), branch.authority())) continue;
            Object candidate = evaluateMaintenanceValue(maintenance.valueExpr(), context);
            result = result.merge(candidate);
            if (result.conflict()) return result;
        }
        return result;
    }

    private static Constraint upstreamConstraint(MaintenanceBranch branch, Source dependency, float target,
                                                 EvalContext context) {
        Constraint result = Constraint.NONE;
        for (StoreWrite maintenance : branch.maintenanceWrites()) {
            if (!maintenance.sink().equals(branch.authority())
                    || !HealthDataflowAnalyzer.containsSink(maintenance.valueExpr(), dependency)) continue;
            HealthSolveResult solved = HealthDataflowAnalyzer.buildWritePath(
                    maintenance.valueExpr(), dependency, Float.valueOf(target), context);
            if (!solved.solved()) continue;
            result = result.merge(solved.value());
            if (result.conflict()) return result;
        }
        return result;
    }

    private static Object evaluateMaintenanceValue(Expr expression, EvalContext context) {
        try {
            return HealthDataflowAnalyzer.evaluate(expression, context);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private static final int MAX_CAUSAL_EXTERNAL_WRITES = 16;

    private record Constraint(Object value, boolean constrained, boolean conflict) {
        private static final Constraint NONE = new Constraint(null, false, false);

        private Constraint merge(Object candidate) {
            if (candidate == null || conflict) return this;
            if (!constrained) return new Constraint(candidate, true, false);
            return equivalentValue(value, candidate)
                    ? this
                    : new Constraint(value, true, true);
        }
    }

    private static boolean equivalentValue(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            double scale = Math.max(1.0d,
                    Math.max(Math.abs(leftNumber.doubleValue()), Math.abs(rightNumber.doubleValue())));
            return Math.abs(leftNumber.doubleValue() - rightNumber.doubleValue()) <= scale * 1.0e-6d;
        }
        return Objects.equals(left, right);
    }

    private static boolean readSinkMatchesValue(Source sink, LivingEntity entity, Object expected) {
        try {
            Object actual = HealthDataflowAnalyzer.evaluate(sink, HealthDataflowAnalyzer.newContext(entity));
            return equivalentValue(actual, expected);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    /* 有效血量写入：对模型的有效血量表达式求逆，得到应写入存储的值；反向累加存储会得到上限减目标值，
       写入后用同一表达式复核。校验不经过 getHealth，故解耦实体上的正确写入不会再被误判回滚。 */
    public static boolean writeEffective(HealthDataflowAnalyzer.EffectiveHealthModel model,
                                         LivingEntity entity, float target) {
        if (model == null || entity == null) return false;
        Class<?> cls = entity.getClass();
        EvalContext ctx = HealthDataflowAnalyzer.newContext(entity);
        HealthSolveResult solved = HealthDataflowAnalyzer.buildWritePath(
                model.readExpr(), model.storage(), Float.valueOf(target), ctx);
        if (!solved.solved() || solved.value() == null) {
            if (EFFECTIVE_DUMPED.add(cls.getName())) {
                EcaLogger.info("[EffectiveHealth] solve=FAIL entity={} storage={} target={} {} ({})",
                        cls.getName(), model.storage().label, target, solved.failure(), solved.detail());
            }
            return false;
        }

        Object snapshot = model.storage().read(entity);
        if (!dispatchWrite(model.storage(), entity, solved.value())) {
            dispatchWrite(model.storage(), entity, snapshot);
            if (EFFECTIVE_DUMPED.add(cls.getName())) {
                EcaLogger.info("[EffectiveHealth] write=FAIL entity={} storage={} solved={}",
                        cls.getName(), model.storage().label, solved.value());
            }
            return false;
        }
        /* 校验只用模型自身的表达式，选错存储时恒真。但生死判定同样可能是诱饵(getHealth 恒等
           maxHealth、死亡改在 tick 里判)，拿它交叉验证会误杀正确模型。
           模型是否可信改由 applyEffectiveHealth 的结构判据在建模阶段裁决。 */
        if (EcaSetHealthManager.verify(entity, target)) {
            EcaSetHealthManager.recordObservedWrite(cls);
            if (EFFECTIVE_SUCCESS_DUMPED.add(cls.getName())) {
                EcaLogger.info("[EffectiveHealth] success entity={} storage={} solved={} target={}",
                        cls.getName(), model.storage().label, solved.value(), target);
            }
            return true;
        }

        boolean restored = dispatchWrite(model.storage(), entity, snapshot);
        if (EFFECTIVE_DUMPED.add(cls.getName())) {
            EcaLogger.info("[EffectiveHealth] verify=FAIL entity={} storage={} solved={} target={} anchor={} restore={}",
                    cls.getName(), model.storage().label, solved.value(), target,
                    EcaSetHealthManager.readHealthAnchor(entity), restored ? "OK" : "FAIL");
        }
        return false;
    }

    private static final Set<String> EFFECTIVE_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> EFFECTIVE_SUCCESS_DUMPED = ConcurrentHashMap.newKeySet();

    /* Runtime-revealed writers commonly maintain ciphertext, keys and integrity tags as one unit.
       Their write set must be committed as a group; probing individual stores creates invalid states. */
    public static boolean writeAssociated(AnalysisResult tree, LivingEntity entity, float target) {
        if (tree == null || entity == null || tree.sources.size() < 2) return false;
        EvalContext context = HealthDataflowAnalyzer.newContext(entity);
        List<AssociatedSourceCandidates> groups = new ArrayList<>();
        for (Source sink : tree.sources) {
            List<Object> candidates = HealthDataflowAnalyzer.buildWriteCandidates(
                    tree.returnExpr, sink, Float.valueOf(target), context,
                    MAX_ASSOCIATED_CANDIDATES_PER_SOURCE);
            if (candidates.isEmpty()) return false;
            groups.add(new AssociatedSourceCandidates(sink, sink.read(entity), candidates));
        }

        AssociatedSearch search = new AssociatedSearch();
        boolean verified = tryAssociatedCombinations(groups, 0, new ArrayList<>(), entity, target, search);
        if (verified && search.last != null) {
            if (ASSOCIATED_SUCCESS_DUMPED.add(entity.getClass().getName())) {
                EcaLogger.info("[AssociatedWriter] success entity={} sources={} expected={} attempts={}",
                        entity.getClass().getName(), groups.size(), target, search.attempts);
                dumpAssociatedStates(search.last.states());
            }
            return true;
        }

        if (ASSOCIATED_FAILURE_DUMPED.add(entity.getClass().getName())) {
            boolean wroteAll = search.last != null && search.last.wroteAll();
            boolean restored = search.last != null && search.last.restored();
            EcaLogger.info("[AssociatedWriter] failed entity={} sources={} attempts={} wroteAll={} verified=false restore={}",
                    entity.getClass().getName(), groups.size(), search.attempts, wroteAll, restored);
            if (search.last != null) dumpAssociatedStates(search.last.states());
        }
        return false;
    }

    private static boolean tryAssociatedCombinations(List<AssociatedSourceCandidates> groups, int depth,
                                                     List<PreparedSourceWrite> selected,
                                                     LivingEntity entity, float target,
                                                     AssociatedSearch search) {
        if (search.attempts >= MAX_ASSOCIATED_COMBINATIONS) return false;
        if (depth == groups.size()) {
            search.attempts++;
            search.last = attemptAssociatedTransaction(selected, entity, target);
            return search.last.verified();
        }
        AssociatedSourceCandidates group = groups.get(depth);
        for (Object candidate : group.values()) {
            selected.add(new PreparedSourceWrite(group.sink(), group.snapshot(), candidate));
            if (tryAssociatedCombinations(groups, depth + 1, selected, entity, target, search)) return true;
            selected.remove(selected.size() - 1);
            if (search.attempts >= MAX_ASSOCIATED_COMBINATIONS) return false;
        }
        return false;
    }

    private static AssociatedAttempt attemptAssociatedTransaction(List<PreparedSourceWrite> selected,
                                                                  LivingEntity entity, float target) {
        List<PreparedSourceWrite> writes = List.copyOf(selected);
        float anchorBefore = EcaSetHealthManager.readHealthAnchor(entity);
        boolean wroteAll = true;
        for (PreparedSourceWrite write : writes) {
            if (!dispatchWrite(write.sink(), entity, write.value())) {
                wroteAll = false;
                break;
            }
        }
        List<Object> afterWrite = new ArrayList<>(writes.size());
        for (PreparedSourceWrite write : writes) afterWrite.add(write.sink().read(entity));
        if (wroteAll) EcaSetHealthManager.noteAnchorResponse(entity, anchorBefore, target);
        boolean verified = wroteAll && EcaSetHealthManager.verify(entity, target);
        List<AssociatedWriteState> states = new ArrayList<>(writes.size());
        for (int i = 0; i < writes.size(); i++) {
            PreparedSourceWrite write = writes.get(i);
            states.add(new AssociatedWriteState(write, afterWrite.get(i), write.sink().read(entity)));
        }
        if (verified) {
            EcaSetHealthManager.recordObservedWrite(entity.getClass());
            return new AssociatedAttempt(true, true, true, states);
        }
        // 关联写入全部成功但校验失败时，记录观测锚点与存储可能解耦
        if (wroteAll) EcaSetHealthManager.recordUnobservedWrite(entity.getClass(), null, "associated-sources");

        boolean restored = true;
        for (int i = writes.size() - 1; i >= 0; i--) {
            PreparedSourceWrite write = writes.get(i);
            if (!dispatchWrite(write.sink(), entity, write.snapshot())) restored = false;
        }
        return new AssociatedAttempt(false, wroteAll, restored, states);
    }

    private static void dumpAssociatedStates(List<AssociatedWriteState> states) {
        for (AssociatedWriteState state : states) {
            PreparedSourceWrite write = state.write();
            EcaLogger.info("[AssociatedWriter]   source={} before={} solved={} afterWrite={} afterVerify={}",
                    write.sink().label, write.snapshot(), write.value(),
                    state.afterWrite(), state.afterVerify());
        }
    }

    /* 首次诊断：打印目标实体类的可写树结构(kind/definingClass/sources 列表)，便于排查不同实体的改血行为 */
    private static void dumpAnalysisStructure(Class<?> cls, AnalysisResult tree, float target) {
        EcaLogger.info("[HealthDataflow] ===== first dataflow write: {} =====", cls.getName());
        EcaLogger.info("[HealthDataflow]   target={} kind={} definingClass={} sources={}",
                target, tree.classify(),
                tree.definingClass != null ? tree.definingClass.getName() : "null",
                tree.sources.size());
        EcaLogger.info("[HealthDataflow]   returnExpr={}", expressionSummary(tree.returnExpr));
        int i = 0;
        for (Source s : tree.sources.subList(0, Math.min(tree.sources.size(), MAX_DIAGNOSTIC_SOURCES))) {
            EcaLogger.info("[HealthDataflow]   sink#{} {} type={} class={}",
                    i++, s.label, s.valueType.getName(), s.getClass().getSimpleName());
        }
        if (tree.sources.size() > MAX_DIAGNOSTIC_SOURCES) {
            EcaLogger.info("[HealthDataflow]   ... {} additional sinks omitted",
                    tree.sources.size() - MAX_DIAGNOSTIC_SOURCES);
        }
    }

    private static void dumpExternalAnalysisStructure(Class<?> cls, AnalysisResult tree, float target) {
        EcaLogger.info("[ExternalScan] ===== first external write: {} =====", cls.getName());
        EcaLogger.info("[ExternalScan]   target={} definingClass={} sources={}", target,
                tree.definingClass != null ? tree.definingClass.getName() : "null", tree.sources.size());
        EcaLogger.info("[ExternalScan]   returnExpr={}", expressionSummary(tree.returnExpr));
        int index = 0;
        for (Source source : tree.sources.subList(0, Math.min(tree.sources.size(), MAX_DIAGNOSTIC_SOURCES))) {
            EcaLogger.info("[ExternalScan]   sink#{} {} type={} class={}",
                    index++, source.label, source.valueType.getName(), source.getClass().getSimpleName());
        }
        if (tree.sources.size() > MAX_DIAGNOSTIC_SOURCES) {
            EcaLogger.info("[ExternalScan]   ... {} additional sinks omitted",
                    tree.sources.size() - MAX_DIAGNOSTIC_SOURCES);
        }
    }

    /* ==================== 写入编排 ==================== */

    @FunctionalInterface
    public interface HealthVerifier {
        boolean verify(LivingEntity entity, float expected, Source sink);
    }

    private record PreparedSourceWrite(Source sink, Object snapshot, Object value) {}

    private record AssociatedWriteState(PreparedSourceWrite write, Object afterWrite, Object afterVerify) {}

    private record AssociatedSourceCandidates(Source sink, Object snapshot, List<Object> values) {}

    private record AssociatedAttempt(boolean verified, boolean wroteAll, boolean restored,
                                     List<AssociatedWriteState> states) {}

    private static final class AssociatedSearch {
        private int attempts;
        private AssociatedAttempt last;
    }

    private static final Set<String> FAIL_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> ADDRESS_DIAG = ConcurrentHashMap.newKeySet();

    /* 逐个验证候选 Source，单点未命中再联合写入(应对双源防御)，失败回滚原值。
       仅在缓存失败树时打印一次诊断，避免每-tick 改血刷屏。 */
    public static boolean writeViaSources(Class<?> cls, AnalysisResult ar, LivingEntity entity, float expected,
                                          boolean logSuccess, HealthVerifier verifier, String diagnosticChannel) {
        if (!isRuntimeSafeExpression(ar.returnExpr)) {
            dumpRuntimeBudget(cls, diagnosticChannel, "expression node budget exceeded");
            return false;
        }
        EvalContext ctx = HealthDataflowAnalyzer.newContext(entity);
        List<String> diag = new ArrayList<>();
        List<PreparedSourceWrite> solvedWrites = new ArrayList<>();
        long deadline = System.nanoTime() + RUNTIME_WRITE_BUDGET_NANOS;
        int examined = 0;
        boolean candidateScanComplete = true;
        for (Source sink : withoutEcaOwnedSources(ar.sources)) {
            if (++examined > MAX_RUNTIME_SOURCES) {
                dumpRuntimeBudget(cls, diagnosticChannel, "source cap exceeded");
                candidateScanComplete = false;
                break;
            }
            if (System.nanoTime() > deadline) {
                dumpRuntimeBudget(cls, diagnosticChannel, "runtime write budget exceeded");
                candidateScanComplete = false;
                break;
            }
            if (!isAddressable(sink, entity)) {
                diag.add("    [" + sink.label + "] skipped=NOT_ADDRESSABLE"
                        + " (branch does not exist on this entity)");
                continue;
            }
            HealthSolveResult solved = HealthDataflowAnalyzer.buildWritePath(ar.returnExpr, sink, Float.valueOf(expected), ctx);
            if (!solved.solved() || solved.value() == null) {
                diag.add("    [" + sink.label + "] solve=FAIL " + solved.failure() + " (" + solved.detail() + ")");
                continue;
            }

            Object snapshot = sink.read(entity);
            solvedWrites.add(new PreparedSourceWrite(sink, snapshot, solved.value()));
            float anchorBefore = EcaSetHealthManager.readHealthAnchor(entity);
            if (!dispatchWrite(sink, entity, solved.value())) {
                boolean restored = dispatchWrite(sink, entity, snapshot);
                diag.add("    [" + sink.label + "] solved=" + solved.value()
                        + " write=FAIL restore=" + (restored ? "OK" : "FAIL"));
                continue;
            }
            // 锚点若随本次写入位移到目标值，即为它反映真实存储的证据，据此补正弱取证的误判
            EcaSetHealthManager.noteAnchorResponse(entity, anchorBefore, expected);
            if (verifier.verify(entity, expected, sink)) {
                EcaSetHealthManager.recordObservedWrite(cls);
                if (logSuccess) {
                    EcaLogger.info("[HealthDataflow] setHealth success entity={} sink={} solved={} expected={}",
                            cls.getName(), sink.label, solved.value(), expected);
                }
                return true;
            }

            boolean restored = dispatchWrite(sink, entity, snapshot);
            // 写入成功但校验失败时，单独记录观测锚点与存储可能解耦
            EcaSetHealthManager.recordUnobservedWrite(cls, sink, sink.label);
            diag.add("    [" + sink.label + "] solved=" + solved.value()
                    + " verify=FAIL restore=" + (restored ? "OK" : "FAIL"));
        }

        if (System.nanoTime() > deadline) {
            dumpRuntimeBudget(cls, diagnosticChannel, "runtime write budget exceeded");
            candidateScanComplete = false;
        }
        if (candidateScanComplete
                && writeAllSources(solvedWrites, entity, expected, diag, verifier, logSuccess)) return true;

        if (FAIL_DUMPED.add(cls.getName() + "|" + diagnosticChannel)) {
            EcaLogger.info("[{}] setHealth failed entity={} expected={} sink results:",
                    diagnosticChannel, cls.getName(), expected);
            for (String line : diag) EcaLogger.info("[{}] {}", diagnosticChannel, line);
        }
        return false;
    }

    private static void dumpRuntimeBudget(Class<?> cls, String channel, String reason) {
        String className = cls == null ? "null" : cls.getName();
        if (RUNTIME_BUDGET_DUMPED.add(className + "|" + channel + "|" + reason)) {
            EcaLogger.info("[HealthDataflow] runtime candidate scan stopped entity={} channel={} reason={}",
                    className, channel, reason);
        }
    }

    /* 诊断摘要直接遍历节点并受硬预算约束，不能先调用 Expr.toString() 再截断。 */
    static String expressionSummary(Expr root) {
        if (root == null) return "null";
        ExpressionStats stats = inspectExpression(root, 2_048);
        String type = root.getClass().getSimpleName();
        return "type=" + type + " nodes=" + stats.nodes()
                + (stats.truncated() ? "+" : "")
                + " sources=" + stats.sourceLabels();
    }

    private static boolean isRuntimeSafeExpression(Expr root) {
        return root != null && !inspectExpression(root, MAX_RUNTIME_EXPRESSION_NODES).truncated();
    }

    private static ExpressionStats inspectExpression(Expr root, int nodeLimit) {
        Set<Expr> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> sourceLabels = new LinkedHashSet<>();
        ArrayDeque<Expr> pending = new ArrayDeque<>();
        pending.add(root);
        boolean truncated = false;
        while (!pending.isEmpty()) {
            Expr current = pending.removeLast();
            if (current == null || !visited.add(current)) continue;
            if (visited.size() > nodeLimit) {
                truncated = true;
                break;
            }
            if (current instanceof Source source) {
                if (sourceLabels.size() < 8) sourceLabels.add(source.label);
            } else if (current instanceof Choice choice) {
                pending.addAll(choice.alternatives());
            } else if (current instanceof Op op) {
                pending.addAll(op.args());
            } else if (current instanceof Call call) {
                pending.addAll(call.args());
            } else if (current instanceof Closure closure) {
                pending.addAll(closure.captured());
            } else if (current instanceof StoreWrite write) {
                pending.add(write.sink());
                pending.add(write.valueExpr());
            } else if (current instanceof OptionalContentExpr optional) {
                pending.add(optional.optionalExpr());
            }
        }
        return new ExpressionStats(Math.min(visited.size(), nodeLimit), List.copyOf(sourceLabels), truncated);
    }

    private record ExpressionStats(int nodes, List<String> sourceLabels, boolean truncated) {}

    /* ≥2 个可解 Source 时联合写入(应对需同时写多处的双源防御)，失败逆序回滚。
       writes 由单源循环收集，其 snapshot 均为原值(循环对每次尝试都已回滚)，故回滚即复原。 */
    private static boolean writeAllSources(List<PreparedSourceWrite> writes, LivingEntity entity, float expected,
                                           List<String> diag, HealthVerifier verifier, boolean logSuccess) {
        if (writes.size() < 2) return false;

        float anchorBefore = EcaSetHealthManager.readHealthAnchor(entity);
        boolean wroteAll = true;
        for (PreparedSourceWrite write : writes) {
            if (!dispatchWrite(write.sink(), entity, write.value())) {
                wroteAll = false;
                break;
            }
        }
        // 单源逐个写时锚点不动、多源同时写才生效的存储，取证只能在联合写之后进行
        if (wroteAll) EcaSetHealthManager.noteAnchorResponse(entity, anchorBefore, expected);
        if (wroteAll && verifier.verify(entity, expected, null)) {
            EcaSetHealthManager.recordObservedWrite(entity.getClass());
            if (logSuccess) {
                EcaLogger.info("[HealthDataflow] setHealth success entity={} sink=all-sources expected={}",
                        entity.getClass().getName(), expected);
            }
            return true;
        }
        // 全源写入成功但校验失败时，记录观测锚点与存储可能解耦
        if (wroteAll) EcaSetHealthManager.recordUnobservedWrite(entity.getClass(), null, "all-sources");

        boolean restoredAll = true;
        for (int i = writes.size() - 1; i >= 0; i--) {
            PreparedSourceWrite write = writes.get(i);
            if (!dispatchWrite(write.sink(), entity, write.snapshot())) restoredAll = false;
        }
        diag.add("    [all sources] write=" + (wroteAll ? "OK" : "FAIL")
                + " verify=FAIL restore=" + (restoredAll ? "OK" : "FAIL"));
        return false;
    }

    /* 多态 getHealth 的分支可能来自其他模组对 LivingEntity 的注入，对当前实体运行期根本不会走到，
       其接收者也不存在。此类分支不属于本实体的权威，必须在组织事务前排除。 */
    public static boolean isAddressable(Source sink, LivingEntity entity) {
        try {
            EvalContext context = HealthDataflowAnalyzer.newContext(entity);
            if (sink instanceof ChainedFieldSource s) {
                Object cur = HealthDataflowAnalyzer.evaluate(s.root, context);
                if (cur == null) {
                    if (ADDRESS_DIAG.add(entity.getClass().getName() + "|" + s.label)) {
                        EcaLogger.info("[HealthDataflow] isAddressable root=null entity={} sink={} level={} rootExpr={}",
                                entity.getClass().getName(), s.label,
                                entity.level() != null ? entity.level().getClass().getName() : "null",
                                expressionSummary(s.root));
                    }
                    return false;
                }
                for (int i = 0; i < s.chain.size() - 1; i++) {
                    cur = readField(cur, s.chain.get(i));
                    if (cur == null) return false;
                }
                return true;
            }
            if (sink instanceof CapabilityDataSource s) {
                return HealthDataflowAnalyzer.evaluate(s.containerExpr, context) != null;
            }
            if (sink instanceof MapEntrySource s) {
                return HealthDataflowAnalyzer.evaluate(s.containerExpr, context) != null;
            }
            if (sink instanceof ArrayElementSource s) {
                return HealthDataflowAnalyzer.evaluate(s.arrayExpr, context) != null;
            }
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    /* 过滤 ECA 自有源：分析阶段剥离不干净时，写入阶段兜底排除。 */
    private static List<Source> withoutEcaOwnedSources(List<Source> sources) {
        List<Source> filtered = new ArrayList<>(sources.size());
        for (Source source : sources) {
            if (isEcaOwnedSource(source)) continue;
            filtered.add(source);
        }
        return filtered;
    }

    private static boolean isEcaOwnedSource(Source source) {
        for (String label : EcaOwnedState.staticFieldLabels()) {
            if (source.label.equals(label)) return true;
        }
        for (String key : EcaOwnedState.nbtKeys()) {
            if (source.label.contains(key)) return true;
        }
        return false;
    }

    /* ==================== Source 写入分发(按子类形态) ==================== */

    /* 按 Source 子类形态选择写入实现。新增 Source 子类时必须在此扩充分发，否则写入将默默失败。
       写入成功后沿 sink 的 receiver 表达式传播 SavedData 置脏：实体外的真实血量(SavedData 字段)
       必须 setDirty() 才会落盘，否则写入只改内存、存档值不变。 */
    public static boolean dispatchWrite(Source sink, LivingEntity entity, Object value) {
        boolean wrote;
        if (sink instanceof FieldChainSource s) wrote = writeFieldChain(s, entity, value);
        else if (sink instanceof StaticFieldSource s) wrote = writeStaticField(s, value);
        else if (sink instanceof ChainedFieldSource s) wrote = writeChainedField(s, entity, value);
        else if (sink instanceof CapabilityDataSource s) wrote = writeCapability(s, entity, value);
        else if (sink instanceof SynchedDataSource s) wrote = writeSynchedData(s, entity, value);
        else if (sink instanceof MapEntrySource s) wrote = writeMapEntry(s, entity, value);
        else if (sink instanceof ArrayElementSource s) wrote = writeArrayElement(s, entity, value);
        else if (sink instanceof MethodCallSource s) wrote = writeMethodCall(s, entity, value);
        else if (sink instanceof ConstOverrideSource s) wrote = writeConstOverride(s, entity, value);
        else return false;
        if (wrote) markSavedDataDirty(sink, entity);
        return wrote;
    }

    /* 常数覆写写入：求出该常数点的 holder(① 实体本体 ② 实体的 health manager)，
       把目标血量登记进 ConstOverride；patched 字节码的 resolveHealth(this,...) 据此返回覆写值。 */
    private static boolean writeConstOverride(ConstOverrideSource s, LivingEntity entity, Object value) {
        Object holder = s.holder(entity);
        if (holder == null) return false;
        if (value instanceof Number n) {
            ConstOverride.setOverride(holder, n.floatValue());
            return true;
        }
                // 快照为 null 时清除写入期间设置的覆写，恢复写入前状态
        // 失败后必须清除常数覆写，避免后续 getHealth 继续读取临时值
        ConstOverride.removeOverride(holder);
        return true;
    }

    private static boolean writeFieldChain(FieldChainSource s, LivingEntity entity, Object value) {
        VarHandle[] handles = s.handles;
        List<FieldStep> chain = s.chain;
        int n = handles.length;
        FieldStep last = chain.get(n - 1);
        Object coerced;
        try {
            coerced = HealthDataflowAnalyzer.coerceForType(value, s.valueType);
            if (coerced == null && s.valueType.isPrimitive()) return false;
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }

        // record component 不可经 VarHandle/Unsafe 写：改为重建 record 写回上一级字段
        Class<?> leafOwner = HealthDataflowAnalyzer.loadClass(last.ownerInternal());
        if (leafOwner != null && leafOwner.isRecord() && n >= 2) {
            try {
                Object holder = entity;
                for (int i = 0; i < n - 2; i++) {
                    holder = handles[i].get(holder);
                    if (holder == null) return false;
                }
                Object recordObj = handles[n - 2].get(holder);
                Object rebuilt = rebuildRecord(leafOwner, recordObj, last.name(), coerced);
                if (rebuilt == null) return false;
                handles[n - 2].set(holder, rebuilt);
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
        }

        Object container;
        try {
            Object cur = entity;
            for (int i = 0; i < n - 1; i++) {
                cur = handles[i].get(cur);
                if (cur == null) return false;
            }
            container = cur;
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }

        // 普通字段优先使用 VarHandle，final 字段写入失败时再尝试 Unsafe
        try {
            handles[n - 1].set(container, coerced);
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            Class<?> owner = HealthDataflowAnalyzer.loadClass(last.ownerInternal());
            if (owner == null) return false;
            Field f = HealthDataflowAnalyzer.findFieldInHierarchy(owner, last.name());
            if (f == null) return false;
            f.setAccessible(true);
            return UnsafeUtil.unsafePutField(container, f, coerced);
        }
    }

    private static boolean writeStaticField(StaticFieldSource s, Object value) {
        try {
            s.field.set(null, HealthDataflowAnalyzer.coerceForType(value, s.valueType));
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    private static boolean writeChainedField(ChainedFieldSource s, LivingEntity entity, Object value) {
        try {
            Object cur = HealthDataflowAnalyzer.evaluate(s.root, HealthDataflowAnalyzer.newContext(entity));
            if (cur == null) return false;
            for (int i = 0; i < s.chain.size() - 1; i++) {
                cur = readField(cur, s.chain.get(i));
                if (cur == null) return false;
            }
            return writeFieldStep(cur, s.chain.get(s.chain.size() - 1), value);
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
    }

    /* 沿 sink 的 receiver 表达式树传播 SavedData 置脏：ChainedFieldSource.root 是
       WorldVariables.get(world) 这类调用，求值得到 SavedData 实例；字段写入只改内存，
       必须 setDirty() 才会由存档系统落盘。对表达式树上所有能求值为 SavedData 的节点置脏。 */
    private static void markSavedDataDirty(Expr containerExpression, LivingEntity entity) {
        Set<Expr> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        markSavedDataDirty(containerExpression, entity, visited);
    }

    private static void markSavedDataDirty(Expr expression, LivingEntity entity, Set<Expr> visited) {
        if (expression == null || !visited.add(expression)) return;
        try {
            if (expression instanceof HealthDataflowAnalyzer.Call || expression instanceof HealthDataflowAnalyzer.Reference) {
                Object value = HealthDataflowAnalyzer.evaluate(expression, HealthDataflowAnalyzer.newContext(entity));
                if (value instanceof SavedData savedData) savedData.setDirty();
            }
            if (expression instanceof ChainedFieldSource chained) {
                markSavedDataDirty(chained.root, entity, visited);
            } else if (expression instanceof MapEntrySource mapEntry) {
                markSavedDataDirty(mapEntry.containerExpr, entity, visited);
                markSavedDataDirty(mapEntry.keyExpr, entity, visited);
            } else if (expression instanceof CapabilityDataSource capability) {
                markSavedDataDirty(capability.containerExpr, entity, visited);
                markSavedDataDirty(capability.keyExpr, entity, visited);
            } else if (expression instanceof ArrayElementSource arrayElement) {
                markSavedDataDirty(arrayElement.arrayExpr, entity, visited);
                markSavedDataDirty(arrayElement.indexExpr, entity, visited);
            } else if (expression instanceof HealthDataflowAnalyzer.Call call) {
                for (Expr argument : call.args()) markSavedDataDirty(argument, entity, visited);
            } else if (expression instanceof HealthDataflowAnalyzer.Op operation) {
                for (Expr argument : operation.args()) markSavedDataDirty(argument, entity, visited);
            } else if (expression instanceof Choice choice) {
                for (Expr alternative : choice.alternatives()) {
                    markSavedDataDirty(alternative, entity, visited);
                }
            } else if (expression instanceof HealthDataflowAnalyzer.Closure closure) {
                for (Expr argument : closure.captured()) markSavedDataDirty(argument, entity, visited);
            } else if (expression instanceof HealthDataflowAnalyzer.OptionalContentExpr optional) {
                markSavedDataDirty(optional.optionalExpr(), entity, visited);
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.info("[HealthDataflow] SavedData dirty propagation failed entity={} msg={}",
                    entity.getClass().getName(), t.getMessage());
        }
    }

    private static boolean writeCapability(CapabilityDataSource s, LivingEntity entity, Object value) {
        try {
            EvalContext ctx = HealthDataflowAnalyzer.newContext(entity);
            Object container = HealthDataflowAnalyzer.evaluate(s.containerExpr, ctx);
            Object key = HealthDataflowAnalyzer.evaluate(s.keyExpr, ctx);
            if (container == null || key == null) return false;
            if (s.chain.isEmpty()) return writeCapabilitySlot(container, key, value);

            Object slot = readCapabilitySlot(container, key);
            if (slot == null) return false;
            Object cur = slot;
            for (int i = 0; i < s.chain.size() - 1; i++) {
                cur = readField(cur, s.chain.get(i));
                if (cur == null) return false;
            }
            FieldStep leaf = s.chain.get(s.chain.size() - 1);
            if (!writeFieldViaSetter(cur, leaf, value) && !writeFieldStep(cur, leaf, value)) return false;
            writeCapabilitySlot(container, key, slot);
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    private static Object readCapabilitySlot(Object container, Object key) {
        Object value = HealthDataflowAnalyzer.invokeCompatibleSafely(container, "getValue", key);
        return value == HealthDataflowAnalyzer.INVOKE_FAILED ? null : value;
    }

    private static boolean writeCapabilitySlot(Object container, Object key, Object value) {
        return HealthDataflowAnalyzer.invokeCompatibleSafely(container, "setValue", key, value) != HealthDataflowAnalyzer.INVOKE_FAILED;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean writeSynchedData(SynchedDataSource s, LivingEntity entity, Object value) {
        try {
            SynchedEntityData ed = entity.getEntityData();
            Int2ObjectMap<?> map = (Int2ObjectMap<?>) ed.itemsById;
            SynchedEntityData.DataItem item = (SynchedEntityData.DataItem) map.get(s.accessor.getId());
            if (item == null) return false;
            Object coerced = HealthDataflowAnalyzer.coerceSameType(item.value, value);
            if (coerced == null) return false;
            item.value = coerced;
            item.dirty = true;
            ed.isDirty = true;
            entity.onSyncedDataUpdated(s.accessor);
            return true;
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
    }

    private static boolean writeMapEntry(MapEntrySource s, LivingEntity entity, Object value) {
        boolean any = false;
        Set<Object> writtenMaps = Collections.newSetFromMap(new IdentityHashMap<>());

        try {
            Object obj = HealthDataflowAnalyzer.evaluate(s.containerExpr, HealthDataflowAnalyzer.newContext(entity));
            if (obj instanceof Map<?, ?> map && writtenMaps.add(map)) {
                Object key = matchKey(map, entity, s.keyKind);
                if (key != null && unsafeModifyMapEntry(map, key, value)) any = true;
            }
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }

    // 同时更新 owner 类及其嵌套类的静态 Map，保持相关记录表一致
        if (s.ownerClassInternal != null) {
            Class<?> ownerClass = HealthDataflowAnalyzer.loadClass(s.ownerClassInternal);
            if (ownerClass != null && writeSiblingMaps(ownerClass, entity, s, value, writtenMaps)) any = true;
        }
        return any;
    }

    /* 写入 cls 及其嵌套类中已经包含本实体键的静态 Map 字段，保持多表一致。
       仅改动 matchKey 命中(以本实体为键)的 Map，故对无关静态表安全；writtenMaps 身份集防重复写。 */
    private static boolean writeSiblingMaps(Class<?> cls, LivingEntity entity, MapEntrySource s, Object value, Set<Object> writtenMaps) {
        boolean any = false;
        for (Field f : cls.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!Map.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object obj = f.get(null);
                if (!(obj instanceof Map<?, ?> map)) continue;
                if (!writtenMaps.add(map)) continue;
                Object key = matchKey(map, entity, s.keyKind);
                if (key == null) continue;
                if (unsafeModifyMapEntry(map, key, value)) any = true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }
        }
        for (Class<?> nested : cls.getDeclaredClasses()) {
            if (writeSiblingMaps(nested, entity, s, value, writtenMaps)) any = true;
        }
        return any;
    }

    private static boolean writeArrayElement(ArrayElementSource s, LivingEntity entity, Object value) {
        try {
            EvalContext ctx = HealthDataflowAnalyzer.newContext(entity);
            Object arr = HealthDataflowAnalyzer.evaluate(s.arrayExpr, ctx);
            Object idx = HealthDataflowAnalyzer.evaluate(s.indexExpr, ctx);
            if (arr == null || !(idx instanceof Number n) || !(value instanceof Number v)) return false;
            int i = n.intValue();
            Class<?> ct = arr.getClass().getComponentType();
            if (ct == int.class) Array.setInt(arr, i, v.intValue());
            else if (ct == long.class) Array.setLong(arr, i, v.longValue());
            else if (ct == float.class) Array.setFloat(arr, i, v.floatValue());
            else if (ct == double.class) Array.setDouble(arr, i, v.doubleValue());
            else if (ct == short.class) Array.setShort(arr, i, v.shortValue());
            else if (ct == byte.class) Array.setByte(arr, i, v.byteValue());
            else Array.set(arr, i, value);
            return true;
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
    }

    private static boolean writeMethodCall(MethodCallSource s, LivingEntity entity, Object value) {
        try {
            Class<?> owner = HealthDataflowAnalyzer.loadClass(s.ownerInternal);
            if (owner == null) return false;
            Type[] argTypes = Type.getArgumentTypes(s.desc);
            boolean isStatic = s.args.size() == argTypes.length;
            int start = isStatic ? 0 : 1;
            Object receiver = null;
            EvalContext ctx = HealthDataflowAnalyzer.newContext(entity);
            if (!isStatic) {
                receiver = HealthDataflowAnalyzer.evaluate(s.args.get(0), ctx);
                if (receiver == null) return false;
            }
            Object[] values = new Object[argTypes.length];
            Class<?>[] paramTypes = new Class<?>[argTypes.length];
            for (int i = 0; i < argTypes.length; i++) {
                paramTypes[i] = HealthDataflowAnalyzer.asmTypeToClass(argTypes[i]);
                if (paramTypes[i] == null) return false;
                Object argValue = i == s.valueArgIndex ? value : HealthDataflowAnalyzer.evaluate(s.args.get(start + i), ctx);
                values[i] = HealthDataflowAnalyzer.coerceArgPublic(argValue, paramTypes[i]);
            }
            Method method = HealthDataflowAnalyzer.findMethod(isStatic ? owner : receiver.getClass(), s.name, paramTypes, values);
            if (method == null && !isStatic) method = HealthDataflowAnalyzer.findMethod(owner, s.name, paramTypes, values);
            if (method == null) return false;
            method.setAccessible(true);
            method.invoke(receiver, values);
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    /* ==================== Map 写入：兄弟表 + entrySet 遍历 + Unsafe ==================== */

    private static final Map<Class<?>, Long> ENTRY_VALUE_OFFSET_CACHE = new ConcurrentHashMap<>();

    private static Object matchKey(Map<?, ?> map, LivingEntity entity, MapEntrySource.KeyKind kind) {
        Object primary = switch (kind) {
            case ENTITY -> entity;
            case ENTITY_UUID -> entity.getUUID();
            case ENTITY_ID -> entity.getId();
            case UNKNOWN -> entity;
        };
        if (primary != null && map.containsKey(primary)) return primary;
        Object[] fb = {entity, entity.getUUID(), entity.getId()};
        for (Object k : fb) if (k != null && map.containsKey(k)) return k;
        return null;
    }

    /* 遍历 entrySet 写所有 key 匹配的 entry(WeakHashMap 多 entry 同 key 的坑),
     * 用 Entry.setValue 绕过 Map.put(常见 mixin 拦截点),失败走 Unsafe 写字段偏移
     */
    private static boolean unsafeModifyMapEntry(Map<?, ?> map, Object targetKey, Object newValue) {
        int written = 0;
        try {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object ek = entry.getKey();
                if (ek != targetKey && (targetKey == null || !targetKey.equals(ek))) continue;
                Object cur = entry.getValue();
                Object boxed = cur == null ? newValue : HealthDataflowAnalyzer.coerceSameType(cur, newValue);
                if (boxed == null) boxed = newValue;

                boolean wrote = false;
                try {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Map.Entry rawEntry = entry;
                    rawEntry.setValue(boxed);
                    if (boxed.equals(entry.getValue())) wrote = true;
                } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }

                if (!wrote) {
                    long offset = getEntryValueOffset(entry);
                    if (offset != -1) {
                        UnsafeUtil.lwjglPutObject(entry, offset, boxed);
                        if (boxed.equals(entry.getValue())) wrote = true;
                    }
                }
                if (wrote) written++;
            }
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }
        return written > 0;
    }

    private static long getEntryValueOffset(Object entry) {
        Class<?> ec = entry.getClass();
        Long cached = ENTRY_VALUE_OFFSET_CACHE.get(ec);
        if (cached != null) return cached;
        for (Class<?> cls = ec; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field f : cls.getDeclaredFields()) {
                String n = f.getName();
                if (n.equals("value") || n.equals("val")) {
                    long off = UnsafeUtil.lwjglObjectFieldOffset(f);
                    if (off != -1) {
                        ENTRY_VALUE_OFFSET_CACHE.put(ec, off);
                        return off;
                    }
                }
            }
        }
        return -1;
    }

    /* ==================== 字段写入辅助(供 ChainedField/Capability 用) ==================== */

    private static Object readField(Object target, FieldStep step) {
        if (target == null) return null;
        try {
            Field f = HealthDataflowAnalyzer.findFieldInHierarchy(target.getClass(), step.name());
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return null; }
    }

    private static boolean writeFieldViaSetter(Object target, FieldStep step, Object value) {
        if (target == null) return false;
        Class<?> fieldType = HealthDataflowAnalyzer.descriptorToClass(step.desc());
        if (fieldType == null) return false;
        String suffix = step.name().isEmpty()
                ? ""
                : Character.toUpperCase(step.name().charAt(0)) + step.name().substring(1);
        String[] names = suffix.isEmpty() ? new String[] {"set"} : new String[] {"set" + suffix, "setValue"};
        for (String name : names) {
            Method method = findSetter(target.getClass(), name, fieldType, value);
            if (method == null) continue;
            try {
                method.setAccessible(true);
                method.invoke(target, HealthDataflowAnalyzer.coerceArgPublic(value, method.getParameterTypes()[0]));
                return true;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
            }
        }
        return false;
    }

    private static Method findSetter(Class<?> owner, String name, Class<?> fieldType, Object value) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1) continue;
                if (HealthDataflowAnalyzer.methodArgMatches(params[0], fieldType, value)) return method;
            }
        }
        return null;
    }

    private static boolean writeFieldStep(Object target, FieldStep step, Object value) {
        Field f;
        Class<?> ft;
        try {
            Class<?> owner = HealthDataflowAnalyzer.loadClass(step.ownerInternal());
            if (owner == null) return false;
            f = HealthDataflowAnalyzer.findFieldInHierarchy(owner, step.name());
            if (f == null) return false;
            f.setAccessible(true);
            ft = f.getType();
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }

        try {
            if (ft == float.class) f.setFloat(target, ((Number) value).floatValue());
            else if (ft == double.class) f.setDouble(target, ((Number) value).doubleValue());
            else if (ft == int.class) f.setInt(target, ((Number) value).intValue());
            else if (ft == long.class) f.setLong(target, ((Number) value).longValue());
            else if (ft == short.class) f.setShort(target, ((Number) value).shortValue());
            else if (ft == byte.class) f.setByte(target, ((Number) value).byteValue());
            else f.set(target, HealthDataflowAnalyzer.coerceForType(value, ft));
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            // record 组件反射写必失败，JDK 又禁止对 record 取 Unsafe field offset(强写只刷 UnsupportedOperationException)；
            // record 重建仅由 FieldChain 路径处理，无法写回持有者的临时 record 不参与写入
            if (f.getDeclaringClass().isRecord()) return false;
            // final 字段或模块访问受限时尝试 Unsafe
            return UnsafeUtil.unsafePutField(target, f, value);
        }
    }

    /* ==================== record 重建(供 FieldChain 末段是 record component 时用) ==================== */

    private static Object rebuildRecord(Class<?> recordClass, Object current, String targetComponent, Object newValue) {
        try {
            RecordComponent[] comps = recordClass.getRecordComponents();
            Class<?>[] types = new Class<?>[comps.length];
            Object[] args = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) {
                types[i] = comps[i].getType();
                if (comps[i].getName().equals(targetComponent)) {
                    args[i] = HealthDataflowAnalyzer.coerceForType(newValue, types[i]);
                } else if (current != null) {
                    Method acc = comps[i].getAccessor();
                    acc.setAccessible(true);
                    args[i] = acc.invoke(current);
                } else {
                    args[i] = types[i].isPrimitive() ? HealthDataflowAnalyzer.coerceForType(0, types[i]) : null;
                }
            }
            Constructor<?> ctor = recordClass.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return null; }
    }

}
