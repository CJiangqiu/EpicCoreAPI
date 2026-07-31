package net.eca.util.health.internal;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.eca.coremod.JvmTiChannel;
import net.eca.coremod.RuntimeBytecodeProvider;
import net.eca.util.EcaLogger;
import net.eca.util.health.EcaOwnedState;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.AnalysisResult;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.ArrayElementSource;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.CapabilityDataSource;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.ChainedFieldSource;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.Call;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.Choice;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.ProtocolConstantOverrideSource;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.EvalContext;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.Expr;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.FieldChainSource;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.FieldStep;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.MapEntrySource;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.MethodCallSource;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.NbtValueSource;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.Op;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.Primitive;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.Reference;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.Source;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.StaticFieldSource;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.SynchedDataSource;
import net.eca.util.reflect.UnsafeUtil;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.nbt.CompoundTag;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 数据流改血落地层：吃 ProtocolDataflowAnalyzer 产出的可写树(AnalysisResult)，
 * 把求解结果写进目标真实存储并校验，失败回滚。
 * Source 的写入按具体类型分派到本类，Unsafe、反射和 retransform 操作也集中在此，
 * 使分析器保持只读且不依赖 ECA 运行期状态。
 */
public final class ProtocolDataFlowEngine {

    private ProtocolDataFlowEngine() {}

    private static final int MAX_EXPRESSION_LOG_CHARS = 4096;
    private static final int MAX_DIAGNOSTIC_LOG_CHARS = 1024;

    /* ==================== 启动注入：把分析器接入 ECA 运行期 ==================== */

    private static volatile boolean initialized = false;

    /* ECA 启动期由调用者调用一次：分析器读外部转换完成、ECA 注入前的快照，
       方法探针则读最终运行期字节码。 */
    public static void init() {
        if (initialized) return;
        synchronized (ProtocolDataFlowEngine.class) {
            if (initialized) return;
            ProtocolDataflowAnalyzer.setClassBytesProvider(ProtocolDataFlowEngine::classBytesForAnalysis);
            ProtocolDataflowAnalyzer.setOverrideLookup(ProtocolConstantOverride::getOverride);
            ProtocolMethodProbe.setClassBytesProvider(ProtocolDataFlowEngine::classBytesViaRuntime);
            // 锁血/最大血量锁的密文、密钥、校验位三者都参与 hook 取值，必须整组登记才能剥净
            ProtocolDataflowAnalyzer.setStripConfig(
                    EcaOwnedState.hookOwners(),
                    EcaOwnedState.staticFieldLabels(),
                    EcaOwnedState.nbtKeys());
            ProtocolDataflowAnalyzer.setOwnedSynchedDataIds(EcaOwnedState.ownedSynchedDataIds());
            initialized = true;
        }
    }

    /* 诊断去重：每类只报一次字节码来源 */
    private static final Set<String> BYTES_SOURCE_DUMPED = ConcurrentHashMap.newKeySet();

    /* 事务明细每类只报一次 */
    private static final Set<String> TRANSACTION_DETAIL_DUMPED = ConcurrentHashMap.newKeySet();

    /* 兜底闸门命中每个 sink 只报一次 */
    private static final Set<String> ECA_OWNED_WRITE_DUMPED = ConcurrentHashMap.newKeySet();

    /* RuntimeBytecodeProvider 优先(含 mixin/coremod 转换后)，缺失回退分析器内置默认实现 */
    private static byte[] classBytesViaRuntime(Class<?> clazz) {
        try {
            byte[] runtime = RuntimeBytecodeProvider.get(clazz);
            if (runtime != null) {
                if (!LifeProtocolManager.isWarmupDiagnosticsSuppressed() && BYTES_SOURCE_DUMPED.add(clazz.getName()))
                    EcaLogger.info("[HealthDataflow] bytes for {} <- RuntimeBytecodeProvider(runtime,{}B)", clazz.getName(), runtime.length);
                return runtime;
            }
        } catch (Throwable ignored) {
            if (ignored instanceof VirtualMachineError e) throw e;
        }
        if (!LifeProtocolManager.isWarmupDiagnosticsSuppressed() && BYTES_SOURCE_DUMPED.add(clazz.getName()))
            EcaLogger.info("[HealthDataflow] bytes for {} <- DISK fallback (runtime capture MISSING)", clazz.getName());
        return ProtocolDataflowAnalyzer.defaultClassBytes(clazz);
    }

    /* 磁盘回退不含运行期外部转换，但也不会混入 ECA；安全性优先于分析被自身污染。 */
    private static byte[] classBytesForAnalysis(Class<?> clazz) {
        byte[] analysis = RuntimeBytecodeProvider.getAnalysis(clazz);
        if (analysis != null) return analysis;
        return ProtocolDataflowAnalyzer.defaultClassBytes(clazz);
    }

    /* ==================== 写入入口 ==================== */

    //每个实体类首次走数据流改血时打印一次分析结构诊断
    private static final Set<String> FIRST_WRITE_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> FIRST_EXTERNAL_WRITE_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> ASSOCIATED_SUCCESS_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> ASSOCIATED_FAILURE_DUMPED = ConcurrentHashMap.newKeySet();
    private static final int MAX_ASSOCIATED_CANDIDATES_PER_SOURCE = 8;
    private static final int MAX_ASSOCIATED_COMBINATIONS = 64;

    /* 数据流改血主入口：拿已分析的可写树把目标血量写进目标真实存储，verify 通过返回 true。
       DATAFLOW 与 CONST_OVERRIDE(带可写源)由本入口处理；无源 CONST_OVERRIDE/UNRESOLVED 在表层就被拦掉。 */
    public static boolean write(AnalysisResult tree, LivingEntity entity, float target) {
        if (tree == null || entity == null) return false;
        Class<?> cls = entity.getClass();
        boolean firstWrite = FIRST_WRITE_DUMPED.add(cls.getName());
        if (firstWrite) dumpAnalysisStructure(cls, tree, target);
        return writeViaSources(cls, tree, entity, target, firstWrite,
                (verifiedEntity, verifiedTarget, sink) -> LifeProtocolManager.verify(verifiedEntity, verifiedTarget),
                "dataflow");
    }

    /* 按维护写入的数据依赖合成多位置事务。每个伴随状态的值都由维护表达式反解，
       不按字段名或当前数值相等关系猜测外部镜像。 */
    public static boolean writeCausalTransaction(
            ProtocolDataflowAnalyzer.ProtocolGraphResult graph,
            LivingEntity entity, float target) {
        if (graph == null || entity == null || graph.authoritySources().isEmpty()) return false;
        List<String> diagnostics = new ArrayList<>();
        for (ProtocolDataflowAnalyzer.AuthorityBranch branch : graph.authorityBranches()) {
            /* 其他模组注入共享 getHealth 的分支会出现在每个实体的分析里，但其接收者在本实体上并不存在。
               这类分支不是本实体权威，放进事务只会让整笔写入卡在一个永远写不进的位置上。 */
            if (!isAddressable(branch.authority(), entity)) {
                diagnostics.add("    [" + branch.authority().label
                        + "] skipped=NOT_ADDRESSABLE (branch does not exist on this entity)");
                continue;
            }
            EvalContext context = ProtocolDataflowAnalyzer.newContext(entity);
            List<PreparedSourceWrite> writes = new ArrayList<>();
            Set<Source> selected = new HashSet<>();
            boolean branchSolvable = true;

            for (Source dependency : branch.transactionSources()) {
                if (dependency.equals(branch.authority()) || selected.contains(dependency)) continue;
                if (!isAddressable(dependency, entity)) {
                    diagnostics.add("    [" + branch.authority().label + "] dependency="
                            + dependency.label + " skipped=NOT_ADDRESSABLE");
                    continue;
                }
                Object requiredValue = null;
                boolean constrained = false;
                for (ProtocolDataflowAnalyzer.StoreWrite maintenance : branch.maintenanceWrites()) {
                    if (!maintenance.sink().equals(branch.authority())) continue;
                    if (!ProtocolDataflowAnalyzer.containsSink(maintenance.valueExpr(), dependency)) continue;
                    ProtocolSolveResult solved = ProtocolDataflowAnalyzer.buildWritePath(
                            maintenance.valueExpr(), dependency, Float.valueOf(target), context);
                    if (!solved.solved() || solved.value() == null) continue;
                    if (constrained && !equivalentProtocolValue(requiredValue, solved.value())) {
                        diagnostics.add("    [" + branch.authority().label + "] conflicting state="
                                + dependency.label + " left=" + requiredValue + " right=" + solved.value());
                        branchSolvable = false;
                        break;
                    }
                    requiredValue = solved.value();
                    constrained = true;
                }
                if (!branchSolvable) break;
                if (!constrained) continue;
                writes.add(new PreparedSourceWrite(dependency, dependency.read(entity), requiredValue));
                selected.add(dependency);
            }
            if (!branchSolvable) continue;

            Source authority = branch.authority();
            ProtocolSolveResult solved = ProtocolDataflowAnalyzer.buildWritePath(
                    graph.observation().returnExpr, authority, Float.valueOf(target), context);
            if (!solved.solved() || solved.value() == null) {
                diagnostics.add("    [" + authority.label + "] solve=FAIL "
                        + solved.failure() + " (" + solved.detail() + ")");
                continue;
            }
            writes.add(new PreparedSourceWrite(authority, authority.read(entity), solved.value()));

            float anchorBefore = LifeProtocolManager.readHealthAnchor(entity);
            boolean wroteAll = true;
            // 事务是全有或全无，任一位置写不进就整体作废；失败位置必须记名，否则无从判断是哪一环断的
            for (PreparedSourceWrite write : writes) {
                if (!dispatchWrite(write.sink(), entity, write.value())) {
                    diagnostics.add("    [" + authority.label + "] transaction write=FAIL at sink="
                            + write.sink().label + " value="
                            + limitedText(write.value(), MAX_DIAGNOSTIC_LOG_CHARS));
                    wroteAll = false;
                    break;
                }
            }
            if (wroteAll) LifeProtocolManager.noteAnchorResponse(entity, anchorBefore, target);
            if (wroteAll && LifeProtocolManager.verify(entity, target)) {
                LifeProtocolManager.recordObservedWrite(entity.getClass());
                EcaLogger.info("[LifeProtocolGraph] transaction accepted entity={} authority={} states={} target={}",
                        entity.getClass().getName(), authority.label, writes.size(), target);
                /* 事务被接受不代表值落到了目标逻辑实际读取的对象上：伴随状态的写入值由维护表达式
                   反解得来，且其访问路径可能指向分析期捕获的陈旧实例。逐个回读以区分这两种情况。 */
                if (TRANSACTION_DETAIL_DUMPED.add(entity.getClass().getName())) {
                    for (PreparedSourceWrite write : writes) {
                        EcaLogger.info("[LifeProtocolGraph]   sink={} before={} wrote={} readback={}",
                                write.sink().label,
                                limitedText(write.snapshot(), MAX_DIAGNOSTIC_LOG_CHARS),
                                limitedText(write.value(), MAX_DIAGNOSTIC_LOG_CHARS),
                                limitedText(write.sink().read(entity), MAX_DIAGNOSTIC_LOG_CHARS));
                        dumpSinkReceiver(write.sink(), entity);
                    }
                    dumpVanillaHealthSync(entity);
                }
                return true;
            }

            boolean restored = true;
            for (int index = writes.size() - 1; index >= 0; index--) {
                PreparedSourceWrite write = writes.get(index);
                if (!dispatchWrite(write.sink(), entity, write.snapshot())) restored = false;
            }
            diagnostics.add("    [" + authority.label + "] solved="
                    + limitedText(solved.value(), MAX_DIAGNOSTIC_LOG_CHARS)
                    + " write=" + (wroteAll ? "OK" : "FAIL")
                    + " verify=FAIL restore=" + (restored ? "OK" : "FAIL"));
        }

        if (FAIL_DUMPED.add(entity.getClass().getName() + "|causal")) {
            EcaLogger.info("[LifeProtocolGraph] transaction rejected entity={} branches={} target={}",
                    entity.getClass().getName(), graph.authorityBranches().size(), target);
            for (String diagnostic : diagnostics) {
                EcaLogger.info("[LifeProtocolGraph] {}", limitedText(diagnostic, MAX_DIAGNOSTIC_LOG_CHARS));
            }
        }
        return false;
    }

    private static boolean equivalentProtocolValue(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            double scale = Math.max(1.0d,
                    Math.max(Math.abs(leftNumber.doubleValue()), Math.abs(rightNumber.doubleValue())));
            return Math.abs(leftNumber.doubleValue() - rightNumber.doubleValue()) <= scale * 1.0e-6d;
        }
        return Objects.equals(left, right);
    }

    /* 语义交集写入：与 write 同骨架，但用外部专用校验器(带死亡语义：目标≤0 需实体确实死亡)。
       供 isAlive/isDeadOrDying/hurt/actuallyHurt 逆推出的可写结构落地。 */
    public static boolean writeSemanticIntersection(AnalysisResult tree, LivingEntity entity, float target) {
        if (tree == null || entity == null) return false;
        Class<?> cls = entity.getClass();
        boolean firstWrite = FIRST_EXTERNAL_WRITE_DUMPED.add(cls.getName());
        if (firstWrite) dumpExternalAnalysisStructure(cls, tree, target);
        return writeViaSources(cls, tree, entity, target, firstWrite,
                (verifiedEntity, verifiedTarget, sink) ->
                    // 语义交集的 Choice 可能使无效候选通过符号校验，因此还需验证 getHealth 的实际读数
                    ProtocolDataflowAnalyzer.verifySemanticDataflow(tree.returnExpr, verifiedEntity, verifiedTarget, sink)
                        && LifeProtocolManager.verifySemanticRaw(verifiedEntity, verifiedTarget),
                "external");
    }

    /* 有效血量写入：对模型的有效血量表达式求逆，得到应写入存储的值；反向累加存储会得到上限减目标值，
       写入后用同一表达式复核。校验不经过 getHealth，故解耦实体上的正确写入不会再被误判回滚。 */
    public static boolean writeEffective(ProtocolDataflowAnalyzer.EffectiveProtocolRuntimeModel model,
                                         LivingEntity entity, float target) {
        if (model == null || entity == null) return false;
        Class<?> cls = entity.getClass();
        EvalContext ctx = ProtocolDataflowAnalyzer.newContext(entity);
        ProtocolSolveResult solved = ProtocolDataflowAnalyzer.buildWritePath(
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
        if (LifeProtocolManager.verify(entity, target)) {
            LifeProtocolManager.recordObservedWrite(cls);
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
                    LifeProtocolManager.readHealthAnchor(entity), restored ? "OK" : "FAIL");
        }
        return false;
    }

    private static final Set<String> EFFECTIVE_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> EFFECTIVE_SUCCESS_DUMPED = ConcurrentHashMap.newKeySet();

    /* Runtime-revealed writers commonly maintain ciphertext, keys and integrity tags as one unit.
       Their write set must be committed as a group; probing individual stores creates invalid states. */
    public static boolean writeAssociated(AnalysisResult tree, LivingEntity entity, float target) {
        if (tree == null || entity == null || tree.sources.size() < 2) return false;
        EvalContext context = ProtocolDataflowAnalyzer.newContext(entity);
        List<AssociatedSourceCandidates> groups = new ArrayList<>();
        for (Source sink : tree.sources) {
            List<Object> candidates = ProtocolDataflowAnalyzer.buildWriteCandidates(
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
        float anchorBefore = LifeProtocolManager.readHealthAnchor(entity);
        boolean wroteAll = true;
        for (PreparedSourceWrite write : writes) {
            if (!dispatchWrite(write.sink(), entity, write.value())) {
                wroteAll = false;
                break;
            }
        }
        List<Object> afterWrite = new ArrayList<>(writes.size());
        for (PreparedSourceWrite write : writes) afterWrite.add(write.sink().read(entity));
        if (wroteAll) LifeProtocolManager.noteAnchorResponse(entity, anchorBefore, target);
        boolean verified = wroteAll && LifeProtocolManager.verify(entity, target);
        List<AssociatedWriteState> states = new ArrayList<>(writes.size());
        for (int i = 0; i < writes.size(); i++) {
            PreparedSourceWrite write = writes.get(i);
            states.add(new AssociatedWriteState(write, afterWrite.get(i), write.sink().read(entity)));
        }
        if (verified) {
            LifeProtocolManager.recordObservedWrite(entity.getClass());
            return new AssociatedAttempt(true, true, true, states);
        }
        // 关联写入全部成功但校验失败时，记录观测锚点与存储可能解耦
        if (wroteAll) LifeProtocolManager.recordUnobservedWrite(entity.getClass(), null, "associated-sources");

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
                    write.sink().label,
                    limitedText(write.snapshot(), MAX_DIAGNOSTIC_LOG_CHARS),
                    limitedText(write.value(), MAX_DIAGNOSTIC_LOG_CHARS),
                    limitedText(state.afterWrite(), MAX_DIAGNOSTIC_LOG_CHARS),
                    limitedText(state.afterVerify(), MAX_DIAGNOSTIC_LOG_CHARS));
        }
    }

    /* 首次诊断：打印目标实体类的可写树结构(kind/definingClass/sources 列表)，便于排查不同实体的改血行为 */
    private static void dumpAnalysisStructure(Class<?> cls, AnalysisResult tree, float target) {
        EcaLogger.info("[HealthDataflow] ===== first dataflow write: {} =====", cls.getName());
        EcaLogger.info("[HealthDataflow]   target={} kind={} definingClass={} sources={}",
                target, tree.classify(),
                tree.definingClass != null ? tree.definingClass.getName() : "null",
                tree.sources.size());
        EcaLogger.info("[HealthDataflow]   returnExpr={}",
                limitedText(tree.returnExpr, MAX_EXPRESSION_LOG_CHARS));
        int i = 0;
        for (Source s : tree.sources) {
            EcaLogger.info("[HealthDataflow]   sink#{} {} type={} class={}",
                    i++, s.label, s.valueType.getName(), s.getClass().getSimpleName());
        }
    }

    private static void dumpExternalAnalysisStructure(Class<?> cls, AnalysisResult tree, float target) {
        EcaLogger.info("[SemanticSlice] ===== first external write: {} =====", cls.getName());
        EcaLogger.info("[SemanticSlice]   target={} definingClass={} sources={}", target,
                tree.definingClass != null ? tree.definingClass.getName() : "null", tree.sources.size());
        EcaLogger.info("[SemanticSlice]   returnExpr={}",
                limitedText(tree.returnExpr, MAX_EXPRESSION_LOG_CHARS));
        int index = 0;
        for (Source source : tree.sources) {
            EcaLogger.info("[SemanticSlice]   sink#{} {} type={} class={}",
                    index++, source.label, source.valueType.getName(), source.getClass().getSimpleName());
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

    /* 逐个验证候选 Source，单点未命中再联合写入(应对双源防御)，失败回滚原值。
       仅在缓存失败树时打印一次诊断，避免每-tick 改血刷屏。 */
    public static boolean writeViaSources(Class<?> cls, AnalysisResult ar, LivingEntity entity, float expected,
                                          boolean logSuccess, HealthVerifier verifier, String diagnosticChannel) {
        EvalContext ctx = ProtocolDataflowAnalyzer.newContext(entity);
        List<String> diag = new ArrayList<>();
        List<PreparedSourceWrite> solvedWrites = new ArrayList<>();
        /* 先全部求解再写入：所有分支都基于同一份写前状态求值，快照也在任何写入之前捕获。 */
        for (Source sink : ProtocolDataflowAnalyzer.withoutEcaOwnedSources(ar.sources)) {
            if (!isAddressable(sink, entity)) {
                diag.add("    [" + sink.label + "] skipped=NOT_ADDRESSABLE"
                        + " (branch does not exist on this entity)");
                continue;
            }
            ProtocolSolveResult solved = ProtocolDataflowAnalyzer.buildWritePath(ar.returnExpr, sink, Float.valueOf(expected), ctx);
            if (!solved.solved() || solved.value() == null) {
                diag.add("    [" + sink.label + "] solve=FAIL " + solved.failure() + " ("
                        + limitedText(solved.detail(), MAX_DIAGNOSTIC_LOG_CHARS) + ")");
                continue;
            }
            solvedWrites.add(new PreparedSourceWrite(sink, sink.read(entity), solved.value()));
        }

        /* 权威由多个分支共同决定时，单支写入通过即时回读也留不住——下一 tick 换一支重算就被覆盖。
           结构上识别出多分支，或跨 tick 复查已经证明单写留不住，都直接走合并事务。
           但只剩一个可寻址分支时组不成合并事务，此时再拒绝单支成功就把唯一可用路径也堵死了。 */
        boolean preferCombined = solvedWrites.size() >= 2
                && ProtocolDataflowAnalyzer.hasMultiBranchAuthority(ar.returnExpr);
        if (preferCombined && writeAllSources(solvedWrites, entity, expected, diag, verifier, logSuccess)) {
            return true;
        }

        for (PreparedSourceWrite prepared : solvedWrites) {
            Source sink = prepared.sink();
            Object snapshot = prepared.snapshot();
            Object value = prepared.value();
            float anchorBefore = LifeProtocolManager.readHealthAnchor(entity);
            if (!dispatchWrite(sink, entity, value)) {
                boolean restored = dispatchWrite(sink, entity, snapshot);
                diag.add("    [" + sink.label + "] solved="
                        + limitedText(value, MAX_DIAGNOSTIC_LOG_CHARS)
                        + " write=FAIL restore=" + (restored ? "OK" : "FAIL"));
                continue;
            }
            // 锚点若随本次写入位移到目标值，即为它反映真实存储的证据，据此补正弱取证的误判
            LifeProtocolManager.noteAnchorResponse(entity, anchorBefore, expected);
            if (verifier.verify(entity, expected, sink)) {
                LifeProtocolManager.recordObservedWrite(cls);
                if (logSuccess) {
                    EcaLogger.info("[HealthDataflow] setHealth success entity={} sink={} solved={} expected={}",
                            cls.getName(), sink.label,
                            limitedText(value, MAX_DIAGNOSTIC_LOG_CHARS), expected);
                }
                return true;
            }

            boolean restored = dispatchWrite(sink, entity, snapshot);
            // 写入成功但校验失败时，单独记录观测锚点与存储可能解耦
            LifeProtocolManager.recordUnobservedWrite(cls, sink, sink.label);
            diag.add("    [" + sink.label + "] solved="
                    + limitedText(value, MAX_DIAGNOSTIC_LOG_CHARS)
                    + " verify=FAIL restore=" + (restored ? "OK" : "FAIL"));
        }

        if (!preferCombined
                && writeAllSources(solvedWrites, entity, expected, diag, verifier, logSuccess)) return true;

        if (FAIL_DUMPED.add(cls.getName() + "|" + diagnosticChannel)) {
            EcaLogger.info("[{}] setHealth failed entity={} expected={} sink results:",
                    diagnosticChannel, cls.getName(), expected);
            for (String line : diag) {
                EcaLogger.info("[{}] {}", diagnosticChannel,
                        limitedText(line, MAX_DIAGNOSTIC_LOG_CHARS));
            }
        }
        return false;
    }

    private static String limitedText(Object value, int maxChars) {
        String text = String.valueOf(value);
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...(truncated,totalChars=" + text.length() + ")";
    }

    /* ≥2 个可解 Source 时联合写入(应对需同时写多处的双源防御)，失败逆序回滚。
       writes 由单源循环收集，其 snapshot 均为原值(循环对每次尝试都已回滚)，故回滚即复原。 */
    private static boolean writeAllSources(List<PreparedSourceWrite> writes, LivingEntity entity, float expected,
                                           List<String> diag, HealthVerifier verifier, boolean logSuccess) {
        if (writes.size() < 2) return false;

        float anchorBefore = LifeProtocolManager.readHealthAnchor(entity);
        boolean wroteAll = true;
        for (PreparedSourceWrite write : writes) {
            if (!dispatchWrite(write.sink(), entity, write.value())) {
                wroteAll = false;
                break;
            }
        }
        // 单源逐个写时锚点不动、多源同时写才生效的存储，取证只能在联合写之后进行
        if (wroteAll) LifeProtocolManager.noteAnchorResponse(entity, anchorBefore, expected);
        if (wroteAll && verifier.verify(entity, expected, null)) {
            LifeProtocolManager.recordObservedWrite(entity.getClass());
            if (logSuccess) {
                EcaLogger.info("[HealthDataflow] setHealth success entity={} sink=all-sources expected={}",
                        entity.getClass().getName(), expected);
            }
            return true;
        }
        // 全源写入成功但校验失败时，记录观测锚点与存储可能解耦
        if (wroteAll) LifeProtocolManager.recordUnobservedWrite(entity.getClass(), null, "all-sources");

        boolean restoredAll = true;
        for (int i = writes.size() - 1; i >= 0; i--) {
            PreparedSourceWrite write = writes.get(i);
            if (!dispatchWrite(write.sink(), entity, write.snapshot())) restoredAll = false;
        }
        diag.add("    [all sources] write=" + (wroteAll ? "OK" : "FAIL")
                + " verify=FAIL restore=" + (restoredAll ? "OK" : "FAIL"));
        return false;
    }


    /* ==================== Source 写入分发(按子类形态) ==================== */

    /* 按 Source 子类形态选择写入实现。新增 Source 子类时必须在此扩充分发，否则写入将默默失败。 */
    public static boolean dispatchWrite(Source sink, LivingEntity entity, Object value) {
        // 兜底闸门：任何通道都不得写入 ECA 自身注入的状态，否则会破坏血量锁与无敌标记
        if (ProtocolDataflowAnalyzer.isEcaOwnedSource(sink)) {
            if (ECA_OWNED_WRITE_DUMPED.add(sink.label)) {
                EcaLogger.info("[LifeProtocol] refused write to ECA-owned state sink={}", sink.label);
            }
            return false;
        }
        boolean wrote;
        if (sink instanceof FieldChainSource s) wrote = writeFieldChain(s, entity, value);
        else if (sink instanceof StaticFieldSource s) wrote = writeStaticField(s, value);
        else if (sink instanceof ChainedFieldSource s) wrote = writeChainedField(s, entity, value);
        else if (sink instanceof CapabilityDataSource s) wrote = writeCapability(s, entity, value);
        else if (sink instanceof SynchedDataSource s) wrote = writeSynchedData(s, entity, value);
        else if (sink instanceof MapEntrySource s) wrote = writeMapEntry(s, entity, value);
        else if (sink instanceof NbtValueSource s) wrote = writeNbtValue(s, entity, value);
        else if (sink instanceof ArrayElementSource s) wrote = writeArrayElement(s, entity, value);
        else if (sink instanceof MethodCallSource s) wrote = writeMethodCall(s, entity, value);
        else if (sink instanceof ProtocolConstantOverrideSource s) {
            wrote = writeProtocolConstantOverride(s, entity, value);
        } else {
            wrote = false;
        }
        if (wrote) markSavedDataDirty(sink, entity);
        return wrote;
    }

    /* 常数覆写写入：求出该常数点的 holder(① 实体本体 ② 实体的 health manager)，
       把目标血量登记进 ProtocolConstantOverride；patched 字节码的 resolveHealth(this,...) 据此返回覆写值。 */
    private static boolean writeProtocolConstantOverride(ProtocolConstantOverrideSource s, LivingEntity entity, Object value) {
        Object holder = s.holder(entity);
        if (holder == null) return false;
        if (value instanceof Number n) {
            ProtocolConstantOverride.setOverride(holder, n.floatValue());
            return true;
        }
                // 快照为 null 时清除写入期间设置的覆写，恢复写入前状态
        // 失败后必须清除常数覆写，避免后续 getHealth 继续读取临时值
        ProtocolConstantOverride.removeOverride(holder);
        return true;
    }

    private static boolean writeFieldChain(FieldChainSource s, LivingEntity entity, Object value) {
        VarHandle[] handles = s.handles;
        List<FieldStep> chain = s.chain;
        int n = handles.length;
        FieldStep last = chain.get(n - 1);
        Object coerced;
        try {
            coerced = ProtocolDataflowAnalyzer.coerceForType(value, s.valueType);
            if (coerced == null && s.valueType.isPrimitive()) return false;
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }

        // record component 不可经 VarHandle/Unsafe 写：改为重建 record 写回上一级字段
        Class<?> leafOwner = ProtocolDataflowAnalyzer.loadClass(last.ownerInternal());
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
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }
        }

        Object container;
        try {
            Object cur = entity;
            for (int i = 0; i < n - 1; i++) {
                cur = handles[i].get(cur);
                if (cur == null) return false;
            }
            container = cur;
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }

        // 普通字段优先使用 VarHandle，final 字段写入失败时再尝试 Unsafe
        try {
            handles[n - 1].set(container, coerced);
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            Class<?> owner = ProtocolDataflowAnalyzer.loadClass(last.ownerInternal());
            if (owner == null) return false;
            Field f = ProtocolDataflowAnalyzer.findFieldInHierarchy(owner, last.name());
            if (f == null) return false;
            f.setAccessible(true);
            return UnsafeUtil.unsafePutField(container, f, coerced);
        }
    }

    private static boolean writeStaticField(StaticFieldSource s, Object value) {
        try {
            s.field.set(null, ProtocolDataflowAnalyzer.coerceForType(value, s.valueType));
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    /* 多态 getHealth 的分支可能来自其他模组对 LivingEntity 的注入，对当前实体运行期根本不会走到，
       其接收者也不存在。此类分支不属于本实体的权威，必须在组织事务前排除，
       否则会让整个合并事务卡在一个永远写不进的位置上。 */
    public static boolean isAddressable(Source sink, LivingEntity entity) {
        try {
            EvalContext context = ProtocolDataflowAnalyzer.newContext(entity);
            if (sink instanceof ChainedFieldSource s) {
                Object cur = ProtocolDataflowAnalyzer.evaluate(s.root, context);
                if (cur == null) return false;
                for (int i = 0; i < s.chain.size() - 1; i++) {
                    cur = readField(cur, s.chain.get(i));
                    if (cur == null) return false;
                }
                return true;
            }
            /* 容器型状态的容器可能是存档回调的形参：那是保存时才存在的投影，
               不是实体的活状态，运行期求值必然为空，写入也无意义。 */
            if (sink instanceof NbtValueSource s) {
                return ProtocolDataflowAnalyzer.evaluate(s.containerExpr, context) instanceof CompoundTag;
            }
            if (sink instanceof MapEntrySource s) {
                return ProtocolDataflowAnalyzer.evaluate(s.containerExpr, context) != null;
            }
            if (sink instanceof CapabilityDataSource s) {
                return ProtocolDataflowAnalyzer.evaluate(s.containerExpr, context) != null;
            }
            if (sink instanceof ArrayElementSource s) {
                return ProtocolDataflowAnalyzer.evaluate(s.arrayExpr, context) != null;
            }
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    private static boolean writeChainedField(ChainedFieldSource s, LivingEntity entity, Object value) {
        try {
            Object cur = ProtocolDataflowAnalyzer.evaluate(s.root, ProtocolDataflowAnalyzer.newContext(entity));
            if (cur == null) return chainWriteFailed(s, "root evaluated to null", -1, value);
            for (int i = 0; i < s.chain.size() - 1; i++) {
                cur = readField(cur, s.chain.get(i));
                if (cur == null) return chainWriteFailed(s, "chain step read null", i, value);
            }
            if (writeFieldStep(cur, s.chain.get(s.chain.size() - 1), value)) return true;
            return chainWriteFailed(s, "final field step rejected the value", s.chain.size() - 1, value);
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }
    }

    /* 链式字段写入失败每个源只报一次：失败位置决定后续是补访问路径还是补数值域处理。 */
    private static final Set<String> CHAIN_WRITE_FAILURE_DUMPED = ConcurrentHashMap.newKeySet();

    private static boolean chainWriteFailed(ChainedFieldSource s, String reason, int stepIndex, Object value) {
        if (CHAIN_WRITE_FAILURE_DUMPED.add(s.label + "|" + reason)) {
            EcaLogger.info("[LifeProtocol] chained field write failed sink={} reason={} step={}/{} value={} chain={}",
                    s.label, reason, stepIndex, s.chain.size(),
                    limitedText(value, MAX_DIAGNOSTIC_LOG_CHARS),
                    limitedText(s.chain, MAX_DIAGNOSTIC_LOG_CHARS));
        }
        return false;
    }

    private static boolean writeCapability(CapabilityDataSource s, LivingEntity entity, Object value) {
        try {
            EvalContext ctx = ProtocolDataflowAnalyzer.newContext(entity);
            Object container = ProtocolDataflowAnalyzer.evaluate(s.containerExpr, ctx);
            Object key = ProtocolDataflowAnalyzer.evaluate(s.keyExpr, ctx);
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
        Object value = ProtocolDataflowAnalyzer.invokeCompatibleSafely(container, "getValue", key);
        return value == ProtocolDataflowAnalyzer.INVOKE_FAILED ? null : value;
    }

    private static boolean writeCapabilitySlot(Object container, Object key, Object value) {
        return ProtocolDataflowAnalyzer.invokeCompatibleSafely(container, "setValue", key, value) != ProtocolDataflowAnalyzer.INVOKE_FAILED;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean writeSynchedData(SynchedDataSource s, LivingEntity entity, Object value) {
        try {
            SynchedEntityData ed = entity.getEntityData();
            Int2ObjectMap<?> map = (Int2ObjectMap<?>) ed.itemsById;
            SynchedEntityData.DataItem item = (SynchedEntityData.DataItem) map.get(s.accessor.getId());
            if (item == null) return false;
            Object coerced = ProtocolDataflowAnalyzer.coerceSameType(item.value, value);
            if (coerced == null) return false;
            item.value = coerced;
            item.dirty = true;
            ed.isDirty = true;
            entity.onSyncedDataUpdated(s.accessor);
            return true;
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }
    }

    private static boolean writeMapEntry(MapEntrySource s, LivingEntity entity, Object value) {
        boolean any = false;
        Set<Object> writtenMaps = Collections.newSetFromMap(new IdentityHashMap<>());

        try {
            Object obj = ProtocolDataflowAnalyzer.evaluate(s.containerExpr, ProtocolDataflowAnalyzer.newContext(entity));
            if (obj instanceof Map<?, ?> map && writtenMaps.add(map)) {
                Object key = matchKey(map, entity, s.keyKind);
                if (key != null && unsafeModifyMapEntry(map, key, value)) any = true;
            }
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }

    // 同时更新 owner 类及其嵌套类的静态 Map，保持相关记录表一致
        if (s.ownerClassInternal != null) {
            Class<?> ownerClass = ProtocolDataflowAnalyzer.loadClass(s.ownerClassInternal);
            if (ownerClass != null && writeSiblingMaps(ownerClass, entity, s, value, writtenMaps)) any = true;
        }
        return any;
    }

    /* 事务只覆盖了分析可见的状态。目标逻辑若还从原版血量重新推导，未被写入的原版值会把结果顶回去。
       原版血量是 net/minecraft/ 的调用，不在可内联归属内，其值在依赖图里是不透明节点，故建不出边。 */
    private static void dumpVanillaHealthSync(LivingEntity entity) {
        Object vanilla = null;
        try {
            SynchedEntityData.DataItem<?> item = (SynchedEntityData.DataItem<?>) ((Int2ObjectMap<?>)
                    entity.getEntityData().itemsById).get(LivingEntity.DATA_HEALTH_ID.getId());
            if (item != null) vanilla = item.getValue();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
        EcaLogger.info("[LifeProtocolGraph]   vanillaHealth id={} value={} getHealth={}",
                LivingEntity.DATA_HEALTH_ID.getId(), vanilla, entity.getHealth());
    }

    /* 写入与回读走同一条访问路径，两者一致证明不了它就是目标逻辑读取的那个对象。
       根表达式若是 Reference，说明访问器在分析期被常量折叠成了捕获实例，写入落在陈旧对象上。 */
    private static void dumpSinkReceiver(Source sink, LivingEntity entity) {
        if (!(sink instanceof ChainedFieldSource chained)) return;
        Object receiver = null;
        try {
            receiver = ProtocolDataflowAnalyzer.evaluate(chained.root, ProtocolDataflowAnalyzer.newContext(entity));
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
        EcaLogger.info("[LifeProtocolGraph]     receiver rootKind={} root={} resolved={}@{}",
                chained.root == null ? "null" : chained.root.getClass().getSimpleName(),
                limitedText(chained.root, MAX_DIAGNOSTIC_LOG_CHARS),
                receiver == null ? "null" : receiver.getClass().getName(),
                receiver == null ? 0 : System.identityHashCode(receiver));
    }

    private static boolean writeNbtValue(NbtValueSource source, LivingEntity entity, Object value) {
        try {
            ProtocolDataflowAnalyzer.EvalContext context = ProtocolDataflowAnalyzer.newContext(entity);
            Object container = ProtocolDataflowAnalyzer.evaluate(source.containerExpr, context);
            Object key = ProtocolDataflowAnalyzer.evaluate(source.keyExpr, context);
            if (!(container instanceof CompoundTag tag) || !(key instanceof String name)) return false;
            Object coerced = ProtocolDataflowAnalyzer.coerceForType(value, source.valueType);
            if (coerced == null) return false;
            if (coerced instanceof Boolean item) tag.putBoolean(name, item);
            else if (coerced instanceof Byte item) tag.putByte(name, item);
            else if (coerced instanceof Short item) tag.putShort(name, item);
            else if (coerced instanceof Integer item) tag.putInt(name, item);
            else if (coerced instanceof Long item) tag.putLong(name, item);
            else if (coerced instanceof Float item) tag.putFloat(name, item);
            else if (coerced instanceof Double item) tag.putDouble(name, item);
            else if (coerced instanceof String item) tag.putString(name, item);
            else return false;
            return true;
        } catch (Throwable throwable) {
            if (throwable instanceof VirtualMachineError error) throw error;
            ProtocolDiagnostics.reflectionFailure("write", throwable);
            return false;
        }
    }

    private static void markSavedDataDirty(Expr containerExpression, LivingEntity entity) {
        Set<Expr> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        markSavedDataDirty(containerExpression, entity, visited);
    }

    private static void markSavedDataDirty(Expr expression, LivingEntity entity, Set<Expr> visited) {
        if (expression == null || !visited.add(expression)) return;
        try {
            if (expression instanceof Call || expression instanceof Reference) {
                Object value = ProtocolDataflowAnalyzer.evaluate(
                        expression, ProtocolDataflowAnalyzer.newContext(entity));
                if (value instanceof SavedData savedData) savedData.setDirty();
            }
            if (expression instanceof ChainedFieldSource chained) {
                markSavedDataDirty(chained.root, entity, visited);
            } else if (expression instanceof MapEntrySource mapEntry) {
                markSavedDataDirty(mapEntry.containerExpr, entity, visited);
                markSavedDataDirty(mapEntry.keyExpr, entity, visited);
            } else if (expression instanceof NbtValueSource nbtValue) {
                markSavedDataDirty(nbtValue.containerExpr, entity, visited);
                markSavedDataDirty(nbtValue.keyExpr, entity, visited);
            } else if (expression instanceof ArrayElementSource arrayElement) {
                markSavedDataDirty(arrayElement.arrayExpr, entity, visited);
                markSavedDataDirty(arrayElement.indexExpr, entity, visited);
            } else if (expression instanceof CapabilityDataSource capability) {
                markSavedDataDirty(capability.containerExpr, entity, visited);
                markSavedDataDirty(capability.keyExpr, entity, visited);
            } else if (expression instanceof Call call) {
                for (Expr argument : call.args()) markSavedDataDirty(argument, entity, visited);
            } else if (expression instanceof Op operation) {
                for (Expr argument : operation.args()) markSavedDataDirty(argument, entity, visited);
            } else if (expression instanceof Choice choice) {
                for (Expr alternative : choice.alternatives()) {
                    markSavedDataDirty(alternative, entity, visited);
                }
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError error) throw error;
            EcaLogger.info("[LifeProtocol] SavedData dirty propagation failed entity={} msg={}",
                    entity.getClass().getName(), t.getMessage());
        }
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
            EvalContext ctx = ProtocolDataflowAnalyzer.newContext(entity);
            Object arr = ProtocolDataflowAnalyzer.evaluate(s.arrayExpr, ctx);
            Object idx = ProtocolDataflowAnalyzer.evaluate(s.indexExpr, ctx);
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
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }
    }

    private static boolean writeMethodCall(MethodCallSource s, LivingEntity entity, Object value) {
        try {
            Class<?> owner = ProtocolDataflowAnalyzer.loadClass(s.ownerInternal);
            if (owner == null) return false;
            Type[] argTypes = Type.getArgumentTypes(s.desc);
            boolean isStatic = s.args.size() == argTypes.length;
            int start = isStatic ? 0 : 1;
            Object receiver = null;
            EvalContext ctx = ProtocolDataflowAnalyzer.newContext(entity);
            if (!isStatic) {
                receiver = ProtocolDataflowAnalyzer.evaluate(s.args.get(0), ctx);
                if (receiver == null) return false;
            }
            Object[] values = new Object[argTypes.length];
            Class<?>[] paramTypes = new Class<?>[argTypes.length];
            for (int i = 0; i < argTypes.length; i++) {
                paramTypes[i] = ProtocolDataflowAnalyzer.asmTypeToClass(argTypes[i]);
                if (paramTypes[i] == null) return false;
                Object argValue = i == s.valueArgIndex ? value : ProtocolDataflowAnalyzer.evaluate(s.args.get(start + i), ctx);
                values[i] = ProtocolDataflowAnalyzer.coerceArgPublic(argValue, paramTypes[i]);
            }
            Method method = ProtocolDataflowAnalyzer.findMethod(isStatic ? owner : receiver.getClass(), s.name, paramTypes, values);
            if (method == null && !isStatic) method = ProtocolDataflowAnalyzer.findMethod(owner, s.name, paramTypes, values);
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
                Object boxed = cur == null ? newValue : ProtocolDataflowAnalyzer.coerceSameType(cur, newValue);
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
            Field f = ProtocolDataflowAnalyzer.findFieldInHierarchy(target.getClass(), step.name());
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("read", t); return null; }
    }

    private static boolean writeFieldViaSetter(Object target, FieldStep step, Object value) {
        if (target == null) return false;
        Class<?> fieldType = ProtocolDataflowAnalyzer.descriptorToClass(step.desc());
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
                method.invoke(target, ProtocolDataflowAnalyzer.coerceArgPublic(value, method.getParameterTypes()[0]));
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
                if (ProtocolDataflowAnalyzer.methodArgMatches(params[0], fieldType, value)) return method;
            }
        }
        return null;
    }

    private static boolean writeFieldStep(Object target, FieldStep step, Object value) {
        Field f;
        Class<?> ft;
        try {
            Class<?> owner = ProtocolDataflowAnalyzer.loadClass(step.ownerInternal());
            if (owner == null) return false;
            f = ProtocolDataflowAnalyzer.findFieldInHierarchy(owner, step.name());
            if (f == null) return false;
            f.setAccessible(true);
            ft = f.getType();
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }

        try {
            if (ft == float.class) f.setFloat(target, ((Number) value).floatValue());
            else if (ft == double.class) f.setDouble(target, ((Number) value).doubleValue());
            else if (ft == int.class) f.setInt(target, ((Number) value).intValue());
            else if (ft == long.class) f.setLong(target, ((Number) value).longValue());
            else if (ft == short.class) f.setShort(target, ((Number) value).shortValue());
            else if (ft == byte.class) f.setByte(target, ((Number) value).byteValue());
            else f.set(target, ProtocolDataflowAnalyzer.coerceForType(value, ft));
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
                    args[i] = ProtocolDataflowAnalyzer.coerceForType(newValue, types[i]);
                } else if (current != null) {
                    Method acc = comps[i].getAccessor();
                    acc.setAccessible(true);
                    args[i] = acc.invoke(current);
                } else {
                    args[i] = types[i].isPrimitive() ? ProtocolDataflowAnalyzer.coerceForType(0, types[i]) : null;
                }
            }
            Constructor<?> ctor = recordClass.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("read", t); return null; }
    }

}



