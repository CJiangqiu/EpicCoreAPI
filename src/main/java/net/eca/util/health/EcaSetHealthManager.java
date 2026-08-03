package net.eca.util.health;

import net.eca.config.EcaConfiguration;
import net.eca.coremod.EcaTransformerManager;
import net.eca.coremod.LivingEntityHook;
import net.eca.util.EcaLogger;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * 改血总管理器：持有数据流分析表 + 调度 + 校验。
 * 数据流逆向分析交给 HealthDataflowAnalyzer，写入交给 HealthDataFlow，本类只负责编排表 + warmup + verify。
 */
public final class EcaSetHealthManager {

    private EcaSetHealthManager() {}

    /* ==================== 数据流主表 ==================== */

    /* 数据流主表：实体类 → 2 态。成功 = 可写结构 AnalysisResult；失败 = AnalysisResult.DATA_FLOW_ANALYZER_FAILED 哨兵。
       warmup 后台预填，setHealth 时查询；未命中现场分析并写回，失败标记后续不再重复分析。 */
    private static final Map<Class<?>, HealthDataflowAnalyzer.AnalysisResult> DATAFLOW_TABLE = new ConcurrentHashMap<>();

    /* 预热专用后台执行器：常驻单守护线程，承接 LoadComplete 的全量预热。
       纯分析只读，离开主线程安全。 */
    private static final ExecutorService ANALYSIS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ECA-Health-Analysis");
        t.setDaemon(true);
        return t;
    });

    /* 外部扫描与预热使用不同线程，避免全量预热使运行期扫描长期排队。 */
    private static final ExecutorService RUNTIME_ANALYSIS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ECA-Health-ExternalScan");
        t.setDaemon(true);
        return t;
    });

    /* Tick 与 writer 都可能命中巨型方法；两条固定通道并行，避免互相吞掉时间片。 */
    private static final ExecutorService TICK_ANALYSIS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ECA-Health-TickScan");
        t.setDaemon(true);
        return t;
    });

    private static final ExecutorService WRITER_ANALYSIS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ECA-Health-WriterScan");
        t.setDaemon(true);
        return t;
    });

    /* 有效血量模型分析不依赖外部扫描结果，使用独立线程避免两类任务相互阻塞。 */
    private static final ExecutorService MODEL_ANALYSIS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ECA-Health-ModelAnalysis");
        t.setDaemon(true);
        return t;
    });

    /* 外部扫描异步分析去重：同类并发首改只提交一次后台分析任务 */
    private static final Set<Class<?>> EXTERNAL_SCAN_PENDING = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> TICK_SCAN_PENDING = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> WRITER_SCAN_PENDING = ConcurrentHashMap.newKeySet();
    private static final Set<String> MAINTENANCE_SCAN_FAILURE_DUMPED = ConcurrentHashMap.newKeySet();

    /* 外部扫描诊断去重(每类一次)：提交、开始执行、失败三个节点各自记一次。
       提交后没有开始记录表示任务仍在队列中。 */
    private static final Set<String> EXTERNAL_SCAN_SUBMIT_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> EXTERNAL_SCAN_START_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> EXTERNAL_SCAN_FAILURE_DUMPED = ConcurrentHashMap.newKeySet();
    private static final int EXTERNAL_SCAN_FAILURE_FRAMES = 12;

    /* UNRESOLVED 失败诊断去重：按 getHealth 定义类只 dump 一次 */
    private static final Set<String> UNRESOLVED_DUMPED = ConcurrentHashMap.newKeySet();

    /* 记录已安装 HeadBridge 的类。基础候选和扩展候选分别维护探测状态与 writer 缓存，
       防止一组的探测结果阻止另一组执行。 */
    private static final Set<Class<?>> METHOD_BRIDGE_INSTALLED = ConcurrentHashMap.newKeySet();
    /* 未找到 writer 或缓存写入失败后进入冷却，限制行为探测频率并允许瞬时失败后重试。 */
    private static final long PROBE_RETRY_COOLDOWN_NANOS = 5_000_000_000L;
    private static final Map<Class<?>, Long> DIRECT_PROBE_RETRY_LEGACY = new ConcurrentHashMap<>();
    private static final Map<Class<?>, MethodProbe.DirectWriter> DIRECT_WRITER_LEGACY = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Long> DIRECT_PROBE_RETRY_EXTENDED = new ConcurrentHashMap<>();
    private static final Map<Class<?>, MethodProbe.DirectWriter> DIRECT_WRITER_EXTENDED = new ConcurrentHashMap<>();

    /* 两段 DirectCall 各自允许的候选形态(与 findDirectCandidates 的 kind 对应) */
    private static final Set<MethodProbe.WriterKind> LEGACY_DIRECT_KINDS =
            Set.of(MethodProbe.WriterKind.METHOD, MethodProbe.WriterKind.FUNCTIONAL_FIELD);
    private static final Set<MethodProbe.WriterKind> EXTENDED_DIRECT_KINDS =
            Set.of(MethodProbe.WriterKind.METHOD_HANDLE_FIELD, MethodProbe.WriterKind.FIELD_COMMIT);

    /* 数值反演前置跳过诊断去重：每类每原因只打一次，避免每-tick 改血刷屏 */
    private static final Set<String> NUMERIC_INVERSION_SKIP_DUMPED = ConcurrentHashMap.newKeySet();

    /* 预热只建立缓存和安装必要桥接；诊断留给首次真实改血，避免启动期刷屏。 */
    private static final ThreadLocal<Boolean> WARMUP_DIAGNOSTICS_SUPPRESSED = ThreadLocal.withInitial(() -> false);

    /* ==================== 对外编排入口 ==================== */

    /* 查询 DATAFLOW_TABLE：失败结果直接返回，可写结果交由 HealthDataFlow；缓存缺失时执行分析并写入表。 */
    public static boolean applyDataflow(LivingEntity target, float targetHealth) {
        if (target == null) return false;
        if (!EcaConfiguration.getAttackSetHealthEnableDataflowSafely()) return false;
        HealthDataflowAnalyzer.AnalysisResult tree = resolveTree(target.getClass());
        if (tree == HealthDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED) return false;
        List<Object> rollbackRoots = collectRollbackRoots(tree, target);
        ObjectGraphSnapshot snapshot = ObjectGraphSnapshot.captureProbe(target, rollbackRoots);
        boolean success = HealthDataFlow.write(tree, target, targetHealth);
        if (!success) snapshot.restore();
        /* dataflow 写实体存储成功后，追加写实体外的 SavedData 真实权威。
           路西法这类实体：dataflow 只覆盖实体镜像 SD:48，当场 verify 通过，但真实血量
           (SavedData) 未写，下一 tick 被钳制回。ExternalScan(tick 收集)能定位 SavedData 写源，
           追加写入使真实权威与实体镜像一致。外部扫描关闭或未就绪时不阻塞 dataflow 的成功结果。 */
        tryExternalScanCoWrite(target, targetHealth);
        return success;
    }

    /* 仅当 ExternalScan 结果已缓存且含实体外写源时追加写实体外权威；未就绪则触发后台分析并放弃本次追加。
       只对 dataflow 覆盖实体镜像、真实血量在实体外(SavedData 等)的类追加，普通实体(ExternalScan 结果全是
       实体内源)跳过，避免每个改血都拖进外部扫描/有效血量建模。
       不改变 dataflow 的返回值——它是主通道，SavedData 追加是补写，失败不影响已成功的实体写入。
       applyExternalScan 内部已自带快照/回滚，此处不再嵌套捕获。 */
    private static void tryExternalScanCoWrite(LivingEntity target, float targetHealth) {
        if (target == null) return;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()
                || !EcaConfiguration.getAttackSetHealthEnableExternalScanSafely()) return;
        Class<?> cls = target.getClass();
        HealthDataflowAnalyzer.AnalysisResult external = HealthDataflowAnalyzer.peekExternalScanResult(cls);
        if (external == null) {
            submitExternalScanAnalysis(cls);
            submitComparisonPrescan(cls);
            return;
        }
        HealthDataflowAnalyzer.MaintenancePlan maintenance =
                HealthDataflowAnalyzer.peekMaintenancePlan(cls);
        if (!maintenance.hasExternalTransactionSource()
                && !HealthDataflowAnalyzer.isMaintenancePlanResolved(cls)) {
            submitMaintenanceAnalysis(cls);
            return;
        }
        if (!maintenance.hasExternalTransactionSource()) return;
        try {
            /* 只补写实体外权威(SavedData 等)：dataflow 已把实体内存储写成目标值，此处必须让
               tick 权威同 tick 也等于目标值，否则路西法的钳制会拿高水位把实体存储改回。
               逐个全写并回读自证，不能借用 applyExternalScan——它首个校验通过的 sink(常是实体内
               镜像)就返回，永远碰不到 lucifer_health2 这类真正的 tick 权威。 */
            if (HealthDataFlow.coWriteExternalAuthorities(maintenance, target, targetHealth)) {
                markExternalAuthorityWritten(cls);
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.info("[ExternalScan] co-write threw entity={} type={} msg={}",
                    cls.getName(), t.getClass().getName(), t.getMessage());
        }
    }

    /* getHealth 数据流无法定位存储时，逆向 isAlive/isDeadOrDying/hurt/actuallyHurt 定位血量存储。
       激进逻辑或外部扫描关闭时直接返回。分析结果只从缓存读取；未就绪时提交后台任务并跳过本次
       避免阻塞服务器线程；分析完成后写入缓存供后续调用，就绪后再安装并写入。 */
    public static boolean applyExternalScan(LivingEntity target, float targetHealth) {
        if (target == null) return false;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()
                || !EcaConfiguration.getAttackSetHealthEnableExternalScanSafely()) return false;
        Class<?> cls = target.getClass();
        HealthDataflowAnalyzer.AnalysisResult tree = HealthDataflowAnalyzer.peekExternalScanResult(cls);
        if (tree == null) {
            submitExternalScanAnalysis(cls);
            /* 比较表达式扫描不依赖证据，与外部扫描并行预跑。两者串行时总等待是各自耗时之和，
               并行后证据到手时表达式往往已就绪，可当场建模。 */
            submitComparisonPrescan(cls);
            return false;
        }
        List<Object> rollbackRoots = collectRollbackRoots(tree, target);
        ObjectGraphSnapshot snapshot = ObjectGraphSnapshot.captureProbe(target, rollbackRoots);
        boolean success = HealthDataFlow.writeExternal(tree, target, targetHealth);
        if (success) return true;
        snapshot.restore();
        /* 外部扫描按存储即血量处理，存储经换算才得到血量时写入值方向不对，且校验读 getHealth 也不反映。
           此处承接同一批存储，改用有效血量表达式求逆与校验；证据正是上面写入尝试刚记录下来的。 */
        return applyEffectiveHealth(target, targetHealth);
    }

    /* 外部扫描第三阶段：实体存储写对了、当场校验也过了，却在下一 tick 被改回——
       说明真实血量另有一份实体之外的镜像。仅对延迟复查检出过回滚的类启用：
       普通实体既不承担扫描开销，也不会无故去改世界存档。门控与一二阶段共用。
       beforeHealth 取写入之前的锚点读数，镜像此刻仍持有该值，是唯一可用的匹配依据。 */
    public static boolean applyExternalMirror(LivingEntity target, float beforeHealth, float targetHealth,
                                              DelayedHealthVerifier.Ticket ticket) {
        if (target == null) return false;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()
                || !EcaConfiguration.getAttackSetHealthEnableExternalScanSafely()) return false;
        HealthModel model = HealthModel.forClass(target.getClass());
        if (model == null || !model.delayedRollbackObserved()) return false;
        /* co-write 已成功写入实体外权威后仍未留住，是模组地板/钳制(路西打的 max(_,10) 等)所致，
           不是未知镜像回滚。此时再按"值吻合"破坏性扫描世界存档既定位不到真镜像，又有改坏其他
           模组世界数据的风险，直接抑制。 */
        if (EXTERNAL_AUTHORITY_WRITTEN.contains(target.getClass())) {
            if (EXTERNAL_MIRROR_SUPPRESSED_DUMPED.add(target.getClass().getName())) {
                EcaLogger.info("[ExternalMirror] suppressed entity={} reason=external authority already co-written (mod floor/clamp, not a hidden mirror)",
                        target.getClass().getName());
            }
            return false;
        }
        return ExternalMirrorWriter.write(target, beforeHealth, targetHealth, ticket);
    }

    /* co-write 成功写入实体外权威的类：其外部权威已被 ECA 直接写入，镜像阶段无需再介入。 */
    private static final Set<Class<?>> EXTERNAL_AUTHORITY_WRITTEN = ConcurrentHashMap.newKeySet();
    private static final Set<String> EXTERNAL_MIRROR_SUPPRESSED_DUMPED = ConcurrentHashMap.newKeySet();

    /* 由 tryExternalScanCoWrite 在 co-write 至少写成功一个外部权威时登记。 */
    static void markExternalAuthorityWritten(Class<?> cls) {
        if (cls != null) EXTERNAL_AUTHORITY_WRITTEN.add(cls);
    }

    /* getHealth 与实际存储解耦时，使用实体生死判定所读取的有效血量表达式作为观测锚点，
       并通过表达式反演计算存储值。仅对已有解耦记录的类启用。
       由 applyExternalScan 在其写入失败后调用，门控与之共用。 */
    private static boolean applyEffectiveHealth(LivingEntity target, float targetHealth) {
        Class<?> cls = target.getClass();
        // 校验成功会清空解耦证据，故已装锚点的类必须继续放行，否则一旦成功就再也走不进本通道
        if (!hasHealthAnchor(cls) && !isHealthReadDecoupled(cls)) return false;
        HealthDataflowAnalyzer.EffectiveHealthModel model =
                HealthDataflowAnalyzer.peekEffectiveHealthModel(cls);
        if (model == null) {
            /* 比较表达式已缓存时建模只剩遍历与打分，当场完成即可，省去一次改血往返；
               未缓存则需扫描字节码，耗时较长，仍转后台并跳过本次。 */
            if (HealthDataflowAnalyzer.hasComparisonCache(cls)) {
                model = HealthDataflowAnalyzer.resolveCachedEffectiveHealthModel(cls, unobservedSinks(cls));
            }
            if (model == null) {
                submitEffectiveModelAnalysis(cls);
                return false;
            }
        }

        HealthDataflowAnalyzer.EffectiveHealthModel resolved = model;
        /* 依赖当次伤害量的式子不是血量读取，误选它做锚点会因求解与校验共用同一表达式而恒真。
           此时必须连同已确认状态一并撤销：错误锚点一旦留下，改血将永久假成功。 */
        if (HealthDataflowAnalyzer.dependsOnDamageInput(resolved.readExpr())) {
            if (EFFECTIVE_MODEL_REJECT_DUMPED.add(cls.getName() + "|" + resolved.storage().label)) {
                EcaLogger.info("[EffectiveHealth] model rejected entity={} storage={} reason=depends on damage input",
                        cls.getName(), resolved.storage().label);
            }
            HealthModel healthModel = HealthModel.forClass(cls);
            healthModel.setEffectiveObservationConfirmed(false);
            healthModel.clearEffectiveObservation();
            HealthDataflowAnalyzer.rejectEffectiveModel(cls, resolved);
            EFFECTIVE_MODEL_SUBMITTED.remove(cls);
            return false;
        }

        /* 模型可能取自与血量反向的内部计数，按原版极性求逆会写出镜像值并当场判死。
           求解与校验共用同一表达式，方向错了两边一起错，因此必须在写入前校正。 */
        HealthDataflowAnalyzer.EffectiveHealthModel oriented =
                HealthDataflowAnalyzer.orientToAliveSide(resolved, target);
        if (oriented == null) {
            if (EFFECTIVE_MODEL_REJECT_DUMPED.add(cls.getName() + "|polarity|" + resolved.storage().label)) {
                EcaLogger.info("[EffectiveHealth] model rejected entity={} storage={} reason=reading sits on zero boundary",
                        cls.getName(), resolved.storage().label);
            }
            return false;
        }
        if (oriented != resolved && EFFECTIVE_POLARITY_DUMPED.add(cls.getName() + "|" + oriented.storage().label)) {
            EcaLogger.info("[EffectiveHealth] polarity inverted entity={} storage={} readExpr={}",
                    cls.getName(), oriented.storage().label,
                    HealthDataFlow.expressionSummary(oriented.readExpr()));
        }

        // 使用有效血量表达式校验，避免 getHealth 与存储解耦时错误接受或拒绝写入
        boolean anchorWasPresent = hasHealthAnchor(cls);
        registerEffectiveHealthAnchor(cls, entity -> {
            Object value = HealthDataflowAnalyzer.evaluate(
                    oriented.readExpr(), HealthDataflowAnalyzer.newContext(entity));
            return value instanceof Number number ? number.floatValue() : Float.NaN;
        });
        List<Object> rollbackRoots = collectRollbackRoots(target);
        ObjectGraphSnapshot snapshot = ObjectGraphSnapshot.captureProbe(target, rollbackRoots);
        boolean success = HealthDataFlow.writeEffective(oriented, target, targetHealth);
        if (success) {
            HealthModel.forClass(cls).setEffectiveObservationConfirmed(true);
            return true;
        }
        snapshot.restore();
        /* 未经成功写入确认的模型失败后立即失效，以便候选集合变化时重新分析。
           写入失败由快照回滚；已经确认的锚点不因单次失败而移除。 */
        HealthModel healthModel = HealthModel.forClass(cls);
        if (!healthModel.effectiveObservationConfirmed()) {
            if (!anchorWasPresent) {
                healthModel.clearEffectiveObservation();
            }
            HealthDataflowAnalyzer.rejectEffectiveModel(cls, oriented);
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

    /* 实体首次加入世界时按需预热外部扫描。
       按已加载类顺序盲扫命中率很低——真正会被改血的实体在被打之前必然先出现在世界里，
       以此为触发点，等玩家打到它时分析通常已完成，首次写入即可直接命中缓存。
       原版实体的 getHealth 走数据流即可写入，无需外部扫描。 */
    public static void onEntityJoinLevel(LivingEntity entity) {
        if (entity == null) return;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()
                || !EcaConfiguration.getAttackSetHealthEnableExternalScanSafely()) return;
        Class<?> cls = entity.getClass();
        if (cls.getName().startsWith("net.minecraft.")) return;
        if (!JOIN_PREWARM_SUBMITTED.add(cls)) return;
        try {
            RUNTIME_ANALYSIS_EXECUTOR.submit(() -> prewarmJoinedEntityClass(cls));
        } catch (Throwable t) {
            JOIN_PREWARM_SUBMITTED.remove(cls);
            EcaLogger.info("[ExternalScan] join prewarm submit rejected entity={} type={} msg={}",
                    cls.getName(), t.getClass().getName(), t.getMessage());
        }
    }

    /* 数据流未分析过的类先补分析，再据其形态决定是否需要外部扫描。
       写入打不穿的形态有两种：分析失败，以及 getHealth 与真实存储无关(NOT_REAL_HEALTH，
       常数/诱饵/镜像)——后者数据流分析本身是成功的，只按失败筛会把这类实体漏掉。 */
    private static void prewarmJoinedEntityClass(Class<?> cls) {
        try {
            HealthDataflowAnalyzer.AnalysisResult tree = resolveTree(cls);
            /* getHealth 定义在原版(未被模组重写)→真血就在原版存储，原版直写即可，跳过外部扫描。
               分析失败时定义类不可知，仍需外部扫描兜底；模组重写了 getHealth 的类(无论其 getHealth
               读实体内还是实体外)都要扫描，否则路西法这类"读得对但权威在实体外"的实体会被漏掉。 */
            if (tree != HealthDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED
                    && isVanillaGetHealthOwner(tree)) return;
            EcaLogger.info("[ExternalScan] join prewarm started entity={}", cls.getName());
            /* 只在高优先级队列完成四个语义入口；比较扫描和周期维护各自进入独立队列。 */
            long scanStart = System.nanoTime();
            HealthDataflowAnalyzer.resolveExternalScanResult(cls);
            long scanMs = (System.nanoTime() - scanStart) / 1_000_000L;
            EcaLogger.info("[ExternalScan] join semantic prewarm done entity={} elapsedMs={}",
                    cls.getName(), scanMs);
            submitComparisonPrescan(cls);
            submitMaintenanceAnalysis(cls);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.info("[ExternalScan] join prewarm threw entity={} type={} msg={}",
                    cls.getName(), t.getClass().getName(), t.getMessage());
        }
    }

    /* getHealth 的定义类是否在原版包：definingClass 由分析器沿层次解析到真正定义 getHealth 字节码的类，
       落在 net.minecraft.* 即表示当前实体未重写 getHealth，其真血在原版存储，无需外部扫描。 */
    private static boolean isVanillaGetHealthOwner(HealthDataflowAnalyzer.AnalysisResult tree) {
        Class<?> owner = tree.definingClass;
        return owner != null && owner.getName().startsWith("net.minecraft.");
    }

    /* 在外部扫描进行的同时预扫比较表达式，使两段耗时重叠而非相加。 */
    private static void submitComparisonPrescan(Class<?> cls) {
        if (HealthDataflowAnalyzer.hasComparisonCache(cls)) return;
        if (!COMPARISON_PRESCAN_SUBMITTED.add(cls)) return;
        try {
            MODEL_ANALYSIS_EXECUTOR.submit(() -> {
                try {
                    HealthDataflowAnalyzer.prewarmClassComparisons(cls);
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
        List<HealthDataflowAnalyzer.Source> candidates = unobservedSinks(cls);
        if (HealthDataflowAnalyzer.isEffectiveModelMiss(cls, candidates)) return;
        String signature = HealthDataflowAnalyzer.candidateSignature(candidates);
        if (signature.equals(EFFECTIVE_MODEL_SUBMITTED.put(cls, signature))) return;
        EcaLogger.info("[EffectiveHealth] model analysis submitted entity={} candidates={}",
                cls.getName(), candidates.size());
        try {
            MODEL_ANALYSIS_EXECUTOR.submit(() -> {
                try {
                    HealthDataflowAnalyzer.resolveEffectiveHealthModel(cls, candidates);
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

    /* 外部扫描在后台去重执行，完成后写入分析缓存。任务异常必须记录，
       以便区分配置关闭、分析进行中和分析失败。 */
    private static void submitExternalScanAnalysis(Class<?> cls) {
        if (!EXTERNAL_SCAN_PENDING.add(cls)) return;
        if (EXTERNAL_SCAN_SUBMIT_DUMPED.add(cls.getName())) {
            EcaLogger.info("[ExternalScan] analysis submitted entity={}", cls.getName());
        }
        try {
            RUNTIME_ANALYSIS_EXECUTOR.submit(() -> {
                try {
                    // 与 submitted 配对：只有 submitted 没有 started，说明任务卡在队列而非分析失败
                    if (EXTERNAL_SCAN_START_DUMPED.add(cls.getName())) {
                        EcaLogger.info("[ExternalScan] analysis started entity={}", cls.getName());
                    }
                    HealthDataflowAnalyzer.resolveExternalScanResult(cls);
                    submitMaintenanceAnalysis(cls);
                } catch (Throwable t) {
                    dumpExternalScanFailure(cls, t);
                    if (t instanceof VirtualMachineError e) throw e;
                } finally {
                    EXTERNAL_SCAN_PENDING.remove(cls);
                }
            });
        } catch (Throwable t) {
            // 提交被拒时必须让出占位，否则该类此后永远跳过外部扫描
            EXTERNAL_SCAN_PENDING.remove(cls);
            EcaLogger.info("[ExternalScan] analysis submit rejected entity={} type={} msg={}",
                    cls.getName(), t.getClass().getName(), t.getMessage());
        }
    }

    private static void submitMaintenanceAnalysis(Class<?> cls) {
        if (cls == null || HealthDataflowAnalyzer.isMaintenancePlanResolved(cls)) return;
        submitTickAnalysis(cls);
        submitWriterAnalysis(cls);
    }

    private static void submitTickAnalysis(Class<?> cls) {
        if (HealthDataflowAnalyzer.isTickMaintenanceResolved(cls) || !TICK_SCAN_PENDING.add(cls)) return;
        try {
            TICK_ANALYSIS_EXECUTOR.submit(() -> {
                try {
                    HealthDataflowAnalyzer.resolveTickMaintenancePlan(cls);
                } catch (Throwable t) {
                    if (MAINTENANCE_SCAN_FAILURE_DUMPED.add(cls.getName() + "|tick")) {
                        EcaLogger.info("[ExternalScan] tick analysis threw entity={} type={} msg={}",
                                cls.getName(), t.getClass().getName(), t.getMessage());
                    }
                    if (t instanceof VirtualMachineError e) throw e;
                } finally {
                    TICK_SCAN_PENDING.remove(cls);
                }
            });
        } catch (Throwable t) {
            TICK_SCAN_PENDING.remove(cls);
            EcaLogger.info("[ExternalScan] tick submit rejected entity={} type={} msg={}",
                    cls.getName(), t.getClass().getName(), t.getMessage());
        }
    }

    private static void submitWriterAnalysis(Class<?> cls) {
        if (HealthDataflowAnalyzer.isAuthorityMaintenanceResolved(cls) || !WRITER_SCAN_PENDING.add(cls)) return;
        try {
            WRITER_ANALYSIS_EXECUTOR.submit(() -> {
                try {
                    HealthDataflowAnalyzer.resolveAuthorityMaintenancePlan(cls);
                } catch (Throwable t) {
                    if (MAINTENANCE_SCAN_FAILURE_DUMPED.add(cls.getName() + "|writer")) {
                        EcaLogger.info("[ExternalScan] writer analysis threw entity={} type={} msg={}",
                                cls.getName(), t.getClass().getName(), t.getMessage());
                    }
                    if (t instanceof VirtualMachineError e) throw e;
                } finally {
                    WRITER_SCAN_PENDING.remove(cls);
                }
            });
        } catch (Throwable t) {
            WRITER_SCAN_PENDING.remove(cls);
            EcaLogger.info("[ExternalScan] writer submit rejected entity={} type={} msg={}",
                    cls.getName(), t.getClass().getName(), t.getMessage());
        }
    }

    /* 外部扫描失败时每类记录一次异常类型、消息和有限数量的栈帧。 */
    private static void dumpExternalScanFailure(Class<?> cls, Throwable t) {
        if (!EXTERNAL_SCAN_FAILURE_DUMPED.add(cls.getName())) return;
        EcaLogger.info("[ExternalScan] analysis threw entity={} type={} msg={}",
                cls.getName(), t.getClass().getName(), t.getMessage());
        StackTraceElement[] frames = t.getStackTrace();
        for (int i = 0; i < Math.min(frames.length, EXTERNAL_SCAN_FAILURE_FRAMES); i++) {
            EcaLogger.info("[ExternalScan]   at {}", frames[i]);
        }
        if (frames.length > EXTERNAL_SCAN_FAILURE_FRAMES) {
            EcaLogger.info("[ExternalScan]   ... {} more frames", frames.length - EXTERNAL_SCAN_FAILURE_FRAMES);
        }
        Throwable cause = t.getCause();
        if (cause != null) {
            EcaLogger.info("[ExternalScan]   caused by {} msg={}", cause.getClass().getName(), cause.getMessage());
        }
    }

    /* 数据流和外部扫描无法写入存储时，尝试调用实体自身的血量 writer。
       激进逻辑或方法探针关闭时直接返回。
       第一阶段依次尝试反射 setter、函数式字段和 HeadBridge；第二阶段尝试 MethodHandle 字段及暂存字段提交。
       第二阶段可能触发不可回滚的目标状态，因此必须在 HeadBridge 之后执行。 */
    public static boolean applyMethodProbe(LivingEntity target, float targetHealth) {
        if (target == null) return false;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()
                || !EcaConfiguration.getAttackSetHealthEnableMethodProbeSafely()) return false;
        Class<?> cls = target.getClass();
        List<Object> rollbackRoots = collectRollbackRoots(target);

        // 第一阶段：基础 DirectCall 候选
        if (runDirectProbe(target, cls, targetHealth, rollbackRoots, LEGACY_DIRECT_KINDS,
                DIRECT_PROBE_RETRY_LEGACY, DIRECT_WRITER_LEGACY)) return true;

        installMethodBridgeOnce(cls);
        MethodProbe.BridgeSpec spec = MethodProbe.getSpec(cls.getName().replace('.', '/'));
        if (spec != null) {
            if (MethodProbe.invokeTrustedBridge(target, spec, targetHealth)) return true;
            if (MethodProbe.invokeBridge(target, spec, targetHealth, rollbackRoots)) return true;
        }

        // 第二阶段：在 HeadBridge 之后探测扩展候选
        return runDirectProbe(target, cls, targetHealth, rollbackRoots, EXTENDED_DIRECT_KINDS,
                DIRECT_PROBE_RETRY_EXTENDED, DIRECT_WRITER_EXTENDED);
    }

    /* DirectCall 优先使用缓存；缓存缺失且冷却结束后，按候选类型执行行为探测。
       写入失败会清除缓存并重新进入冷却，以限制探测频率并允许后续恢复。 */
    private static boolean runDirectProbe(LivingEntity target, Class<?> cls, float targetHealth,
                                          List<Object> rollbackRoots, Set<MethodProbe.WriterKind> kinds,
                                          Map<Class<?>, Long> probeRetryAfter,
                                          Map<Class<?>, MethodProbe.DirectWriter> writerCache) {
        MethodProbe.DirectWriter writer = writerCache.get(cls);
        if (writer == null) {
            Long retryAfter = probeRetryAfter.get(cls);
            if (retryAfter == null || System.nanoTime() - retryAfter >= 0L) {
                List<MethodProbe.DirectCandidate> candidates =
                        filterByKinds(MethodProbe.findDirectCandidates(cls), kinds);
                writer = MethodProbe.resolveDirect(target, candidates, targetHealth, rollbackRoots);
                if (writer != null) {
                    writerCache.put(cls, writer);
                    probeRetryAfter.remove(cls);
                } else {
                    probeRetryAfter.put(cls, System.nanoTime() + PROBE_RETRY_COOLDOWN_NANOS);
                    EcaLogger.info("[MethodProbe] no direct writer entity={} kinds={} (cooling down {}s)",
                            cls.getName(), kinds, PROBE_RETRY_COOLDOWN_NANOS / 1_000_000_000L);
                }
            }
        }
        if (writer == null) return false;
        ObjectGraphSnapshot snapshot = ObjectGraphSnapshot.captureProbe(target, rollbackRoots);
        // 带死亡语义：target≤0 是斩杀意图，writer 会把血量 clamp 到≥0(实际写成 0)，故实读≤0 即成功，
        // 不能拿负 target 做容差匹配(否则 |0-(-75)| 恒超容差，斩杀永远误判失败)。
        // 快速改血时存储写入/读值可能瞬时偏差，重试几次再判失败，避免一次偏差就丢缓存进冷却。
        boolean wrote = false;
        float actual = Float.NaN;
        for (int attempt = 0; attempt < 3; attempt++) {
            wrote = writer.write(target, targetHealth);
            actual = readHealthAnchor(target);
            if (wrote && HealthValueSemantics.matchesWithDeathSemantics(actual, targetHealth)) {
                return true;
            }
        }
        // 记录 writer、目标值、写入结果和读取值，用于区分写入失败与校验读取不一致
        EcaLogger.info("[MethodProbe] direct write failed entity={} writer={} target={} wrote={} actual={}",
                cls.getName(), writer.describe(), targetHealth, wrote, actual);
        snapshot.restore();
        writerCache.remove(cls, writer);
        probeRetryAfter.put(cls, System.nanoTime() + PROBE_RETRY_COOLDOWN_NANOS);
        return false;
    }

    // 按候选形态过滤(findDirectCandidates 已按 kind 排好序，过滤保序)
    private static List<MethodProbe.DirectCandidate> filterByKinds(List<MethodProbe.DirectCandidate> all,
                                                                   Set<MethodProbe.WriterKind> kinds) {
        List<MethodProbe.DirectCandidate> out = new ArrayList<>(all.size());
        for (MethodProbe.DirectCandidate candidate : all) {
            if (kinds.contains(candidate.kind())) out.add(candidate);
        }
        return out;
    }

    /* 每个类只安装一次 HeadBridge，预热与运行期分析共用安装状态。
       强制兼容模式或配置关闭时不登记 spec，也不执行 retransform。 */
    public static void installMethodBridgeOnce(Class<?> cls) {
        if (cls == null) return;
        if (EcaConfiguration.getForceCompatibilityModeSafely()
                || !EcaConfiguration.getAttackEnableRadicalLogicSafely()
                || !EcaConfiguration.getAttackSetHealthEnableMethodProbeSafely()) return;
        if (METHOD_BRIDGE_INSTALLED.add(cls)) MethodProbe.installBridge(cls);
    }

    /* 前置通道无法处理不可逆解码时，从无法反演的运行期对象继续搜索可写数值单元。
       激进逻辑或数值反演关闭，以及没有可用对象根时，直接返回 false。 */
    public static boolean applyNumericInversion(LivingEntity target, float targetHealth) {
        if (target == null) return false;
        Class<?> cls = target.getClass();
        boolean radical = EcaConfiguration.getAttackEnableRadicalLogicSafely();
        boolean enabled = EcaConfiguration.getAttackSetHealthEnableNumericInversionSafely();
        if (!radical || !enabled) {
            dumpNumericInversionSkip(cls, "gate closed (radical=" + radical + " numericInversion=" + enabled + ")");
            return false;
        }
        HealthDataflowAnalyzer.AnalysisResult tree = resolveTree(cls);
        if (tree == HealthDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED) {
            dumpNumericInversionSkip(cls, "dataflow tree unavailable (no writable structure to frame dead-ends)");
            return false;
        }
        List<Object> roots = HealthDataflowAnalyzer.collectDeadEndRoots(
                tree.returnExpr, HealthDataflowAnalyzer.newContext(target));
        if (roots.isEmpty()) {
            dumpNumericInversionSkip(cls, "no dead-end roots (nothing non-invertible to descend into)");
            return false;
        }
        // 进入搜索：命中/失败结局由 NumericInverter 自身记录
        return NumericInverter.search(target, targetHealth, roots);
    }

    // 数值反演前置跳过诊断：每类每原因只打一次
    private static void dumpNumericInversionSkip(Class<?> cls, String reason) {
        if (NUMERIC_INVERSION_SKIP_DUMPED.add(cls.getName() + "|" + reason))
            EcaLogger.info("[NumericInverter] skipped entity={} reason={}", cls.getName(), reason);
    }

    // 无现成分析树时的重载：先解析分析树，再收集回滚根
    private static List<Object> collectRollbackRoots(LivingEntity target) {
        HealthDataflowAnalyzer.AnalysisResult tree = resolveTree(target.getClass());
        if (tree == HealthDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED) return List.of();
        return collectRollbackRoots(tree, target);
    }

    private static List<Object> collectRollbackRoots(HealthDataflowAnalyzer.AnalysisResult tree,
                                                     LivingEntity target) {
        if (tree == null || tree == HealthDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED) {
            return List.of();
        }
        return HealthDataflowAnalyzer.collectDeadEndRoots(
                tree.returnExpr, HealthDataflowAnalyzer.newContext(target));
    }

    private static HealthDataflowAnalyzer.AnalysisResult resolveTree(Class<?> cls) {
        return DATAFLOW_TABLE.computeIfAbsent(cls, EcaSetHealthManager::analyzeForTable);
    }

    /* 分析并归一化为 2 态：可写结构(REAL_HEALTH 或 NOT_REAL_HEALTH 带可写源) / DATA_FLOW_ANALYZER_FAILED。
       异常、空结果、无可写源形态(无源 NOT_REAL_HEALTH/UNRESOLVED)统一记失败，避免后续重复分析。 */
    private static HealthDataflowAnalyzer.AnalysisResult analyzeForTable(Class<?> cls) {
        HealthDataflowAnalyzer.AnalysisResult ar;
        try {
            ar = HealthDataflowAnalyzer.analyze(cls);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            if (!isWarmupDiagnosticsSuppressed())
                EcaLogger.info("[HealthDataflow] analyze {} threw {} => FAILED", cls.getName(), t.toString());
            return HealthDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED;
        }
        if (ar == null) {
            if (!isWarmupDiagnosticsSuppressed())
                EcaLogger.info("[HealthDataflow] analyze {} => null => FAILED", cls.getName());
            return HealthDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED;
        }
        HealthDataflowAnalyzer.AnalysisResult.Kind kind = ar.classify();
        boolean eligible = kind == HealthDataflowAnalyzer.AnalysisResult.Kind.REAL_HEALTH
                || (kind == HealthDataflowAnalyzer.AnalysisResult.Kind.NOT_REAL_HEALTH && !ar.sources.isEmpty());
        if (!isWarmupDiagnosticsSuppressed()) {
            EcaLogger.info("[HealthDataflow] analyze {} => kind={} definingClass={} sources={} eligible={}",
                    cls.getName(), kind,
                    ar.definingClass != null ? ar.definingClass.getName() : "null",
                    ar.sources.size(), eligible);
        }
        // 失败诊断：按 getHealth 定义类去重，打印返回表达式与各源 label，判断 10 个源是别的 mod 叠的还是 ECA 剥离残留
        if (!eligible) {
            String dc = ar.definingClass != null ? ar.definingClass.getName() : "null";
            if (!isWarmupDiagnosticsSuppressed() && UNRESOLVED_DUMPED.add(dc)) {
                EcaLogger.info("[HealthDataflow] UNRESOLVED definingClass={} sources={}", dc, ar.sources.size());
                int i = 0;
                for (HealthDataflowAnalyzer.Source s : ar.sources) {
                    EcaLogger.info("[HealthDataflow]   src#{} label={} type={} kind={}",
                            i++, s.label, s.valueType.getName(), s.getClass().getSimpleName());
                }
            }
            return HealthDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED;
        }
        // 落表即安装常数覆写 patch：warmup 与晚加载惰性两路都经此，无条件转换，运行期由配置双门控激活
        ConstOverride.install(ar);
        return ar;
    }

    /* 后台预热入口：FMLLoadComplete 在所有 ECA 字节码处理之后调用。
       复用常驻分析执行器(后台单线程)，避免阻塞主加载线程；纯分析只读，离开主线程安全。
       强制兼容模式下跳过预热——转换已全部禁止，数据流表无需预填。 */
    public static void startWarmup() {
        if (EcaConfiguration.getForceCompatibilityModeSafely()) return;
        ANALYSIS_EXECUTOR.submit(EcaSetHealthManager::warmupAll);
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
                    Class<?> clazz = HealthDataflowAnalyzer.loadClass(info.internalName());
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
               外部扫描改由实体加入世界时触发，按已加载类顺序盲扫命中率过低。 */
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
        if (!HealthValueSemantics.matches(actual, targetHealth)) return false;
        return isAnchorTrustworthy(target);
    }

    // 外部扫描专用实读校验(带死亡语义：target≤0 需实读血量≤0，正值走容差匹配)。
    // 外部扫描的符号表达式可能在存储未更新时通过校验，因此还需检查观测锚点
    public static boolean verifyExternalRaw(LivingEntity target, float targetHealth) {
        float actual = readHealthAnchor(target);
        if (!Float.isFinite(actual)) return false;
        if (targetHealth <= 0.0f) {
            return HealthValueSemantics.matchesWithDeathSemantics(actual, targetHealth)
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
        if (HealthValueSemantics.matches(before, expected)) return;
        float actual = readHealthAnchor(target);
        if (HealthValueSemantics.matches(actual, expected)) {
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
    public static void registerHealthAnchor(Class<?> cls, HealthModel.Observation anchor) {
        if (cls == null) return;
        HealthModel model = HealthModel.forClass(cls);
        model.setObservation(anchor, HealthModel.ObservationOrigin.EXTERNAL);
    }

    private static void registerEffectiveHealthAnchor(Class<?> cls, HealthModel.Observation anchor) {
        HealthModel model = HealthModel.forClass(cls);
        if (model != null) model.setObservation(anchor, HealthModel.ObservationOrigin.EFFECTIVE_HEALTH);
    }

    public static boolean hasHealthAnchor(Class<?> cls) {
        HealthModel model = HealthModel.forClass(cls);
        return model != null && model.observation() != null;
    }

    /* 读锚点当前值：已注册替代锚点的类走替代量，其余回落 getHealth 原始读。异常/非有限值返回 NaN。 */
    public static float readHealthAnchor(LivingEntity target) {
        if (target == null) return Float.NaN;
        HealthModel model = HealthModel.forClass(target.getClass());
        HealthModel.Observation anchor = model == null ? null : model.observation();
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
    private static final Map<Class<?>, Map<String, HealthDataflowAnalyzer.Source>> UNOBSERVED_WRITES =
            new ConcurrentHashMap<>();
    private static final Set<Class<?>> ANCHOR_OBSERVED = ConcurrentHashMap.newKeySet();
    private static final Set<String> UNOBSERVED_DUMPED = ConcurrentHashMap.newKeySet();

    /* 写入成功、校验失败并完成回滚后记录该源。
       sink 为 null 表示多源联写场景(无单一归属源)，只记诊断不进候选集。 */
    static void recordUnobservedWrite(Class<?> cls, HealthDataflowAnalyzer.Source sink, String sinkLabel) {
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
    static List<HealthDataflowAnalyzer.Source> unobservedSinks(Class<?> cls) {
        Map<String, HealthDataflowAnalyzer.Source> sinks = cls == null ? null : UNOBSERVED_WRITES.get(cls);
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
    static void onDelayedRollback(Class<?> cls) {
        if (cls == null) return;
        ANCHOR_OBSERVED.remove(cls);
        HealthModel model = HealthModel.forClass(cls);
        model.setEffectiveObservationConfirmed(false);
        // 下次改血时放行第三阶段，去实体之外找持有真实血量的镜像
        model.markDelayedRollbackObserved();
    }

    /* 存在写入成功但观测不到的源，且该类从未通过校验时判定为解耦，供后续通道决定是否改用替代锚点。 */
    public static boolean isHealthReadDecoupled(Class<?> cls) {
        if (cls == null || ANCHOR_OBSERVED.contains(cls)) return false;
        Map<String, HealthDataflowAnalyzer.Source> unobserved = UNOBSERVED_WRITES.get(cls);
        return unobserved != null && !unobserved.isEmpty();
    }

    /* 服务器停止时清除所有缓存与状态，确保热重载后从干净状态开始。 */
    public static void clear() {
        DATAFLOW_TABLE.clear();
        EXTERNAL_SCAN_PENDING.clear();
        TICK_SCAN_PENDING.clear();
        WRITER_SCAN_PENDING.clear();
        MAINTENANCE_SCAN_FAILURE_DUMPED.clear();
        HealthDataflowAnalyzer.clearMaintenancePlans();
        EXTERNAL_SCAN_SUBMIT_DUMPED.clear();
        EXTERNAL_SCAN_START_DUMPED.clear();
        EXTERNAL_SCAN_FAILURE_DUMPED.clear();
        UNRESOLVED_DUMPED.clear();
        METHOD_BRIDGE_INSTALLED.clear();
        DIRECT_PROBE_RETRY_LEGACY.clear();
        DIRECT_WRITER_LEGACY.clear();
        DIRECT_PROBE_RETRY_EXTENDED.clear();
        DIRECT_WRITER_EXTENDED.clear();
        NUMERIC_INVERSION_SKIP_DUMPED.clear();
        ANCHOR_REFLECTS_WRITES.clear();
        ANCHOR_TRUST_DUMPED.clear();
        ANCHOR_OBSERVED.clear();
        UNOBSERVED_WRITES.clear();
        UNOBSERVED_DUMPED.clear();
        JOIN_PREWARM_SUBMITTED.clear();
        COMPARISON_PRESCAN_SUBMITTED.clear();
        EFFECTIVE_MODEL_SUBMITTED.clear();
        EFFECTIVE_MODEL_REJECT_DUMPED.clear();
        EFFECTIVE_POLARITY_DUMPED.clear();
        EXTERNAL_AUTHORITY_WRITTEN.clear();
        EXTERNAL_MIRROR_SUPPRESSED_DUMPED.clear();
        ExternalMirrorWriter.clear();
    }
}
