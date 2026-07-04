package net.eca.util.health;

import net.eca.agent.EcaAgent;
import net.eca.config.EcaConfiguration;
import net.eca.coremod.LivingEntityHook;
import net.eca.util.EcaLogger;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /* UNRESOLVED 失败诊断去重：按 getHealth 定义类只 dump 一次 */
    private static final Set<String> UNRESOLVED_DUMPED = ConcurrentHashMap.newKeySet();

    /* 方法探针：HeadBridge 已烤入的类；DirectCall 已探测的类 + 命中 writer 缓存(跨同类实例复用) */
    private static final Set<Class<?>> METHOD_BRIDGE_INSTALLED = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> DIRECT_PROBED = ConcurrentHashMap.newKeySet();
    private static final Map<Class<?>, MethodProbe.DirectWriter> DIRECT_WRITER = new ConcurrentHashMap<>();

    /* 数值反演前置跳过诊断去重：每类每原因只打一次，避免每-tick 改血刷屏 */
    private static final Set<String> NUMERIC_INVERSION_SKIP_DUMPED = ConcurrentHashMap.newKeySet();

    /* ==================== 对外编排入口 ==================== */

    /* 数据流改血主入口：查 DATAFLOW_TABLE，命中失败哨兵直接放弃；命中可写结构则交 HealthDataFlow 写入；
       未命中现场分析并落表。 */
    public static boolean applyDataflow(LivingEntity target, float targetHealth) {
        if (target == null) return false;
        HealthDataflowAnalyzer.AnalysisResult tree = resolveTree(target.getClass());
        if (tree == HealthDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED) return false;
        return HealthDataFlow.write(tree, target, targetHealth);
    }

    /* 外部扫描兜底入口：getHealth 数据流打不穿时，逆向 isAlive/isDeadOrDying/hurt/actuallyHurt 定位真实血量存储。
       双门控(激进逻辑 + 外部扫描开关)任一关闭直接放弃；resolveExternalScanResult 已带缓存，install 每类只跑一次。 */
    public static boolean applyExternalScan(LivingEntity target, float targetHealth) {
        if (target == null) return false;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()
                || !EcaConfiguration.getAttackSetHealthEnableExternalScanSafely()) return false;
        Class<?> cls = target.getClass();
        HealthDataflowAnalyzer.AnalysisResult tree = HealthDataflowAnalyzer.resolveExternalScanResult(cls);
        if (tree == null) return false;
        if (EXTERNAL_INSTALLED.add(cls)) ConstOverride.install(tree);
        return HealthDataFlow.writeExternal(tree, target, targetHealth);
    }

    /* 方法探针兜底入口：存储直写(数据流/外部扫描)全打不穿时，借实体自身合法 writer 改血。
       双门控(激进逻辑 + 方法探针开关)任一关闭直接放弃。
       DirectCall 优先(可调用 setter，行为探测命中即缓存复用)；失败退 HeadBridge(writer 被守护，借实体可信帧发起)。 */
    public static boolean applyMethodProbe(LivingEntity target, float targetHealth) {
        if (target == null) return false;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()
                || !EcaConfiguration.getAttackSetHealthEnableMethodProbeSafely()) return false;
        Class<?> cls = target.getClass();
        List<Object> rollbackRoots = collectRollbackRoots(target);

        MethodProbe.DirectWriter writer = DIRECT_WRITER.get(cls);
        if (writer == null && DIRECT_PROBED.add(cls)) {
            writer = MethodProbe.resolveDirect(target, MethodProbe.findDirectCandidates(cls), targetHealth, rollbackRoots);
            if (writer != null) DIRECT_WRITER.put(cls, writer);
        }
        if (writer != null) {
            ObjectGraphSnapshot snapshot = ObjectGraphSnapshot.capture(target, rollbackRoots);
            if (writer.write(target, targetHealth) && verify(target, targetHealth)) return true;
            snapshot.restore();
        }

        installMethodBridgeOnce(cls);
        MethodProbe.BridgeSpec spec = MethodProbe.getSpec(cls.getName().replace('.', '/'));
        if (spec == null) return false;
        if (MethodProbe.invokeTrustedBridge(target, spec, targetHealth)) return true;
        return MethodProbe.invokeBridge(target, spec, targetHealth, rollbackRoots);
    }

    /* HeadBridge 每类只烤入一次(warmup 与惰性共用)。无条件烤入——运行期是否借桥由配置双门控 + 激活态决定。 */
    public static void installMethodBridgeOnce(Class<?> cls) {
        if (cls != null && METHOD_BRIDGE_INSTALLED.add(cls)) MethodProbe.installBridge(cls);
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
            EcaLogger.info("[HealthDataflow] analyze {} threw {} => FAILED", cls.getName(), t.toString());
            return HealthDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED;
        }
        if (ar == null) {
            EcaLogger.info("[HealthDataflow] analyze {} => null => FAILED", cls.getName());
            return HealthDataflowAnalyzer.AnalysisResult.DATA_FLOW_ANALYZER_FAILED;
        }
        HealthDataflowAnalyzer.AnalysisResult.Kind kind = ar.classify();
        boolean eligible = kind == HealthDataflowAnalyzer.AnalysisResult.Kind.DATAFLOW
                || (kind == HealthDataflowAnalyzer.AnalysisResult.Kind.CONST_OVERRIDE && !ar.sources.isEmpty());
        EcaLogger.info("[HealthDataflow] analyze {} => kind={} definingClass={} sources={} eligible={}",
                cls.getName(), kind,
                ar.definingClass != null ? ar.definingClass.getName() : "null",
                ar.sources.size(), eligible);
        // 失败诊断：按 getHealth 定义类去重，打印返回表达式与各源 label，判断 10 个源是别的 mod 叠的还是 ECA 剥离残留
        if (!eligible) {
            String dc = ar.definingClass != null ? ar.definingClass.getName() : "null";
            if (UNRESOLVED_DUMPED.add(dc)) {
                EcaLogger.info("[HealthDataflow] UNRESOLVED dump definingClass={} returnExpr={}", dc, ar.returnExpr);
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
       单条 daemon 线程跑，避免阻塞主加载线程；纯分析只读，离开主线程安全。 */
    public static void startWarmup() {
        Thread t = new Thread(EcaSetHealthManager::warmupAll, "ECA-Health-Dataflow-Warmup");
        t.setDaemon(true);
        t.start();
    }

    /* 遍历已加载的 LivingEntity 子类(排除 Player 与抽象类)，逐个分析填表。
       晚加载的实体类不在此列，仍由 setHealth 时惰性补分析(computeIfAbsent 与本线程互不冲突)。 */
    private static void warmupAll() {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            EcaLogger.info("[HealthDataflow] warmup skipped: Instrumentation unavailable");
            return;
        }
        int analyzed = 0;
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (clazz == null) continue;
            if (!LivingEntity.class.isAssignableFrom(clazz)) continue;
            if (Player.class.isAssignableFrom(clazz)) continue;
            if (Modifier.isAbstract(clazz.getModifiers())) continue;
            if (DATAFLOW_TABLE.containsKey(clazz)) continue;
            try {
                resolveTree(clazz);
                installMethodBridgeOnce(clazz);
                analyzed++;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                EcaLogger.info("[HealthDataflow] warmup analyze {} failed: {}", clazz.getName(), t.toString());
            }
        }
        EcaLogger.info("[HealthDataflow] warmup complete: analyzed {} entity classes, table size={}",
                analyzed, DATAFLOW_TABLE.size());
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
}
