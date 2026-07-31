package net.eca.util.health.internal;

import net.eca.config.EcaConfiguration;
import net.eca.coremod.EcaTransformerManager;
import net.eca.coremod.LivingEntityHook;
import net.eca.coremod.RuntimeBytecodeProvider;
import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.eca.util.health.LifeProtocolAnalyzer;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.Source;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.SynchedDataSource;
import net.eca.util.health.protocol.AccessPath;
import net.eca.util.health.protocol.CandidateState;
import net.eca.util.health.protocol.LifeProtocol;
import net.eca.util.health.protocol.MethodReference;
import net.eca.util.health.protocol.MutationTransaction;
import net.eca.util.health.protocol.ProtocolEvidence;
import net.eca.util.health.protocol.SemanticEndpoint;
import net.eca.util.health.protocol.StateLocation;
import net.eca.util.health.protocol.ValidationPlan;
import net.eca.util.health.protocol.ValueExpression;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.objectweb.asm.Type;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * 改血总管理器：顶层候选分析交给 LifeProtocolAnalyzer，写入交给 ProtocolDataFlowEngine。
 * 本类只负责编排能力、协议提升、预热与运行期验证。
 */
public final class LifeProtocolManager {

    private LifeProtocolManager() {}

    private static final String MANAGER_INTERNAL_NAME = "net/eca/util/health/internal/LifeProtocolManager";
    private static final LifeProtocolAnalyzer PROTOCOL_ANALYZER = new LifeProtocolAnalyzer();
    private static final Map<Class<?>, LifeProtocolAnalyzer.AnalysisResult> STATIC_ANALYSES =
            new ConcurrentHashMap<>();
    private static final Map<Class<?>, ResolvedProtocol> VERIFIED_PROTOCOLS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, BytecodeStamp> BYTECODE_STAMPS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<RejectionKey, String>> REJECTED_PROTOCOLS = new ConcurrentHashMap<>();
    private static final Set<String> PROTOCOL_RESOLUTION_DUMPED = ConcurrentHashMap.newKeySet();

    public static boolean setHealth(LivingEntity entity, float targetHealth) {
        if (entity == null || !Float.isFinite(targetHealth)) return false;
        if (entity.level() == null || entity.level().isClientSide) return false;
        ProtocolDataFlowEngine.init();

        float before = readHealthAnchor(entity);
        if (ProtocolValueSemantics.matchesWithDeathSemantics(before, targetHealth)) return true;

        ResolvedProtocol cached = VERIFIED_PROTOCOLS.get(entity.getClass());
        if (cached != null
                && !cached.protocol().fingerprint().classStructure().equals(classFingerprint(entity.getClass()))) {
            REJECTED_PROTOCOLS.remove(entity.getClass());
            invalidateProtocol(entity.getClass(), "class structure fingerprint changed");
            cached = null;
        }
        if (cached != null) {
            if (executeCapability(cached.capability(), entity, targetHealth)) {
                ProtocolVerificationManager.schedule(entity, before, targetHealth);
                schedulePersistenceValidation(cached.protocol(), entity, targetHealth);
                return true;
            }
            invalidateProtocol(entity.getClass(), "cached transaction failed immediate validation");
        }

        Capability capability = discoverCapability(entity, targetHealth);
        if (capability == null) return false;

        LifeProtocol protocol = describeProtocol(entity.getClass(), capability);
        VERIFIED_PROTOCOLS.put(entity.getClass(), new ResolvedProtocol(protocol, capability));
        ProtocolVerificationManager.schedule(entity, before, targetHealth);
        schedulePersistenceValidation(protocol, entity, targetHealth);
        if (PROTOCOL_RESOLUTION_DUMPED.add(entity.getClass().getName())) {
            EcaLogger.info("[LifeProtocol] resolved entity={} capability={} fingerprint={}",
                    entity.getClass().getName(), capability, protocol.fingerprint().protocolStructure());
        }
        return true;
    }

    public static Optional<LifeProtocol> getProtocol(Class<?> entityClass) {
        ResolvedProtocol resolved = entityClass == null ? null : VERIFIED_PROTOCOLS.get(entityClass);
        return resolved == null ? Optional.empty() : Optional.of(resolved.protocol());
    }

    public static void invalidateProtocol(Class<?> entityClass, String reason) {
        if (entityClass == null) return;
        ResolvedProtocol removed = VERIFIED_PROTOCOLS.remove(entityClass);
        DATAFLOW_TABLE.remove(entityClass);
        STATIC_ANALYSES.remove(entityClass);
        PROTOCOL_ANALYZER.invalidate(entityClass);
        if (removed != null) {
            EcaLogger.info("[LifeProtocol] invalidated entity={} capability={} reason={}",
                    entityClass.getName(), removed.capability(), reason);
        }
    }

    static void invalidateResolvedProtocol(Class<?> entityClass, String reason) {
        if (entityClass == null) return;
        ResolvedProtocol removed = VERIFIED_PROTOCOLS.remove(entityClass);
        if (removed != null) {
            EcaLogger.info("[LifeProtocol] rejected entity={} capability={} reason={}",
                    entityClass.getName(), removed.capability(), reason);
        }
    }

    public static void clear() {
        VERIFIED_PROTOCOLS.clear();
        BYTECODE_STAMPS.clear();
        DATAFLOW_TABLE.clear();
        STATIC_ANALYSES.clear();
        PROTOCOL_ANALYZER.clear();
        REJECTED_PROTOCOLS.clear();
        ANCHOR_REFLECTS_WRITES.clear();
        ANCHOR_OBSERVED.clear();
        UNOBSERVED_WRITES.clear();
        ProtocolVerificationManager.clear();
    }

    private static Capability discoverCapability(LivingEntity entity, float targetHealth) {
        warmAnchorTrust(entity);
        List<Capability> candidates = new ArrayList<>();
        if (isAnchorTrustworthy(entity)) candidates.add(Capability.VANILLA_STATE);
        resolveTree(entity.getClass());
        LifeProtocolAnalyzer.AnalysisResult direct = STATIC_ANALYSES.get(entity.getClass());
        ProtocolDataflowAnalyzer.ProtocolGraphResult graph =
                PROTOCOL_ANALYZER.protocolGraph(entity.getClass());
        boolean causalGraph = false;
        if (direct != null && direct.status() == LifeProtocolAnalyzer.AnalysisResult.Status.AMBIGUOUS
                && !direct.candidates().isEmpty()) {
            if (graph.hasMaintenanceWriter()) {
                causalGraph = true;
                candidates.add(Capability.CAUSAL_GRAPH_TRANSACTION);
            } else {
                candidates.add(Capability.GET_HEALTH_DATAFLOW);
            }
        }
        /* 已证明存在环境维护写入时，对象求解和方法探针由因果图协议分支统一编排；
           没有因果图时，它们才作为普通协议候选参与发现。 */
        if (!causalGraph && EcaConfiguration.getAttackEnableRadicalLogicSafely()) {
            candidates.add(Capability.SEMANTIC_INTERSECTION);
            candidates.add(Capability.METHOD_TRANSACTION);
            candidates.add(Capability.NUMERIC_INVERSION);
        }
        for (Capability capability : candidates) {
            if (isProtocolRejected(entity.getClass(), capability, targetHealth)) continue;
            if (executeCapability(capability, entity, targetHealth)) return capability;
        }
        return null;
    }

    private static void schedulePersistenceValidation(LifeProtocol protocol, LivingEntity entity,
                                                      float targetHealth) {
        boolean requested = protocol.validationPlan().checks().stream()
                .anyMatch(check -> check.stage() == ValidationPlan.Stage.PERSISTENCE_RELOAD);
        if (requested && targetHealth > 0.0f) {
            ProtocolVerificationManager.expectPersistence(entity, targetHealth);
        }
    }

    private static boolean executeCapability(Capability capability, LivingEntity entity, float targetHealth) {
        return switch (capability) {
            case VANILLA_STATE -> applyVanillaState(entity, targetHealth);
            case GET_HEALTH_DATAFLOW -> applyDataflow(entity, targetHealth);
            case CAUSAL_GRAPH_TRANSACTION -> applyCausalGraphTransaction(entity, targetHealth);
            case SEMANTIC_INTERSECTION -> applySemanticIntersection(entity, targetHealth);
            case METHOD_TRANSACTION -> applyProtocolMethodProbe(entity, targetHealth);
            case NUMERIC_INVERSION -> applyNumericInversion(entity, targetHealth);
        };
    }

    static List<Float> predictDelayedHealthStates(LivingEntity entity, float targetHealth) {
        if (entity == null || targetHealth <= 0.0f) return List.of();
        ProtocolDataflowAnalyzer.ProtocolGraphResult graph =
                PROTOCOL_ANALYZER.protocolGraph(entity.getClass());
        ProtocolDataflowAnalyzer.EvalContext context = ProtocolDataflowAnalyzer.newContext(entity);
        List<Float> predictions = new ArrayList<>();
        for (ProtocolDataflowAnalyzer.AuthorityBranch branch : graph.authorityBranches()) {
            for (ProtocolDataflowAnalyzer.StoreWrite maintenance : branch.maintenanceWrites()) {
                if (!maintenance.sink().equals(branch.authority())) continue;
                try {
                    Object nextState = ProtocolDataflowAnalyzer.evaluate(maintenance.valueExpr(), context);
                    if (nextState == null) continue;
                    Object nextHealth = ProtocolDataflowAnalyzer.evaluateWithSourceOverride(
                            graph.observation().returnExpr, context, branch.authority(), nextState);
                    if (nextHealth instanceof Number number) {
                        float prediction = number.floatValue();
                        if (Float.isFinite(prediction) && !predictions.contains(prediction)) {
                            predictions.add(prediction);
                        }
                    }
                } catch (Throwable throwable) {
                    if (throwable instanceof VirtualMachineError error) throw error;
                }
            }
        }
        return List.copyOf(predictions);
    }

    private static boolean applyCausalGraphTransaction(LivingEntity entity, float targetHealth) {
        ProtocolDataflowAnalyzer.ProtocolGraphResult graph =
                PROTOCOL_ANALYZER.protocolGraph(entity.getClass());
        if (!graph.hasMaintenanceWriter()) return false;
        List<Object> rollbackRoots = collectRollbackRoots(graph.observation(), entity);
        ProtocolStateSnapshot snapshot = ProtocolStateSnapshot.captureProbe(entity, rollbackRoots);
        boolean success = ProtocolDataFlowEngine.writeCausalTransaction(graph, entity, targetHealth);
        if (success) return true;
        snapshot.restore();

        if (EcaConfiguration.getAttackEnableRadicalLogicSafely()) {
            for (ProtocolDataflowAnalyzer.AuthorityBranch branch : graph.authorityBranches()) {
                if (ProtocolNumericInverter.searchAuthority(entity, targetHealth, branch.authority())) {
                    return true;
                }
            }
            return applyProtocolMethodProbe(entity, targetHealth);
        }
        return false;
    }

    private static boolean applyVanillaState(LivingEntity entity, float targetHealth) {
        try {
            Float snapshot = entity.getEntityData().get(LivingEntity.DATA_HEALTH_ID);
            float anchorBefore = readHealthAnchor(entity);
            EntityUtil.setBasicHealth(entity, targetHealth);
            noteAnchorResponse(entity, anchorBefore, targetHealth);
            if (verify(entity, targetHealth)) {
                Source source = new SynchedDataSource(LivingEntity.DATA_HEALTH_ID, float.class);
                ProtocolVerificationManager.registerRollback(entity,
                        List.of(new ProtocolVerificationManager.SourceSnapshot(source, snapshot)));
                return true;
            }
            EntityUtil.setBasicHealth(entity, snapshot);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError error) throw error;
            EcaLogger.info("[LifeProtocol] vanilla state transaction failed entity={} msg={}",
                    entity.getClass().getName(), t.getMessage());
        }
        return false;
    }

    /* ==================== 数据流主表 ==================== */

    /* 数据流主表：实体类 → 2 态。成功 = 可写结构 AnalysisResult；失败 = AnalysisResult.DATA_FLOW_ANALYZER_FAILED 哨兵。
       warmup 后台预填，setHealth 时查询；未命中现场分析并写回，失败标记后续不再重复分析。 */
    private static final Map<Class<?>, ProtocolDataflowAnalyzer.AnalysisResult> DATAFLOW_TABLE = new ConcurrentHashMap<>();

    /* 记录已经安装语义交集 patch 的类，安装去重由管理器维护。 */
    private static final Set<Class<?>> SEMANTIC_INSTALLED = ConcurrentHashMap.newKeySet();

    /* 预热专用后台执行器：常驻单守护线程，承接 LoadComplete 的全量预热。
       纯分析只读，离开主线程安全。 */
    private static final ExecutorService ANALYSIS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ECA-Health-Analysis");
        t.setDaemon(true);
        return t;
    });

    /* 语义交集与预热使用不同线程，避免全量预热使运行期扫描长期排队。 */
    private static final ExecutorService RUNTIME_ANALYSIS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ECA-Health-SemanticSlice");
        t.setDaemon(true);
        return t;
    });

    /* 有效血量模型分析不依赖语义交集结果，使用独立线程避免两类任务相互阻塞。 */
    private static final ExecutorService MODEL_ANALYSIS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ECA-Health-ModelAnalysis");
        t.setDaemon(true);
        return t;
    });

    /* 语义交集异步分析去重：同类并发首改只提交一次后台分析任务 */
    private static final Set<Class<?>> SEMANTIC_SLICE_PENDING = ConcurrentHashMap.newKeySet();

    /* 语义交集诊断去重(每类一次)：提交、开始执行、失败三个节点各自记一次。
       提交后没有开始记录表示任务仍在队列中。 */
    private static final Set<String> SEMANTIC_SLICE_SUBMIT_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> SEMANTIC_SLICE_START_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> SEMANTIC_SLICE_FAILURE_DUMPED = ConcurrentHashMap.newKeySet();
    private static final int SEMANTIC_SLICE_FAILURE_FRAMES = 12;

    /* 记录已安装 HeadBridge 的类。基础候选和扩展候选分别维护探测状态与 writer 缓存，
       防止一组的探测结果阻止另一组执行。 */
    private static final Set<Class<?>> METHOD_BRIDGE_INSTALLED = ConcurrentHashMap.newKeySet();
    /* 未找到 writer 或缓存写入失败后进入冷却，限制行为探测频率并允许瞬时失败后重试。 */
    private static final long PROBE_RETRY_COOLDOWN_NANOS = 5_000_000_000L;
    private static final Map<Class<?>, Long> DIRECT_PROBE_RETRY_LEGACY = new ConcurrentHashMap<>();
    private static final Map<Class<?>, ProtocolMethodProbe.DirectWriter> DIRECT_WRITER_LEGACY = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Long> DIRECT_PROBE_RETRY_EXTENDED = new ConcurrentHashMap<>();
    private static final Map<Class<?>, ProtocolMethodProbe.DirectWriter> DIRECT_WRITER_EXTENDED = new ConcurrentHashMap<>();

    /* 两段 DirectCall 各自允许的候选形态(与 findDirectCandidates 的 kind 对应) */
    private static final Set<ProtocolMethodProbe.WriterKind> LEGACY_DIRECT_KINDS =
            Set.of(ProtocolMethodProbe.WriterKind.METHOD, ProtocolMethodProbe.WriterKind.FUNCTIONAL_FIELD);
    private static final Set<ProtocolMethodProbe.WriterKind> EXTENDED_DIRECT_KINDS =
            Set.of(ProtocolMethodProbe.WriterKind.METHOD_HANDLE_FIELD, ProtocolMethodProbe.WriterKind.FIELD_COMMIT);

    /* 数值反演前置跳过诊断去重：每类每原因只打一次，避免每-tick 改血刷屏 */
    private static final Set<String> NUMERIC_INVERSION_SKIP_DUMPED = ConcurrentHashMap.newKeySet();

    /* 预热只建立缓存和安装必要桥接；诊断留给首次真实改血，避免启动期刷屏。 */
    private static final ThreadLocal<Boolean> WARMUP_DIAGNOSTICS_SUPPRESSED = ThreadLocal.withInitial(() -> false);

    /* ==================== 对外编排入口 ==================== */

    /* 查询 DATAFLOW_TABLE：失败结果直接返回，可写结果交由 ProtocolDataFlowEngine；缓存缺失时执行分析并写入表。 */
    public static boolean applyDataflow(LivingEntity target, float targetHealth) {
        if (target == null) return false;
        ProtocolDataflowAnalyzer.AnalysisResult tree = resolveTree(target.getClass());
        if (tree == ProtocolDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED) return false;
        List<Object> rollbackRoots = collectRollbackRoots(tree, target);
        ProtocolStateSnapshot snapshot = ProtocolStateSnapshot.captureProbe(target, rollbackRoots);
        boolean success = ProtocolDataFlowEngine.write(tree, target, targetHealth);
        if (!success) snapshot.restore();
        return success;
    }

    /* getHealth 数据流无法定位存储时，逆向 isAlive/isDeadOrDying/hurt/actuallyHurt 定位血量存储。
       激进逻辑或语义交集关闭时直接返回。分析结果只从缓存读取；未就绪时提交后台任务并跳过本次
       避免阻塞服务器线程；分析完成后写入缓存供后续调用，就绪后再安装并写入。 */
    public static boolean applySemanticIntersection(LivingEntity target, float targetHealth) {
        if (target == null) return false;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()) return false;
        Class<?> cls = target.getClass();
        ProtocolDataflowAnalyzer.AnalysisResult tree = ProtocolDataflowAnalyzer.peekSemanticSliceResult(cls);
        if (tree == null) {
            submitSemanticSliceAnalysis(cls);
            /* 比较表达式扫描不依赖证据，与语义交集并行预跑。两者串行时总等待是各自耗时之和，
               并行后证据到手时表达式往往已就绪，可当场建模。 */
            submitComparisonPrescan(cls);
            return false;
        }
        if (SEMANTIC_INSTALLED.add(cls)) ProtocolConstantOverride.install(tree);
        List<Object> rollbackRoots = collectRollbackRoots(tree, target);
        ProtocolStateSnapshot snapshot = ProtocolStateSnapshot.captureProbe(target, rollbackRoots);
        boolean success = ProtocolDataFlowEngine.writeSemanticIntersection(tree, target, targetHealth);
        if (success) return true;
        snapshot.restore();
        /* 语义交集按存储即血量处理，存储经换算才得到血量时写入值方向不对，且校验读 getHealth 也不反映。
           此处承接同一批存储，改用有效血量表达式求逆与校验；证据正是上面写入尝试刚记录下来的。 */
        return applyEffectiveHealth(target, targetHealth);
    }

    /* getHealth 与实际存储解耦时，使用实体生死判定所读取的有效血量表达式作为观测锚点，
       并通过表达式反演计算存储值。仅对已有解耦记录的类启用。
       由 applySemanticIntersection 在其写入失败后调用，门控与之共用。 */
    private static boolean applyEffectiveHealth(LivingEntity target, float targetHealth) {
        Class<?> cls = target.getClass();
        // 校验成功会清空解耦证据，故已装锚点的类必须继续放行，否则一旦成功就再也走不进本通道
        if (!hasHealthAnchor(cls) && !isHealthReadDecoupled(cls)) return false;
        ProtocolDataflowAnalyzer.EffectiveProtocolRuntimeModel model =
                ProtocolDataflowAnalyzer.peekEffectiveProtocolRuntimeModel(cls);
        if (model == null) {
            /* 比较表达式已缓存时建模只剩遍历与打分，当场完成即可，省去一次改血往返；
               未缓存则需扫描字节码，耗时较长，仍转后台并跳过本次。 */
            if (ProtocolDataflowAnalyzer.hasComparisonCache(cls)) {
                model = ProtocolDataflowAnalyzer.resolveCachedEffectiveProtocolRuntimeModel(cls, unobservedSinks(cls));
            }
            if (model == null) {
                submitEffectiveModelAnalysis(cls);
                return false;
            }
        }

        ProtocolDataflowAnalyzer.EffectiveProtocolRuntimeModel resolved = model;
        /* 依赖当次伤害量的式子不是血量读取，误选它做锚点会因求解与校验共用同一表达式而恒真。
           此时必须连同已确认状态一并撤销：错误锚点一旦留下，改血将永久假成功。 */
        if (ProtocolDataflowAnalyzer.dependsOnDamageInput(resolved.readExpr())) {
            if (EFFECTIVE_MODEL_REJECT_DUMPED.add(cls.getName() + "|" + resolved.storage().label)) {
                EcaLogger.info("[EffectiveHealth] model rejected entity={} storage={} reason=depends on damage input",
                        cls.getName(), resolved.storage().label);
            }
            ProtocolRuntimeModel healthModel = ProtocolRuntimeModel.forClass(cls);
            healthModel.setEffectiveObservationConfirmed(false);
            healthModel.clearEffectiveObservation();
            ProtocolDataflowAnalyzer.rejectEffectiveModel(cls, resolved);
            EFFECTIVE_MODEL_SUBMITTED.remove(cls);
            return false;
        }

        /* 模型可能取自与血量反向的内部计数，按原版极性求逆会写出镜像值并当场判死。
           求解与校验共用同一表达式，方向错了两边一起错，因此必须在写入前校正。 */
        ProtocolDataflowAnalyzer.EffectiveProtocolRuntimeModel oriented =
                ProtocolDataflowAnalyzer.orientToAliveSide(resolved, target);
        if (oriented == null) {
            if (EFFECTIVE_MODEL_REJECT_DUMPED.add(cls.getName() + "|polarity|" + resolved.storage().label)) {
                EcaLogger.info("[EffectiveHealth] model rejected entity={} storage={} reason=reading sits on zero boundary",
                        cls.getName(), resolved.storage().label);
            }
            return false;
        }
        if (oriented != resolved && EFFECTIVE_POLARITY_DUMPED.add(cls.getName() + "|" + oriented.storage().label)) {
            EcaLogger.info("[EffectiveHealth] polarity inverted entity={} storage={} readExpr={}",
                    cls.getName(), oriented.storage().label, oriented.readExpr());
        }

        // 使用有效血量表达式校验，避免 getHealth 与存储解耦时错误接受或拒绝写入
        boolean anchorWasPresent = hasHealthAnchor(cls);
        registerEffectiveHealthAnchor(cls, entity -> {
            Object value = ProtocolDataflowAnalyzer.evaluate(
                    oriented.readExpr(), ProtocolDataflowAnalyzer.newContext(entity));
            return value instanceof Number number ? number.floatValue() : Float.NaN;
        });
        List<Object> rollbackRoots = collectRollbackRoots(target);
        ProtocolStateSnapshot snapshot = ProtocolStateSnapshot.captureProbe(target, rollbackRoots);
        boolean success = ProtocolDataFlowEngine.writeEffective(oriented, target, targetHealth);
        if (success) {
            ProtocolRuntimeModel.forClass(cls).setEffectiveObservationConfirmed(true);
            return true;
        }
        snapshot.restore();
        /* 未经成功写入确认的模型失败后立即失效，以便候选集合变化时重新分析。
           写入失败由快照回滚；已经确认的锚点不因单次失败而移除。 */
        ProtocolRuntimeModel healthModel = ProtocolRuntimeModel.forClass(cls);
        if (!healthModel.effectiveObservationConfirmed()) {
            if (!anchorWasPresent) {
                healthModel.clearEffectiveObservation();
            }
            ProtocolDataflowAnalyzer.rejectEffectiveModel(cls, oriented);
            EFFECTIVE_MODEL_SUBMITTED.remove(cls);
        }
        return false;
    }

    /* 结构判据拒绝模型的诊断去重，按类与存储标识。 */
    private static final Set<String> EFFECTIVE_MODEL_REJECT_DUMPED = ConcurrentHashMap.newKeySet();

    /* 极性校正的诊断去重，按类与存储标识。 */
    private static final Set<String> EFFECTIVE_POLARITY_DUMPED = ConcurrentHashMap.newKeySet();

    /* 已提交模型分析的候选签名：同一批候选只分析一次；候选随各通道失败逐步补齐，签名一变即允许重试。 */
    private static final Map<Class<?>, String> EFFECTIVE_MODEL_SUBMITTED = new ConcurrentHashMap<>();

    /* 已提交比较表达式预扫的类，每类一次。 */
    private static final Set<Class<?>> COMPARISON_PRESCAN_SUBMITTED = ConcurrentHashMap.newKeySet();

    /* 已按实体加入世界触发过预热的类，每类一次。 */
    private static final Set<Class<?>> JOIN_PREWARM_SUBMITTED = ConcurrentHashMap.newKeySet();

    /* 实体首次加入世界时按需预热语义交集。
       按已加载类顺序盲扫命中率很低——真正会被改血的实体在被打之前必然先出现在世界里，
       以此为触发点，等玩家打到它时分析通常已完成，首次写入即可直接命中缓存。
       原版实体的 getHealth 走数据流即可写入，无需语义交集。 */
    public static void onEntityJoinLevel(LivingEntity entity) {
        if (entity == null) return;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()) return;
        Class<?> cls = entity.getClass();
        if (cls.getName().startsWith("net.minecraft.")) return;
        if (!JOIN_PREWARM_SUBMITTED.add(cls)) return;
        try {
            RUNTIME_ANALYSIS_EXECUTOR.submit(() -> prewarmJoinedEntityClass(cls));
        } catch (Throwable t) {
            JOIN_PREWARM_SUBMITTED.remove(cls);
            EcaLogger.info("[SemanticSlice] join prewarm submit rejected entity={} type={} msg={}",
                    cls.getName(), t.getClass().getName(), t.getMessage());
        }
    }

    /* 数据流未分析过的类先补分析，再据其形态决定是否需要语义交集。
       写入打不穿的形态有两种：分析失败，以及 getHealth 返回常数语义——
       后者数据流分析本身是成功的，只按失败筛会把这类实体漏掉。 */
    private static void prewarmJoinedEntityClass(Class<?> cls) {
        try {
            ProtocolDataflowAnalyzer.AnalysisResult tree = resolveTree(cls);
            if (tree != ProtocolDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED
                    && tree.classify() != ProtocolDataflowAnalyzer.AnalysisResult.Kind.CONST_OVERRIDE) return;
            EcaLogger.info("[SemanticSlice] join prewarm started entity={}", cls.getName());
            /* 预热跑完之前，同一执行器上的运行期请求只能排队等待，因此预热时长直接决定
               "生成后第一次改血能否命中缓存"。分两段计时以便定位是哪一段变慢。 */
            long scanStart = System.nanoTime();
            ProtocolDataflowAnalyzer.resolveSemanticSliceResult(cls);
            long scanMs = (System.nanoTime() - scanStart) / 1_000_000L;
            long comparisonStart = System.nanoTime();
            // 比较表达式一并预扫，证据到手后即可当场建模，无需再等一次改血
            ProtocolDataflowAnalyzer.prewarmClassComparisons(cls);
            long comparisonMs = (System.nanoTime() - comparisonStart) / 1_000_000L;
            EcaLogger.info("[SemanticSlice] join prewarm done entity={} semanticSlice={}ms comparisons={}ms total={}ms",
                    cls.getName(), scanMs, comparisonMs, scanMs + comparisonMs);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.info("[SemanticSlice] join prewarm threw entity={} type={} msg={}",
                    cls.getName(), t.getClass().getName(), t.getMessage());
        }
    }

    /* 在语义交集进行的同时预扫比较表达式，使两段耗时重叠而非相加。 */
    private static void submitComparisonPrescan(Class<?> cls) {
        if (ProtocolDataflowAnalyzer.hasComparisonCache(cls)) return;
        if (!COMPARISON_PRESCAN_SUBMITTED.add(cls)) return;
        try {
            MODEL_ANALYSIS_EXECUTOR.submit(() -> {
                try {
                    ProtocolDataflowAnalyzer.prewarmClassComparisons(cls);
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError e) throw e;
                    EcaLogger.info("[EffectiveHealth] comparison prescan threw entity={} type={} msg={}",
                            cls.getName(), t.getClass().getName(), t.getMessage());
                }
            });
        } catch (Throwable t) {
            COMPARISON_PRESCAN_SUBMITTED.remove(cls);
            EcaLogger.info("[EffectiveHealth] comparison prescan submit rejected entity={} type={} msg={}",
                    cls.getName(), t.getClass().getName(), t.getMessage());
        }
    }

    private static void submitEffectiveModelAnalysis(Class<?> cls) {
        // 比较表达式可以直接提供候选，因此无需等待其他分析通道产生候选
        List<ProtocolDataflowAnalyzer.Source> candidates = unobservedSinks(cls);
        if (ProtocolDataflowAnalyzer.isEffectiveModelMiss(cls, candidates)) return;
        String signature = ProtocolDataflowAnalyzer.candidateSignature(candidates);
        if (signature.equals(EFFECTIVE_MODEL_SUBMITTED.put(cls, signature))) return;
        EcaLogger.info("[EffectiveHealth] model analysis submitted entity={} candidates={}",
                cls.getName(), candidates.size());
        try {
            MODEL_ANALYSIS_EXECUTOR.submit(() -> {
                try {
                    ProtocolDataflowAnalyzer.resolveEffectiveProtocolRuntimeModel(cls, candidates);
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError e) throw e;
                    EcaLogger.info("[EffectiveHealth] model analysis threw entity={} type={} msg={}",
                            cls.getName(), t.getClass().getName(), t.getMessage());
                }
            });
        } catch (Throwable t) {
            // 提交被拒时让出签名，否则该批候选此后永远不会再分析
            EFFECTIVE_MODEL_SUBMITTED.remove(cls);
            EcaLogger.info("[EffectiveHealth] model analysis submit rejected entity={} type={} msg={}",
                    cls.getName(), t.getClass().getName(), t.getMessage());
        }
    }

    /* 语义交集在后台去重执行，完成后写入分析缓存。任务异常必须记录，
       以便区分配置关闭、分析进行中和分析失败。 */
    private static void submitSemanticSliceAnalysis(Class<?> cls) {
        if (!SEMANTIC_SLICE_PENDING.add(cls)) return;
        if (SEMANTIC_SLICE_SUBMIT_DUMPED.add(cls.getName())) {
            EcaLogger.info("[SemanticSlice] analysis submitted entity={}", cls.getName());
        }
        try {
            RUNTIME_ANALYSIS_EXECUTOR.submit(() -> {
                try {
                    // 与 submitted 配对：只有 submitted 没有 started，说明任务卡在队列而非分析失败
                    if (SEMANTIC_SLICE_START_DUMPED.add(cls.getName())) {
                        EcaLogger.info("[SemanticSlice] analysis started entity={}", cls.getName());
                    }
                    ProtocolDataflowAnalyzer.resolveSemanticSliceResult(cls);
                } catch (Throwable t) {
                    dumpSemanticSliceFailure(cls, t);
                    if (t instanceof VirtualMachineError e) throw e;
                } finally {
                    SEMANTIC_SLICE_PENDING.remove(cls);
                }
            });
        } catch (Throwable t) {
            // 提交被拒时必须让出占位，否则该类此后永远跳过语义交集
            SEMANTIC_SLICE_PENDING.remove(cls);
            EcaLogger.info("[SemanticSlice] analysis submit rejected entity={} type={} msg={}",
                    cls.getName(), t.getClass().getName(), t.getMessage());
        }
    }

    /* 语义交集失败时每类记录一次异常类型、消息和有限数量的栈帧。 */
    private static void dumpSemanticSliceFailure(Class<?> cls, Throwable t) {
        if (!SEMANTIC_SLICE_FAILURE_DUMPED.add(cls.getName())) return;
        EcaLogger.info("[SemanticSlice] analysis threw entity={} type={} msg={}",
                cls.getName(), t.getClass().getName(), t.getMessage());
        StackTraceElement[] frames = t.getStackTrace();
        for (int i = 0; i < Math.min(frames.length, SEMANTIC_SLICE_FAILURE_FRAMES); i++) {
            EcaLogger.info("[SemanticSlice]   at {}", frames[i]);
        }
        if (frames.length > SEMANTIC_SLICE_FAILURE_FRAMES) {
            EcaLogger.info("[SemanticSlice]   ... {} more frames", frames.length - SEMANTIC_SLICE_FAILURE_FRAMES);
        }
        Throwable cause = t.getCause();
        if (cause != null) {
            EcaLogger.info("[SemanticSlice]   caused by {} msg={}", cause.getClass().getName(), cause.getMessage());
        }
    }

    /* 数据流和语义交集无法写入存储时，尝试调用实体自身的血量 writer。
       激进逻辑或方法探针关闭时直接返回。
       第一阶段依次尝试反射 setter、函数式字段和 HeadBridge；第二阶段尝试 MethodHandle 字段及暂存字段提交。
       第二阶段可能触发不可回滚的目标状态，因此必须在 HeadBridge 之后执行。 */
    public static boolean applyProtocolMethodProbe(LivingEntity target, float targetHealth) {
        if (target == null) return false;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()) return false;
        Class<?> cls = target.getClass();
        List<Object> rollbackRoots = collectRollbackRoots(target);

        // 第一阶段：基础 DirectCall 候选
        if (runDirectProbe(target, cls, targetHealth, rollbackRoots, LEGACY_DIRECT_KINDS,
                DIRECT_PROBE_RETRY_LEGACY, DIRECT_WRITER_LEGACY)) return true;

        installMethodBridgeOnce(cls);
        ProtocolMethodProbe.BridgeSpec spec = ProtocolMethodProbe.getSpec(cls.getName().replace('.', '/'));
        if (spec != null) {
            if (ProtocolMethodProbe.invokeTrustedBridge(target, spec, targetHealth)) return true;
            if (ProtocolMethodProbe.invokeBridge(target, spec, targetHealth, rollbackRoots)) return true;
        }

        // 第二阶段：在 HeadBridge 之后探测扩展候选
        return runDirectProbe(target, cls, targetHealth, rollbackRoots, EXTENDED_DIRECT_KINDS,
                DIRECT_PROBE_RETRY_EXTENDED, DIRECT_WRITER_EXTENDED);
    }

    /* DirectCall 优先使用缓存；缓存缺失且冷却结束后，按候选类型执行行为探测。
       写入失败会清除缓存并重新进入冷却，以限制探测频率并允许后续恢复。 */
    private static boolean runDirectProbe(LivingEntity target, Class<?> cls, float targetHealth,
                                          List<Object> rollbackRoots, Set<ProtocolMethodProbe.WriterKind> kinds,
                                          Map<Class<?>, Long> probeRetryAfter,
                                          Map<Class<?>, ProtocolMethodProbe.DirectWriter> writerCache) {
        ProtocolMethodProbe.DirectWriter writer = writerCache.get(cls);
        if (writer == null) {
            Long retryAfter = probeRetryAfter.get(cls);
            if (retryAfter == null || System.nanoTime() - retryAfter >= 0L) {
                List<ProtocolMethodProbe.DirectCandidate> candidates =
                        filterByKinds(ProtocolMethodProbe.findDirectCandidates(cls), kinds);
                writer = ProtocolMethodProbe.resolveDirect(target, candidates, targetHealth, rollbackRoots);
                if (writer != null) {
                    writerCache.put(cls, writer);
                    probeRetryAfter.remove(cls);
                } else {
                    probeRetryAfter.put(cls, System.nanoTime() + PROBE_RETRY_COOLDOWN_NANOS);
                    EcaLogger.info("[ProtocolMethodProbe] no direct writer entity={} kinds={} (cooling down {}s)",
                            cls.getName(), kinds, PROBE_RETRY_COOLDOWN_NANOS / 1_000_000_000L);
                }
            }
        }
        if (writer == null) return false;
        ProtocolStateSnapshot snapshot = ProtocolStateSnapshot.captureProbe(target, rollbackRoots);
        float anchorBefore = readHealthAnchor(target);
        // 带死亡语义：target≤0 是斩杀意图，writer 会把血量 clamp 到≥0(实际写成 0)，故实读≤0 即成功，
        // 不能拿负 target 做容差匹配(否则 |0-(-75)| 恒超容差，斩杀永远误判失败)。
        // 快速改血时存储写入/读值可能瞬时偏差，重试几次再判失败，避免一次偏差就丢缓存进冷却。
        boolean wrote = false;
        float actual = Float.NaN;
        for (int attempt = 0; attempt < 3; attempt++) {
            wrote = writer.write(target, targetHealth);
            if (wrote) noteAnchorResponse(target, anchorBefore, targetHealth);
            actual = readHealthAnchor(target);
            if (wrote && ProtocolValueSemantics.matchesWithDeathSemantics(actual, targetHealth)) {
                ProtocolVerificationManager.registerStateRollback(target, snapshot);
                return true;
            }
        }
        // 记录 writer、目标值、写入结果和读取值，用于区分写入失败与校验读取不一致
        EcaLogger.info("[ProtocolMethodProbe] direct write failed entity={} writer={} target={} wrote={} actual={}",
                cls.getName(), writer.describe(), targetHealth, wrote, actual);
        snapshot.restore();
        writerCache.remove(cls, writer);
        probeRetryAfter.put(cls, System.nanoTime() + PROBE_RETRY_COOLDOWN_NANOS);
        return false;
    }

    // 按候选形态过滤(findDirectCandidates 已按 kind 排好序，过滤保序)
    private static List<ProtocolMethodProbe.DirectCandidate> filterByKinds(List<ProtocolMethodProbe.DirectCandidate> all,
                                                                   Set<ProtocolMethodProbe.WriterKind> kinds) {
        List<ProtocolMethodProbe.DirectCandidate> out = new ArrayList<>(all.size());
        for (ProtocolMethodProbe.DirectCandidate candidate : all) {
            if (kinds.contains(candidate.kind())) out.add(candidate);
        }
        return out;
    }

    /* 每个类只安装一次 HeadBridge，预热与运行期分析共用安装状态。
       强制兼容模式或配置关闭时不登记 spec，也不执行 retransform。 */
    public static void installMethodBridgeOnce(Class<?> cls) {
        if (cls == null) return;
        if (EcaConfiguration.getForceCompatibilityModeSafely()
                || !EcaConfiguration.getAttackEnableRadicalLogicSafely()) return;
        if (METHOD_BRIDGE_INSTALLED.add(cls)) ProtocolMethodProbe.installBridge(cls);
    }

    /* 符号反演失败后只从权威候选本身下降，禁止退化为整个实体对象图扫描。 */
    public static boolean applyNumericInversion(LivingEntity target, float targetHealth) {
        if (target == null) return false;
        Class<?> cls = target.getClass();
        boolean radical = EcaConfiguration.getAttackEnableRadicalLogicSafely();
        boolean enabled = radical;
        if (!radical || !enabled) {
            dumpNumericInversionSkip(cls, "gate closed (radical=" + radical + " numericInversion=" + enabled + ")");
            return false;
        }
        ProtocolDataflowAnalyzer.ProtocolGraphResult graph = PROTOCOL_ANALYZER.protocolGraph(cls);
        if (graph.authorityBranches().isEmpty()) {
            dumpNumericInversionSkip(cls, "protocol graph has no authority candidate");
            return false;
        }
        for (ProtocolDataflowAnalyzer.AuthorityBranch branch : graph.authorityBranches()) {
            if (ProtocolNumericInverter.searchAuthority(target, targetHealth, branch.authority())) return true;
        }
        return false;
    }

    // 数值反演前置跳过诊断：每类每原因只打一次
    private static void dumpNumericInversionSkip(Class<?> cls, String reason) {
        if (NUMERIC_INVERSION_SKIP_DUMPED.add(cls.getName() + "|" + reason))
            EcaLogger.info("[ProtocolNumericInverter] skipped entity={} reason={}", cls.getName(), reason);
    }

    // 无现成分析树时的重载：先解析分析树，再收集回滚根
    private static List<Object> collectRollbackRoots(LivingEntity target) {
        ProtocolDataflowAnalyzer.AnalysisResult tree = resolveTree(target.getClass());
        if (tree == ProtocolDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED) return List.of();
        return collectRollbackRoots(tree, target);
    }

    private static List<Object> collectRollbackRoots(ProtocolDataflowAnalyzer.AnalysisResult tree,
                                                     LivingEntity target) {
        if (tree == null || tree == ProtocolDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED) {
            return List.of();
        }
        return ProtocolDataflowAnalyzer.collectDeadEndRoots(
                tree.returnExpr, ProtocolDataflowAnalyzer.newContext(target));
    }

    private static ProtocolDataflowAnalyzer.AnalysisResult resolveTree(Class<?> cls) {
        return DATAFLOW_TABLE.computeIfAbsent(cls, LifeProtocolManager::analyzeForTable);
    }

    /* 顶层分析器统一生成静态候选和底层执行树；本层只安装运行期桥并记录诊断。 */
    private static ProtocolDataflowAnalyzer.AnalysisResult analyzeForTable(Class<?> cls) {
        LifeProtocolAnalyzer.AnalysisResult analysis = PROTOCOL_ANALYZER.analyze(cls);
        STATIC_ANALYSES.put(cls, analysis);
        if (analysis.status() != LifeProtocolAnalyzer.AnalysisResult.Status.AMBIGUOUS
                || analysis.candidates().isEmpty()) {
            if (!isWarmupDiagnosticsSuppressed()) {
                EcaLogger.info("[LifeProtocol] static analysis entity={} status={} diagnostic={}",
                        cls.getName(), analysis.status(), analysis.diagnostic());
            }
            return ProtocolDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED;
        }
        ProtocolDataflowAnalyzer.AnalysisResult ar = PROTOCOL_ANALYZER.dataflowTree(cls);
        if (!isWarmupDiagnosticsSuppressed()) {
            EcaLogger.info("[LifeProtocol] static analysis entity={} status={} definingClass={} candidates={}",
                    cls.getName(), analysis.status(),
                    ar.definingClass != null ? ar.definingClass.getName() : "null",
                    analysis.candidates().size());
        }
        ProtocolConstantOverride.install(ar);
        return ar;
    }

    /* 后台预热入口：FMLLoadComplete 在所有 ECA 字节码处理之后调用。
       复用常驻分析执行器(后台单线程)，避免阻塞主加载线程；纯分析只读，离开主线程安全。
       强制兼容模式下跳过预热——转换已全部禁止，数据流表无需预填。 */
    public static void startWarmup() {
        if (EcaConfiguration.getForceCompatibilityModeSafely()) return;
        ANALYSIS_EXECUTOR.submit(LifeProtocolManager::warmupAll);
    }

    /* 遍历已加载的 LivingEntity 子类(排除 Player 与抽象类)，逐个分析填表。
       晚加载的实体类不在此列，仍由 setHealth 时惰性补分析(computeIfAbsent 与本线程互不冲突)。 */
    private static void warmupAll() {
        long startNanos = System.nanoTime();
        AtomicInteger analyzed = new AtomicInteger();
        boolean enumerated = false;
        WARMUP_DIAGNOSTICS_SUPPRESSED.set(true);
        try {
            enumerated = EcaTransformerManager.forEachLoadedClass(clazz -> {
                warmupClass(clazz, analyzed);
            });
            if (!enumerated) {
                enumerated = EcaTransformerManager.forEachLoadedInternalName(info -> {
                    if (info == null || !info.modifiable() || !info.livingEntity()) return;
                    Class<?> clazz = ProtocolDataflowAnalyzer.loadClass(info.internalName());
                    if (clazz == null) return;
                    warmupClass(clazz, analyzed);
                });
            }
        } finally {
            WARMUP_DIAGNOSTICS_SUPPRESSED.remove();
        }
        // 预热耗时决定运行期惰性分析要顶多久；枚举失败(analyzed=0)也必须能看出来
        EcaLogger.info("[HealthDataflow] warmup done enumerated={} analyzed={} elapsedMs={}",
                enumerated, analyzed.get(), (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static void warmupClass(Class<?> clazz, AtomicInteger analyzed) {
        if (clazz == null) return;
        if (!LivingEntity.class.isAssignableFrom(clazz)) return;
        if (Player.class.isAssignableFrom(clazz)) return;
        if (Modifier.isAbstract(clazz.getModifiers())) return;
        if (DATAFLOW_TABLE.containsKey(clazz)) return;
        try {
            /* 只做数据流逆向与桥接安装：二者挡的是运行期首次改血在服务器线程上的同步分析。
               语义交集改由实体加入世界时触发，按已加载类顺序盲扫命中率过低。 */
            resolveTree(clazz);
            installMethodBridgeOnce(clazz);
            analyzed.incrementAndGet();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            if (!isWarmupDiagnosticsSuppressed())
                EcaLogger.info("[HealthDataflow] warmup analyze {} failed: {}", clazz.getName(), t.toString());
        }
    }

    static boolean isWarmupDiagnosticsSuppressed() {
        return WARMUP_DIAGNOSTICS_SUPPRESSED.get();
    }

    /* ==================== 校验 ==================== */

    // 绕过 ECA 禁疗和血量锁定读取血量，避免这些 hook 影响校验；异常或非有限值返回 NaN
    public static float safeGetHealth(LivingEntity target) {
        try {
            LivingEntityHook.beginRawHealthRead();
            float h;
            try {
                h = target.getHealth();
            } finally {
                LivingEntityHook.endRawHealthRead();
            }
            return Float.isFinite(h) ? h : Float.NaN;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return Float.NaN;
        }
    }

    // 校验改血是否生效：观测锚点落在目标值容差内，且锚点本身能反映写入
    public static boolean verify(LivingEntity target, float targetHealth) {
        float actual = readHealthAnchor(target);
        if (!Float.isFinite(actual)) return false;
        if (!ProtocolValueSemantics.matches(actual, targetHealth)) return false;
        return isAnchorTrustworthy(target);
    }

    // 语义交集专用实读校验(带死亡语义：target≤0 需实读血量≤0，正值走容差匹配)。
    // 语义交集的符号表达式可能在存储未更新时通过校验，因此还需检查观测锚点
    public static boolean verifySemanticRaw(LivingEntity target, float targetHealth) {
        float actual = readHealthAnchor(target);
        if (!Float.isFinite(actual)) return false;
        if (targetHealth <= 0.0f) {
            return ProtocolValueSemantics.matchesWithDeathSemantics(actual, targetHealth)
                    && isAnchorTrustworthy(target);
        }
        return verify(target, targetHealth);
    }

    /* ==================== 锚点可信度 ==================== */

    /* 锚点是否确实随写入变化，按实体类缓存。恒返回常量的 getHealth 证明不了任何写入生效，
       凡是目标值恰好等于该常量的改血都会无条件通过校验。
       注意"不跟随原版字段"不等于"不可信"——读自定义存储的真实 getHealth 同样不跟随，
       故此处只是初始弱取证，真正的证据由 promoteAnchorTrust 在观察到联动后补齐。 */
    private static final Map<Class<?>, Boolean> ANCHOR_REFLECTS_WRITES = new ConcurrentHashMap<>();
    private static final Set<String> ANCHOR_TRUST_DUMPED = ConcurrentHashMap.newKeySet();

    /* 探测本身要写原版血量，混在通道事务里会污染快照，故须在任何写入之前先把结论预热进缓存。 */
    public static void warmAnchorTrust(LivingEntity target) {
        if (target != null) isAnchorTrustworthy(target);
    }

    /* 观测到锚点随两个不同写入分别读回对应值时提升为可信。这比原版字段探测强：
       写入经由目标自身的 writer 驱动，直接证明了锚点反映真实存储。 */
    public static void promoteAnchorTrust(Class<?> cls) {
        if (cls != null) ANCHOR_REFLECTS_WRITES.put(cls, Boolean.TRUE);
    }

    /* 单次写入的取证形式：锚点读数从 before 位移到 expected，即证明它反映本次写入。
       两者本就相近时取不到位移，不作判定——没有证据不等于反证。 */
    public static void noteAnchorResponse(LivingEntity target, float before, float expected) {
        if (target == null || !Float.isFinite(before) || !Float.isFinite(expected)) return;
        if (ProtocolValueSemantics.matches(before, expected)) return;
        float actual = readHealthAnchor(target);
        if (ProtocolValueSemantics.matches(actual, expected)) {
            promoteAnchorTrust(target.getClass());
        }
    }

    /* 锚点能否作为写入生效的证据。替代锚点由建模阶段的结构判据裁决过，直接放行；
       其余先做一次原版字段联动的弱取证。 */
    private static boolean isAnchorTrustworthy(LivingEntity target) {
        if (target == null) return false;
        Class<?> cls = target.getClass();
        if (hasHealthAnchor(cls)) return true;
        Boolean cached = ANCHOR_REFLECTS_WRITES.get(cls);
        if (cached != null) return cached;
        boolean tracks = probeVanillaHealthTracking(target);
        ANCHOR_REFLECTS_WRITES.put(cls, tracks);
        if (!tracks && ANCHOR_TRUST_DUMPED.add(cls.getName())) {
            EcaLogger.info("[HealthAnchor] getHealth did not follow vanilla write entity={} (awaiting stronger evidence)",
                    cls.getName());
        }
        return tracks;
    }

    /* 写入两个不同的原版血量哨兵，比较 getHealth 读数是否随之变化；不变即为脱钩。
       上限不可用时(诱饵 getMaxHealth 常返回 0)退回固定哨兵。无论结果如何都写回原值。 */
    private static boolean probeVanillaHealthTracking(LivingEntity target) {
        try {
            SynchedEntityData data = target.getEntityData();
            Float snapshot = data.get(LivingEntity.DATA_HEALTH_ID);
            float max = target.getMaxHealth();
            boolean bounded = Float.isFinite(max) && max > 2.0f;
            float low = bounded ? max * 0.25f : 3.0f;
            float high = bounded ? max * 0.75f : 9.0f;
            try {
                data.set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(low));
                float readLow = safeGetHealth(target);
                data.set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(high));
                float readHigh = safeGetHealth(target);
                if (!Float.isFinite(readLow) || !Float.isFinite(readHigh)) return false;
                return Math.abs(readHigh - readLow) > 1.0E-4f;
            } finally {
                data.set(LivingEntity.DATA_HEALTH_ID, snapshot);
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    /* ==================== 血量观测锚点 ==================== */

    /* 校验默认读取 getHealth。若 getHealth 与血量存储解耦，则使用分析器注册的替代锚点，
       防止有效写入因观测值不变而被回滚。 */
    /* 注册实体类的替代观测锚点；anchor 为 null 表示恢复默认的 getHealth 锚点。 */
    public static void registerHealthAnchor(Class<?> cls, ProtocolRuntimeModel.Observation anchor) {
        if (cls == null) return;
        ProtocolRuntimeModel model = ProtocolRuntimeModel.forClass(cls);
        model.setObservation(anchor, ProtocolRuntimeModel.ObservationOrigin.EXTERNAL);
    }

    private static void registerEffectiveHealthAnchor(Class<?> cls, ProtocolRuntimeModel.Observation anchor) {
        ProtocolRuntimeModel model = ProtocolRuntimeModel.forClass(cls);
        if (model != null) model.setObservation(anchor, ProtocolRuntimeModel.ObservationOrigin.EFFECTIVE_HEALTH);
    }

    public static boolean hasHealthAnchor(Class<?> cls) {
        ProtocolRuntimeModel model = ProtocolRuntimeModel.forClass(cls);
        return model != null && model.observation() != null;
    }

    /* 读锚点当前值：已注册替代锚点的类走替代量，其余回落 getHealth 原始读。异常/非有限值返回 NaN。 */
    public static float readHealthAnchor(LivingEntity target) {
        if (target == null) return Float.NaN;
        ProtocolRuntimeModel model = ProtocolRuntimeModel.forClass(target.getClass());
        ProtocolRuntimeModel.Observation anchor = model == null ? null : model.observation();
        if (anchor == null) return safeGetHealth(target);
        try {
            float value = anchor.read(target);
            return Float.isFinite(value) ? value : Float.NaN;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return Float.NaN;
        }
    }

    /* ==================== 观测口解耦证据 ==================== */

    /* 记录写入成功但锚点读数不变的源，用于识别观测值与存储解耦的实体类。
       已经校验成功的类不再收集此类记录，避免把非血量源加入候选。 */
    private static final Map<Class<?>, Map<String, ProtocolDataflowAnalyzer.Source>> UNOBSERVED_WRITES =
            new ConcurrentHashMap<>();
    private static final Set<Class<?>> ANCHOR_OBSERVED = ConcurrentHashMap.newKeySet();
    private static final Set<String> UNOBSERVED_DUMPED = ConcurrentHashMap.newKeySet();

    /* 写入成功、校验失败并完成回滚后记录该源。
       sink 为 null 表示多源联写场景(无单一归属源)，只记诊断不进候选集。 */
    static void recordUnobservedWrite(Class<?> cls, ProtocolDataflowAnalyzer.Source sink, String sinkLabel) {
        if (cls == null || sinkLabel == null || ANCHOR_OBSERVED.contains(cls)) return;
        if (sink != null) {
            UNOBSERVED_WRITES.computeIfAbsent(cls, k -> new ConcurrentHashMap<>()).putIfAbsent(sinkLabel, sink);
        }
        if (!isWarmupDiagnosticsSuppressed() && UNOBSERVED_DUMPED.add(cls.getName() + "|" + sinkLabel)) {
            EcaLogger.info("[HealthAnchor] write not observed entity={} sink={} anchor={}",
                    cls.getName(), sinkLabel, hasHealthAnchor(cls) ? "custom" : "getHealth");
        }
    }

    /* 返回写入后未反映到观测锚点的源，供有效血量模型选择候选存储。 */
    static List<ProtocolDataflowAnalyzer.Source> unobservedSinks(Class<?> cls) {
        Map<String, ProtocolDataflowAnalyzer.Source> sinks = cls == null ? null : UNOBSERVED_WRITES.get(cls);
        return sinks == null ? List.of() : List.copyOf(sinks.values());
    }

    /* 校验通过后标记该类的观测锚点与存储联动，不再收集解耦记录。 */
    static void recordObservedWrite(Class<?> cls) {
        if (cls == null) return;
        if (ANCHOR_OBSERVED.add(cls)) UNOBSERVED_WRITES.remove(cls);
    }

    /* 延迟复查发现写入被实体自身逻辑改回：解除该类"已验证可写"的两处闭锁，
       使后续改血重新收集解耦证据、并允许重新裁决有效血量模型。
       不撤销模型与桥接本身——值没留住说明防护把它改回去了，不说明存储定位错了。 */
    static void onDelayedRollback(Class<?> cls, float targetHealth) {
        if (cls == null) return;
        ResolvedProtocol resolved = VERIFIED_PROTOCOLS.get(cls);
        if (resolved != null) {
            REJECTED_PROTOCOLS.computeIfAbsent(cls, ignored -> new ConcurrentHashMap<>())
                    .put(new RejectionKey(resolved.capability(), TargetDomain.of(targetHealth)),
                            resolved.protocol().fingerprint().classStructure());
        }
        ANCHOR_OBSERVED.remove(cls);
        ProtocolRuntimeModel model = ProtocolRuntimeModel.forClass(cls);
        model.setEffectiveObservationConfirmed(false);
        // 下次改血时放行第三阶段，去实体之外找持有真实血量的镜像
        model.markDelayedRollbackObserved();
    }

    private static boolean isProtocolRejected(Class<?> entityClass, Capability capability, float targetHealth) {
        Map<RejectionKey, String> rejected = REJECTED_PROTOCOLS.get(entityClass);
        if (rejected == null) return false;
        RejectionKey key = new RejectionKey(capability, TargetDomain.of(targetHealth));
        String rejectedFingerprint = rejected.get(key);
        if (rejectedFingerprint == null) return false;
        String currentFingerprint = classFingerprint(entityClass);
        if (rejectedFingerprint.equals(currentFingerprint)) return true;
        rejected.remove(key, rejectedFingerprint);
        if (rejected.isEmpty()) REJECTED_PROTOCOLS.remove(entityClass, rejected);
        return false;
    }

    /* 存在写入成功但观测不到的源，且该类从未通过校验时判定为解耦，供后续通道决定是否改用替代锚点。 */
    public static boolean isHealthReadDecoupled(Class<?> cls) {
        if (cls == null || ANCHOR_OBSERVED.contains(cls)) return false;
        Map<String, ProtocolDataflowAnalyzer.Source> unobserved = UNOBSERVED_WRITES.get(cls);
        return unobserved != null && !unobserved.isEmpty();
    }

    private static LifeProtocol describeProtocol(Class<?> entityClass, Capability capability) {
        String entityInternal = entityClass.getName().replace('.', '/');
        MethodReference reader = new MethodReference(entityInternal, "getHealth", "()F",
                MethodReference.InvocationKind.VIRTUAL);
        MethodReference writer = new MethodReference(MANAGER_INTERNAL_NAME, "setHealth",
                "(Lnet/minecraft/world/entity/LivingEntity;F)Z", MethodReference.InvocationKind.STATIC);
        AccessPath entityPath = AccessPath.entity(entityInternal);

        List<ProtocolDataflowAnalyzer.Source> sources = protocolSources(entityClass, capability);
        List<StateLocation> states = new ArrayList<>();
        List<ProtocolEvidence> evidence = new ArrayList<>();
        if (capability == Capability.GET_HEALTH_DATAFLOW) {
            LifeProtocolAnalyzer.AnalysisResult analysis = STATIC_ANALYSES.get(entityClass);
            if (analysis != null) {
                for (CandidateState candidate : analysis.candidates()) {
                    if (!states.contains(candidate.location())) states.add(candidate.location());
                    evidence.addAll(candidate.evidence());
                }
            }
        } else if (capability == Capability.CAUSAL_GRAPH_TRANSACTION) {
            ProtocolDataflowAnalyzer.ProtocolGraphResult graph =
                    PROTOCOL_ANALYZER.protocolGraph(entityClass);
            for (int index = 0; index < graph.transactionSources().size(); index++) {
                states.add(toStateLocation(entityInternal, entityPath,
                        graph.transactionSources().get(index), index));
            }
        }
        if (sources.isEmpty()) {
            if (states.isEmpty()) states.add(new StateLocation.MethodState(entityPath, reader, writer, "F"));
        } else if (states.isEmpty()) {
            for (int index = 0; index < sources.size(); index++) {
                states.add(toStateLocation(entityInternal, entityPath, sources.get(index), index));
            }
        }

        SemanticEndpoint readEndpoint = new SemanticEndpoint(SemanticEndpoint.Kind.HEALTH_OBSERVATION, reader);
        SemanticEndpoint writeEndpoint = new SemanticEndpoint(SemanticEndpoint.Kind.HEALTH_MUTATION, writer);
        for (StateLocation state : states) {
            boolean hasRequiredReadEvidence = evidence.stream().anyMatch(item -> item.location().equals(state)
                    && item.direction() == ProtocolEvidence.Direction.BACKWARD_SLICE
                    && item.strength() == ProtocolEvidence.Strength.REQUIRED);
            if (!hasRequiredReadEvidence) {
                evidence.add(new ProtocolEvidence(state, readEndpoint, ProtocolEvidence.Kind.READ_DEPENDENCY,
                        ProtocolEvidence.Direction.BACKWARD_SLICE, ProtocolEvidence.Strength.REQUIRED,
                        new ProtocolEvidence.Provenance(reader, -1),
                        "The state contributes to the entity health observation."));
            }
            evidence.add(new ProtocolEvidence(state, writeEndpoint, ProtocolEvidence.Kind.WRITE_PROPAGATION,
                    ProtocolEvidence.Direction.FORWARD_PROPAGATION, ProtocolEvidence.Strength.REQUIRED,
                    new ProtocolEvidence.Provenance(writer, -1),
                    "A controlled transaction changed the health observation through this capability."));
        }

        ValueExpression target = new ValueExpression.Parameter(1, "F");
        MutationTransaction transaction = new MutationTransaction(states,
                List.of(new MutationTransaction.InvokeAction(writer, null,
                        List.of(new ValueExpression.Parameter(0,
                                "Lnet/minecraft/world/entity/LivingEntity;"), target), states)));
        List<ValidationPlan.Check> checks = new ArrayList<>();
        checks.add(new ValidationPlan.Check(ValidationPlan.Stage.IMMEDIATE_READBACK, 0, true));
        checks.add(new ValidationPlan.Check(ValidationPlan.Stage.AFTER_TICK, 1, true));
        boolean persistentSource = sources.stream()
                .anyMatch(source -> source instanceof ProtocolDataflowAnalyzer.MapEntrySource);
        if (capability == Capability.SEMANTIC_INTERSECTION
                || capability == Capability.CAUSAL_GRAPH_TRANSACTION || persistentSource) {
            checks.add(new ValidationPlan.Check(ValidationPlan.Stage.PERSISTENCE_RELOAD, 0, false));
        }

        ValueExpression authoritativeRead = new ValueExpression.Invocation(reader,
                new ValueExpression.Parameter(0, "Lnet/minecraft/world/entity/LivingEntity;"), List.of(), "F");
        String sourceSignature = sources.stream().map(source -> source.label).sorted()
                .reduce(capability.name(), (left, right) -> left + "|" + right);
        LifeProtocol.Fingerprint fingerprint = new LifeProtocol.Fingerprint(
                classFingerprint(entityClass),
                fingerprint(sourceSignature.getBytes(StandardCharsets.UTF_8)));
        List<LifeProtocol.LifecycleConstraint> lifecycleConstraints = new ArrayList<>();
        if (capability == Capability.CAUSAL_GRAPH_TRANSACTION) {
            for (StateLocation state : states) {
                lifecycleConstraints.add(new LifeProtocol.LifecycleConstraint(
                        LifeProtocol.Kind.TICK_REWRITES_STATE, state,
                        "The state participates in an environment-driven maintenance write."));
            }
        }
        return new LifeProtocol(entityInternal, fingerprint, authoritativeRead, states, transaction,
                new ValidationPlan(checks), lifecycleConstraints, evidence);
    }

    private static List<ProtocolDataflowAnalyzer.Source> protocolSources(Class<?> entityClass,
                                                                         Capability capability) {
        ProtocolDataflowAnalyzer.AnalysisResult result;
        if (capability == Capability.GET_HEALTH_DATAFLOW) {
            result = resolveTree(entityClass);
        } else if (capability == Capability.CAUSAL_GRAPH_TRANSACTION) {
            return PROTOCOL_ANALYZER.protocolGraph(entityClass).transactionSources();
        } else if (capability == Capability.SEMANTIC_INTERSECTION) {
            result = ProtocolDataflowAnalyzer.peekSemanticSliceResult(entityClass);
        } else {
            return List.of();
        }
        if (result == null || result == ProtocolDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED) {
            return List.of();
        }
        return result.sources;
    }

    private static StateLocation toStateLocation(String entityInternal, AccessPath entityPath,
                                                 ProtocolDataflowAnalyzer.Source source, int index) {
        String descriptor = Type.getDescriptor(source.valueType);
        if (source instanceof ProtocolDataflowAnalyzer.SynchedDataSource synched) {
            return new StateLocation.SynchedDataState(entityPath, entityInternal, null,
                    synched.accessor.getId(), descriptor);
        }
        if (source instanceof ProtocolDataflowAnalyzer.FieldChainSource fieldChain
                && !fieldChain.chain.isEmpty()) {
            AccessPath receiver = entityPath;
            for (int stepIndex = 0; stepIndex < fieldChain.chain.size() - 1; stepIndex++) {
                ProtocolDataflowAnalyzer.FieldStep step = fieldChain.chain.get(stepIndex);
                receiver = receiver.append(new AccessPath.FieldStep(
                        step.ownerInternal(), step.name(), step.desc(), false));
            }
            ProtocolDataflowAnalyzer.FieldStep field = fieldChain.chain.get(fieldChain.chain.size() - 1);
            return new StateLocation.FieldState(receiver, field.ownerInternal(), field.name(), field.desc(), false);
        }
        if (source instanceof ProtocolDataflowAnalyzer.StaticFieldSource staticField) {
            String owner = staticField.field.getDeclaringClass().getName().replace('.', '/');
            AccessPath staticPath = new AccessPath(
                    new AccessPath.Root(AccessPath.RootKind.STATIC, owner, -1), List.of());
            return new StateLocation.FieldState(staticPath, owner, staticField.field.getName(),
                    Type.getDescriptor(staticField.field.getType()), true);
        }
        if (source instanceof ProtocolDataflowAnalyzer.NbtValueSource nbtValue) {
            Object key = nbtValue.keyExpr instanceof ProtocolDataflowAnalyzer.Reference reference
                    ? reference.value() : nbtValue.keyExpr.toString();
            return new StateLocation.NbtState(entityPath,
                    new ValueExpression.Constant(key, "Ljava/lang/String;"), descriptor);
        }
        MethodReference symbolicReader = new MethodReference(entityInternal, "readProtocolState" + index,
                "()" + descriptor, MethodReference.InvocationKind.VIRTUAL);
        return new StateLocation.MethodState(entityPath, symbolicReader, null, descriptor);
    }

    private static String fingerprint(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value == null ? new byte[0] : value);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String classFingerprint(Class<?> entityClass) {
        byte[] bytes = RuntimeBytecodeProvider.get(entityClass);
        if (bytes == null) bytes = ProtocolDataflowAnalyzer.defaultClassBytes(entityClass);
        BytecodeStamp cached = BYTECODE_STAMPS.get(entityClass);
        if (cached != null && cached.bytes() == bytes) return cached.fingerprint();
        String fingerprint = fingerprint(bytes);
        BYTECODE_STAMPS.put(entityClass, new BytecodeStamp(bytes, fingerprint));
        return fingerprint;
    }

    private record ResolvedProtocol(LifeProtocol protocol, Capability capability) {
    }

    private record BytecodeStamp(byte[] bytes, String fingerprint) {
    }

    private record RejectionKey(Capability capability, TargetDomain targetDomain) {
    }

    private enum TargetDomain {
        LETHAL,
        POSITIVE;

        private static TargetDomain of(float targetHealth) {
            return targetHealth <= 0.0f ? LETHAL : POSITIVE;
        }
    }

    private enum Capability {
        VANILLA_STATE,
        CAUSAL_GRAPH_TRANSACTION,
        GET_HEALTH_DATAFLOW,
        SEMANTIC_INTERSECTION,
        METHOD_TRANSACTION,
        NUMERIC_INVERSION
    }
}


