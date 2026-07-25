package net.eca.util.health;

import net.eca.config.EcaConfiguration;
import net.eca.coremod.EcaTransformerManager;
import net.eca.coremod.LivingEntityHook;
import net.eca.util.EcaLogger;
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

    /* 外部扫描已装 patch 的类：外部扫描缓存在分析器内(须零副作用)，故 install 的每类去重在管理器侧记 */
    private static final Set<Class<?>> EXTERNAL_INSTALLED = ConcurrentHashMap.newKeySet();

    /* 改血分析专用后台执行器：常驻单守护线程，承接 LoadComplete 预热与运行期外部扫描懒加载。
       外部扫描逆向 hurt/actuallyHurt 会内联进重整合包大量类，可达数秒——放到本线程跑，服务器线程永不阻塞。 */
    private static final ExecutorService ANALYSIS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ECA-Health-Analysis");
        t.setDaemon(true);
        return t;
    });

    /* 外部扫描异步分析去重：同类并发首改只提交一次后台分析任务 */
    private static final Set<Class<?>> EXTERNAL_SCAN_PENDING = ConcurrentHashMap.newKeySet();

    /* UNRESOLVED 失败诊断去重：按 getHealth 定义类只 dump 一次 */
    private static final Set<String> UNRESOLVED_DUMPED = ConcurrentHashMap.newKeySet();

    /* 方法探针：HeadBridge 已烤入的类。DirectCall 分两段各自维护"已探测"标记 + 命中 writer 缓存(跨同类实例复用)：
       legacy = 旧版已验证的反射 setter/函数式字段，在 HeadBridge 之前探测；
       extended = 新增激进候选(MethodHandle 字段/暂存字段+提交)，在 HeadBridge 之后探测。
       两段缓存键分离，避免一段的"已探测"标记短路另一段。 */
    private static final Set<Class<?>> METHOD_BRIDGE_INSTALLED = ConcurrentHashMap.newKeySet();
    /* DirectCall 重探冷却：未找到 writer 或缓存 writer 写入失败时，记录下次允许重探的纳秒时间戳。
       既不每 tick 重探(行为探测会写探测值、有开销)，又能从瞬时失败恢复，避免"一次失败永久锁死"。 */
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

    /* 数据流改血主入口：查 DATAFLOW_TABLE，命中失败哨兵直接放弃；命中可写结构则交 HealthDataFlow 写入；
       未命中现场分析并落表。 */
    public static boolean applyDataflow(LivingEntity target, float targetHealth) {
        if (target == null) return false;
        HealthDataflowAnalyzer.AnalysisResult tree = resolveTree(target.getClass());
        if (tree == HealthDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED) return false;
        List<Object> rollbackRoots = collectRollbackRoots(tree, target);
        ObjectGraphSnapshot snapshot = ObjectGraphSnapshot.captureProbe(target, rollbackRoots);
        boolean success = HealthDataFlow.write(tree, target, targetHealth);
        if (!success) snapshot.restore();
        return success;
    }

    /* 外部扫描兜底入口：getHealth 数据流打不穿时，逆向 isAlive/isDeadOrDying/hurt/actuallyHurt 定位真实血量存储。
       双门控(激进逻辑 + 外部扫描开关)任一关闭直接放弃。分析结果只非阻塞查缓存：未就绪则提交后台异步分析并跳过本次
       (绝不阻塞服务器线程，否则会冻服数秒)，分析完成后入缓存供后续调用；就绪则 install + 写入。 */
    public static boolean applyExternalScan(LivingEntity target, float targetHealth) {
        if (target == null) return false;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()
                || !EcaConfiguration.getAttackSetHealthEnableExternalScanSafely()) return false;
        Class<?> cls = target.getClass();
        HealthDataflowAnalyzer.AnalysisResult tree = HealthDataflowAnalyzer.peekExternalScanResult(cls);
        if (tree == null) {
            submitExternalScanAnalysis(cls);
            return false;
        }
        if (EXTERNAL_INSTALLED.add(cls)) ConstOverride.install(tree);
        List<Object> rollbackRoots = collectRollbackRoots(tree, target);
        ObjectGraphSnapshot snapshot = ObjectGraphSnapshot.captureProbe(target, rollbackRoots);
        boolean success = HealthDataFlow.writeExternal(tree, target, targetHealth);
        if (!success) snapshot.restore();
        return success;
    }

    /* 死亡门控兜底：getHealth 是常量诱饵、setHealth 空操作、生死由"编码布尔字段"门控的实体(如紫悦 isDeadOrDying=decodeBool(_twilightDone)，
       die()/remove() 也 gate 其上)，改血各通道全打不穿。仅斩杀意图(target≤0)触发：用实体自身 encoder 把门控字段翻成"死亡值"，
       解码回读校验；翻正后实体自身被 gate 的 die()/remove() 解锁，由调用方 kill 流程完成击杀与掉落。 */
    public static boolean applyDeathGate(LivingEntity target, float targetHealth) {
        if (target == null || targetHealth > 0.0f) return false;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()) return false;
        Class<?> cls = target.getClass();
        HealthDataflowAnalyzer.DeathGate gate = HealthDataflowAnalyzer.analyzeDeathGate(cls);
        if (gate == null) return false;
        try {
            Object snapshot = gate.field().get(target);
            Object encoded = gate.encoder().invoke(null, gate.deathValue());
            gate.field().set(target, encoded);
            boolean dead = Boolean.TRUE.equals(gate.decoder().invoke(null, gate.field().get(target)));
            if (dead == gate.deathValue()) {
                EcaLogger.info("[DeathGate] flipped entity={} field={} deathValue={}",
                        cls.getName(), gate.field().getName(), gate.deathValue());
                return true;
            }
            gate.field().set(target, snapshot);   // 回读不符，回滚
            return false;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    /* 把外部扫描分析丢到后台执行器(去重)：完成后结果入分析器缓存，本次及分析期间的调用都跳过外部扫描通道。 */
    private static void submitExternalScanAnalysis(Class<?> cls) {
        if (EXTERNAL_SCAN_PENDING.add(cls)) {
            ANALYSIS_EXECUTOR.submit(() -> {
                try {
                    HealthDataflowAnalyzer.resolveExternalScanResult(cls);
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError e) throw e;
                } finally {
                    EXTERNAL_SCAN_PENDING.remove(cls);
                }
            });
        }
    }

    /* 方法探针兜底入口：存储直写(数据流/外部扫描)全打不穿时，借实体自身合法 writer 改血。
       双门控(激进逻辑 + 方法探针开关)任一关闭直接放弃。
       分两段、严格复刻旧版生效顺序、新增激进候选后置：
       Phase 1(旧版已验证路径)：反射 setter/函数式字段 DirectCall → HeadBridge(借实体可信帧发起 token+writer)；
       Phase 2(新增激进候选)：MethodHandle 字段 / 暂存字段+提交 DirectCall。
       激进候选的行为探测可能触发目标不可回滚的防御(如 accelerator checkEntityDecoys 的 lockTemporary)，
       若置于 HeadBridge 之前会把存储锁死、令本可成功的 HeadBridge 失效，故必须后置。 */
    public static boolean applyMethodProbe(LivingEntity target, float targetHealth) {
        if (target == null) return false;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()
                || !EcaConfiguration.getAttackSetHealthEnableMethodProbeSafely()) return false;
        Class<?> cls = target.getClass();
        List<Object> rollbackRoots = collectRollbackRoots(target);

        // Phase 1：旧版已验证路径
        if (runDirectProbe(target, cls, targetHealth, rollbackRoots, LEGACY_DIRECT_KINDS,
                DIRECT_PROBE_RETRY_LEGACY, DIRECT_WRITER_LEGACY)) return true;

        installMethodBridgeOnce(cls);
        MethodProbe.BridgeSpec spec = MethodProbe.getSpec(cls.getName().replace('.', '/'));
        if (spec != null) {
            if (MethodProbe.invokeTrustedBridge(target, spec, targetHealth)) return true;
            if (MethodProbe.invokeBridge(target, spec, targetHealth, rollbackRoots)) return true;
        }

        // Phase 2：新增激进候选，HeadBridge 之后才探测
        return runDirectProbe(target, cls, targetHealth, rollbackRoots, EXTENDED_DIRECT_KINDS,
                DIRECT_PROBE_RETRY_EXTENDED, DIRECT_WRITER_EXTENDED);
    }

    /* 单段 DirectCall 探测：查缓存 → 无缓存且过冷却期则按候选 kind 过滤后行为探测 → 命中即写入并校验。
       运行期函数对象/类指针可被替换，写入失败即失效本段缓存并进入冷却，冷却后重新探测——
       既不每 tick 重探(行为探测会写探测值)，又能从瞬时失败恢复，避免"一次失败永久锁死"。 */
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
            actual = safeGetHealth(target);
            if (wrote && Float.isFinite(actual)
                    && (targetHealth <= 0.0f ? actual <= 0.0f
                            : Math.abs(actual - targetHealth) <= Math.max(0.5f, Math.abs(targetHealth) * 0.02f))) {
                return true;
            }
        }
        // 诊断：打出 writer/目标/是否写成功/实读血量，定位"写没生效"还是"verify 读不到写入值"
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

    /* HeadBridge 每类只烤入一次(warmup 与惰性共用)。
       强制兼容模式或配置关闭时不转换(硬门——不登记 spec、不 retransform)。 */
    public static void installMethodBridgeOnce(Class<?> cls) {
        if (cls == null) return;
        if (EcaConfiguration.getForceCompatibilityModeSafely()
                || !EcaConfiguration.getAttackEnableRadicalLogicSafely()
                || !EcaConfiguration.getAttackSetHealthEnableMethodProbeSafely()) return;
        if (METHOD_BRIDGE_INSTALLED.add(cls)) MethodProbe.installBridge(cls);
    }

    /* 数值反演兜底入口：前面通道全打不穿(通常卡在自定义非可逆解码)时，从数据流逆向的死角活对象继续向下扰动原始 cell。
       双门控(激进逻辑 + 数值反演开关)任一关闭直接放弃；无可用死角(树失败/无死角根)直接返回 false。 */
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

    // 查表：命中返回结构或失败哨兵；未命中现场分析(归一化为 2 态)并原子落表
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

    /* 分析并归一化为 2 态：可写结构(DATAFLOW 或 CONST_OVERRIDE 带可写源) / DATA_FLOW_ANALYZER_FAILED。
       异常、空结果、无可写源形态(无源 CONST_OVERRIDE/UNRESOLVED)统一记失败，避免后续重复分析。 */
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
        boolean eligible = kind == HealthDataflowAnalyzer.AnalysisResult.Kind.DATAFLOW
                || (kind == HealthDataflowAnalyzer.AnalysisResult.Kind.CONST_OVERRIDE && !ar.sources.isEmpty());
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
        WARMUP_DIAGNOSTICS_SUPPRESSED.set(true);
        try {
            AtomicInteger analyzed = new AtomicInteger();
            boolean enumerated = EcaTransformerManager.forEachLoadedClass(clazz -> {
                warmupClass(clazz, analyzed);
            });
            if (!enumerated) {
                boolean internalEnumerated = EcaTransformerManager.forEachLoadedInternalName(info -> {
                    if (info == null || !info.modifiable() || !info.livingEntity()) return;
                    Class<?> clazz = HealthDataflowAnalyzer.loadClass(info.internalName());
                    if (clazz == null) return;
                    warmupClass(clazz, analyzed);
                });
                if (!internalEnumerated) return;
            }
        } finally {
            WARMUP_DIAGNOSTICS_SUPPRESSED.remove();
        }
    }

    private static void warmupClass(Class<?> clazz, AtomicInteger analyzed) {
        if (clazz == null) return;
        if (!LivingEntity.class.isAssignableFrom(clazz)) return;
        if (Player.class.isAssignableFrom(clazz)) return;
        if (Modifier.isAbstract(clazz.getModifiers())) return;
        if (DATAFLOW_TABLE.containsKey(clazz)) return;
        try {
            HealthDataflowAnalyzer.AnalysisResult tree = resolveTree(clazz);
            // 数据流打不穿的类才需要外部扫描，趁预热在后台把它分析掉，避免运行期首改再触发异步等待
            if (tree == HealthDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED) {
                HealthDataflowAnalyzer.resolveExternalScanResult(clazz);
            }
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

    // 安全读取目标当前"真实"血量：置原始读旁路，放行 ECA 自家禁疗/血锁 hook，避免 verify 被自身锁定值劫持。异常/非有限值返回 NaN
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

    // 校验改血是否生效：getHealth 落在目标值容差内
    public static boolean verify(LivingEntity target, float targetHealth) {
        float actual = safeGetHealth(target);
        if (!Float.isFinite(actual)) return false;
        float tolerance = Math.max(0.5f, Math.abs(targetHealth) * 0.02f);
        return Math.abs(actual - targetHealth) <= tolerance;
    }

    // 外部扫描专用 RAW 实读校验(带死亡语义：target≤0 需实读血量≤0，正值走容差匹配)。
    // 专治 ConstOverride "改读不改存储"的伪成功——符号表达式代进覆写值算得过，但真实 getHealth 没变就判失败，
    // 与数据流通道对 CO 源的 RAW 校验对齐，让链放给方法探针真写。
    public static boolean verifyExternalRaw(LivingEntity target, float targetHealth) {
        float actual = safeGetHealth(target);
        if (!Float.isFinite(actual)) return false;
        if (targetHealth <= 0.0f) return actual <= 0.0f;
        return verify(target, targetHealth);
    }
}
