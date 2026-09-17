package net.eca.util.health;

import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects one command-triggered health mutation trace and writes a standalone diagnostic report.
 */
public final class HealthReportManager {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Path REPORT_DIRECTORY = Paths.get("logs");
    private static final ThreadLocal<Session> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<List<Session>> BACKGROUND = new ThreadLocal<>();
    private static final Map<Long, Session> DELAYED = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Set<Session>> TRACKED = new ConcurrentHashMap<>();
    private static final int MAX_DIAGNOSTIC_LINES = 10_000;
    private static final DateTimeFormatter DETAIL_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final List<String> HEALTH_LOG_PREFIXES = List.of(
            "[HealthDataflow]", "[HealthAnchor]", "[dataflow]", "[ExternalScan]",
            "[EffectiveHealth]", "[MethodProbe]", "[NumericInverter]", "[ExternalMirror]",
            "[DelayedVerify]", "[AssociatedWriter]");

    private HealthReportManager() {}

    public static void begin(LivingEntity entity, float target) {
        if (entity == null) return;
        Session session = new Session(entity, target);
        ACTIVE.set(session);
        TRACKED.computeIfAbsent(entity.getClass(), ignored -> ConcurrentHashMap.newKeySet()).add(session);
        EcaLogger.setDiagnosticCapture(session::captureLog);
    }

    public static void recordInitialValue(LivingEntity entity, float value) {
        Session session = active(entity);
        if (session != null) session.before = value;
    }

    public static void recordAttempt(LivingEntity entity, String channel, boolean success, String detail) {
        recordAttempt(entity, channel, success, detail, true);
    }

    public static void recordAttempt(LivingEntity entity, String channel, boolean success, String detail,
                                     boolean selectWinner) {
        Session session = active(entity);
        if (session == null) return;
        session.attempts.put(channel, new Attempt(success ? "成功" : "未成功", safeDetail(detail)));
        if (success && selectWinner) {
            session.winningChannel = channel;
            if ("原版同步数据直写".equals(channel)) session.winningStorageKind = "原版同步实体数据";
        }
    }

    public static void recordSkipped(LivingEntity entity, String channel, String reason) {
        Session session = active(entity);
        if (session != null) session.attempts.put(channel, new Attempt("跳过", safeDetail(reason)));
    }

    public static void recordExternalMirror(LivingEntity entity, boolean success) {
        recordAttempt(entity, "实体外镜像联写", success, success ? "已写入候选权威并等待延迟复查" : "本次未启用或未命中");
    }

    public static void recordSuccessfulStorage(LivingEntity entity, HealthDataflowAnalyzer.Source source,
                                               boolean mirrorRedirect) {
        Session session = active(entity);
        if (session == null || source == null) return;
        session.winningStorageKind = sourceKind(source);
        session.winningConstantOverride = source instanceof HealthDataflowAnalyzer.ConstOverrideSource;
        session.winningMirrorRedirect = mirrorRedirect;
    }

    public static void recordSuccessfulStorageGroup(LivingEntity entity,
                                                    List<HealthDataflowAnalyzer.Source> sources) {
        Session session = active(entity);
        if (session == null || sources == null || sources.isEmpty()) return;
        session.winningStorageKind = sourceKinds(sources);
        session.winningConstantOverride = sources.stream().anyMatch(
                HealthDataflowAnalyzer.ConstOverrideSource.class::isInstance);
        session.winningMultiSource = sources.size() > 1;
    }

    public static void attachDelayedTicket(LivingEntity entity, DelayedHealthVerifier.Ticket ticket) {
        Session session = active(entity);
        if (session == null || ticket == null) return;
        session.ticketRevision = ticket.revision();
        session.delayedPending = true;
        session.delayedStatus = "等待下一 tick 复查";
        DELAYED.put(ticket.revision(), session);
    }

    public static Path finish(LivingEntity entity, boolean success) {
        Session session = active(entity);
        if (session == null) return null;
        ACTIVE.remove();
        EcaLogger.clearDiagnosticCapture();
        session.success = success;
        session.finished = true;
        session.after = EcaSetHealthManager.readHealthAnchor(entity);
        if (session.ticketRevision == 0L) session.delayedStatus = "无需或无法登记延迟复查";
        collectAnalysis(session, entity);
        write(session);
        cleanup(session);
        return session.path != null && Files.exists(session.path) ? session.path : null;
    }

    public static void finishWithError(LivingEntity entity, Throwable throwable) {
        Session session = active(entity);
        if (session == null) return;
        session.error = throwable == null ? "未知错误" : throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
    }

    public static void completeDelayed(DelayedHealthVerifier.Ticket ticket, String status, float actual) {
        if (ticket == null) return;
        Session session = DELAYED.remove(ticket.revision());
        if (session == null) return;
        session.delayedPending = false;
        session.delayedStatus = status;
        session.delayedActual = actual;
        write(session);
        cleanup(session);
    }

    public static boolean isCapturing(LivingEntity entity) {
        return active(entity) != null;
    }

    public static boolean isCapturing(Class<?> entityClass) {
        if (entityClass == null) return false;
        Session active = ACTIVE.get();
        if (active != null && active.entityClass == entityClass) return true;
        List<Session> background = BACKGROUND.get();
        if (background == null) return false;
        for (Session session : background) {
            if (session.entityClass == entityClass) return true;
        }
        return false;
    }

    public static Future<?> submitTracked(ExecutorService executor, Class<?> entityClass, Runnable task) {
        List<Session> sessions = trackedSessions(entityClass);
        if (sessions.isEmpty()) return executor.submit(task);
        for (Session session : sessions) session.backgroundTasks.incrementAndGet();
        try {
            return executor.submit(() -> {
                BACKGROUND.set(sessions);
                EcaLogger.setDiagnosticCapture(message -> {
                    for (Session session : sessions) session.captureLog(message);
                });
                try {
                    task.run();
                } finally {
                    EcaLogger.clearDiagnosticCapture();
                    BACKGROUND.remove();
                    for (Session session : sessions) {
                        session.backgroundTasks.decrementAndGet();
                        if (session.path != null) write(session);
                        cleanup(session);
                    }
                }
            });
        } catch (RuntimeException exception) {
            for (Session session : sessions) {
                session.backgroundTasks.decrementAndGet();
                cleanup(session);
            }
            throw exception;
        }
    }

    public static void clear() {
        ACTIVE.remove();
        BACKGROUND.remove();
        EcaLogger.clearDiagnosticCapture();
        DELAYED.clear();
        TRACKED.clear();
    }

    private static List<Session> trackedSessions(Class<?> entityClass) {
        Set<Session> sessions = entityClass == null ? null : TRACKED.get(entityClass);
        return sessions == null || sessions.isEmpty() ? List.of() : List.copyOf(sessions);
    }

    private static void cleanup(Session session) {
        if (session == null || !session.finished || session.delayedPending
                || session.backgroundTasks.get() > 0) return;
        Set<Session> sessions = TRACKED.get(session.entityClass);
        if (sessions == null) return;
        sessions.remove(session);
        if (sessions.isEmpty()) TRACKED.remove(session.entityClass, sessions);
    }

    private static Session active(LivingEntity entity) {
        Session session = ACTIVE.get();
        return session != null && entity != null && session.entityId == entity.getId()
                && session.entityUuid.equals(entity.getUUID()) ? session : null;
    }

    private static void collectAnalysis(Session session, LivingEntity entity) {
        Class<?> entityClass = entity.getClass();
        try {
            HealthDataflowAnalyzer.AnalysisResult result = HealthDataflowAnalyzer.analyze(entityClass);
            if (result == null || result.isEmpty()) {
                session.analysisKind = "无法解析";
            } else {
                session.analysisKind = switch (result.classify()) {
                    case REAL_HEALTH -> "真实血量读取";
                    case NOT_REAL_HEALTH -> "非真实血量或诱饵读取";
                    case UNRESOLVED -> "包含无法解析的计算";
                };
                session.primaryExpression = expressionShape(result.returnExpr);
                session.primarySources = sourceKinds(result.sources);
                session.primarySourceCount = result.sources.size();
                session.hasConstantOverride = containsSource(result.sources,
                        HealthDataflowAnalyzer.ConstOverrideSource.class);
                session.hasSynchedData = containsSource(result.sources,
                        HealthDataflowAnalyzer.SynchedDataSource.class);
                session.hasMapStorage = containsSource(result.sources,
                        HealthDataflowAnalyzer.MapEntrySource.class);
                session.hasNestedStorage = containsSource(result.sources,
                        HealthDataflowAnalyzer.ChainedFieldSource.class)
                        || containsSource(result.sources, HealthDataflowAnalyzer.CapabilityDataSource.class)
                        || containsSource(result.sources, HealthDataflowAnalyzer.ArrayElementSource.class);
                session.hasEncodedStorage = result.sources.stream().anyMatch(source -> source.valueType == String.class);
            }
        } catch (Throwable throwable) {
            if (throwable instanceof VirtualMachineError error) throw error;
            session.analysisKind = "分析异常：" + throwable.getClass().getSimpleName();
        }

        HealthDataflowAnalyzer.AnalysisResult external =
                HealthDataflowAnalyzer.peekExternalScanResult(entityClass);
        session.externalStatus = external == null
                ? "尚无结果（可能仍在后台分析）"
                : "已解析，来源数=" + external.sources.size() + "，结构=" + sourceKinds(external.sources);

        HealthDataflowAnalyzer.EffectiveHealthModel effective =
                HealthDataflowAnalyzer.peekEffectiveHealthModel(entityClass);
        if (effective != null) {
            session.effectiveStatus = "已建立，表达式=" + expressionShape(effective.readExpr())
                    + "，存储结构=" + sourceKind(effective.storage());
            session.hasEffectiveModel = true;
            session.hasReverseAccumulator = containsSubtract(effective.readExpr(),
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        } else {
            session.effectiveStatus = "尚未建立";
        }

        HealthDataflowAnalyzer.MaintenancePlan maintenance =
                HealthDataflowAnalyzer.peekMaintenancePlan(entityClass);
        session.maintenanceStatus = HealthDataflowAnalyzer.isMaintenancePlanResolved(entityClass)
                ? "已解析，分支=" + maintenance.branches().size()
                    + "，周期写入=" + maintenance.maintenanceWriteCount()
                    + "，实体外事务源=" + yesNo(maintenance.hasExternalTransactionSource())
                : "尚未完成";
        session.hasExternalAuthority = maintenance.hasExternalTransactionSource();
        session.hasMirrorAuthority = HealthDataflowAnalyzer.hasMirrorAuthority(entityClass);

        HealthModel model = HealthModel.forClass(entityClass);
        session.anchorStatus = EcaSetHealthManager.isAnchorUntrusted(entity)
                ? "默认观测出口已判定不可信"
                : model.observation() == null
                    ? "使用默认观测出口"
                    : "使用替代观测锚点（" + model.observationOrigin() + "）";
        session.delayedRollbackKnown = model.delayedRollbackObserved();
    }

    private static void write(Session session) {
        synchronized (session) {
            try {
                Files.createDirectories(REPORT_DIRECTORY);
                if (session.path == null) session.path = allocatePath(session);
                Files.writeString(session.path, render(session), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                EcaLogger.info("[HealthReport] write failed: {}", exception.getMessage());
            }
        }
    }

    private static Path allocatePath(Session session) {
        String base = sanitizeFileName(session.entityName) + "_health_report_"
                + session.createdAt.format(FILE_TIME);
        Path path = REPORT_DIRECTORY.resolve(base + ".txt");
        if (!Files.exists(path)) return path;
        return REPORT_DIRECTORY.resolve(base + "_" + session.entityId + ".txt");
    }

    private static String render(Session session) {
        Judgment judgment = judge(session);
        StringBuilder report = new StringBuilder(4096);
        report.append("ECA 血量分析报告\n")
                .append("生成时间: ").append(session.createdAt.format(DISPLAY_TIME)).append('\n')
                .append("实体名称: ").append(session.entityName).append('\n')
                .append("实体 UUID: ").append(session.entityUuid).append('\n')
                .append("实体运行 ID: ").append(session.entityId).append("\n\n")
                .append("=== 智能判断 ===\n")
                .append("主类型: ").append(judgment.primaryType()).append('\n')
                .append("附加特征: ").append(judgment.features()).append('\n')
                .append("有效修改模块: ").append(judgment.module()).append('\n')
                .append("结果: ").append(judgment.result()).append('\n')
                .append("置信度: ").append(judgment.confidence()).append('\n')
                .append("判断依据:\n");
        for (String evidence : judgment.evidence()) report.append("- ").append(evidence).append('\n');

        report.append("\n=== 本次改血 ===\n")
                .append("目标值: ").append(session.target).append('\n')
                .append("改前观测值: ").append(formatFloat(session.before)).append('\n')
                .append("当场改后观测值: ").append(formatFloat(session.after)).append('\n')
                .append("当场结果: ").append(session.success ? "成功" : "失败").append('\n')
                .append("成功存储结构: ").append(session.winningStorageKind).append('\n')
                .append("延迟复查: ").append(session.delayedStatus).append('\n');
        if (Float.isFinite(session.delayedActual)) {
            report.append("延迟观测值: ").append(formatFloat(session.delayedActual)).append('\n');
        }
        if (session.error != null) report.append("异常: ").append(session.error).append('\n');

        report.append("\n=== 通道执行记录 ===\n");
        appendAttempt(report, session, "原版同步数据直写", true);
        appendAttempt(report, session, "数据流逆向", EcaConfiguration.getAttackSetHealthEnableDataflowSafely());
        appendAttempt(report, session, "外部语义扫描", EcaConfiguration.getAttackSetHealthEnableExternalScanSafely());
        appendAttempt(report, session, "有效血量反演", EcaConfiguration.getAttackSetHealthEnableExternalScanSafely());
        appendAttempt(report, session, "方法探针", EcaConfiguration.getAttackSetHealthEnableMethodProbeSafely());
        appendAttempt(report, session, "数值反演", EcaConfiguration.getAttackSetHealthEnableNumericInversionSafely());
        appendAttempt(report, session, "实体外镜像联写", EcaConfiguration.getAttackSetHealthEnableExternalScanSafely());

        report.append("\n=== 结构分析 ===\n")
                .append("getHealth 分类: ").append(session.analysisKind).append('\n')
                .append("返回表达式结构: ").append(session.primaryExpression).append('\n')
                .append("可写来源数: ").append(session.primarySourceCount).append('\n')
                .append("来源结构: ").append(session.primarySources).append('\n')
                .append("观测锚点: ").append(session.anchorStatus).append('\n')
                .append("外部扫描: ").append(session.externalStatus).append('\n')
                .append("有效血量模型: ").append(session.effectiveStatus).append('\n')
                .append("周期维护模型: ").append(session.maintenanceStatus).append('\n')
                .append("已知延迟回滚: ").append(yesNo(session.delayedRollbackKnown)).append('\n')
                .append("\n=== 配置门控 ===\n")
                .append("强制兼容模式: ").append(onOff(EcaConfiguration.getForceCompatibilityModeSafely())).append('\n')
                .append("激进逻辑: ").append(onOff(EcaConfiguration.getAttackEnableRadicalLogicSafely())).append('\n')
                .append("常数覆写: ").append(onOff(EcaConfiguration.getAttackSetHealthEnableConstOverrideSafely())).append('\n')
                .append("数据流逆向: ").append(onOff(EcaConfiguration.getAttackSetHealthEnableDataflowSafely())).append('\n')
                .append("外部扫描: ").append(onOff(EcaConfiguration.getAttackSetHealthEnableExternalScanSafely())).append('\n')
                .append("方法探针: ").append(onOff(EcaConfiguration.getAttackSetHealthEnableMethodProbeSafely())).append('\n')
                .append("数值反演: ").append(onOff(EcaConfiguration.getAttackSetHealthEnableNumericInversionSafely())).append('\n');
        report.append("\n=== 详细数据流逆向过程 ===\n");
        List<String> diagnostics = session.diagnosticSnapshot();
        if (diagnostics.isEmpty()) report.append("本次未产生血量诊断日志。\n");
        else for (String diagnostic : diagnostics) report.append(diagnostic).append('\n');
        return report.toString();
    }

    private static void appendAttempt(StringBuilder report, Session session, String channel, boolean enabled) {
        Attempt attempt = session.attempts.get(channel);
        if (attempt != null) {
            report.append(channel).append(": ").append(attempt.status());
            if (!attempt.detail().isEmpty()) report.append(" — ").append(attempt.detail());
        } else if (!enabled) {
            report.append(channel).append(": 跳过 — 配置未启用");
        } else {
            report.append(channel).append(": 未执行 — 前序通道已结束流程");
        }
        report.append('\n');
    }

    private static Judgment judge(Session session) {
        List<String> features = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        String primary;

        if (session.hasConstantOverride || session.analysisKind.startsWith("非真实")) {
            primary = "诱饵观测出口型";
            evidence.add("getHealth 分析未直接指向可信的真实血量存储");
        } else if (session.hasReverseAccumulator) {
            primary = "反向累加器型自定义存储";
            evidence.add("有效血量表达式包含减法方向的存储换算");
        } else if (session.hasEffectiveModel) {
            primary = "换算型有效血量存储";
            evidence.add("已从生死语义建立可逆的有效血量表达式");
        } else if ("原版同步数据直写".equals(session.winningChannel)) {
            primary = "原版同步血量型";
            evidence.add("写入原版同步血量后，观测锚点立即匹配目标值");
        } else if (session.hasSynchedData) {
            primary = "自定义同步数据存储型";
            evidence.add("数据流来源包含同步实体数据单元");
        } else if (session.hasMapStorage || session.hasExternalAuthority) {
            primary = "外部权威存储型";
            evidence.add("分析发现映射存储或实体外事务来源");
        } else if (session.hasNestedStorage) {
            primary = "深层对象存储型";
            evidence.add("血量来源位于嵌套字段、能力容器或数组结构中");
        } else if (session.analysisKind.startsWith("真实")) {
            primary = "直接自定义存储型";
            evidence.add("getHealth 数据流可到达可写存储");
        } else {
            primary = "无法可靠判定型";
            evidence.add("现有结构证据不足以确定唯一血量模型");
        }

        if (session.primarySourceCount > 1) features.add("多来源候选");
        if (session.winningMultiSource) features.add("多源联合写入");
        if (session.hasEncodedStorage) features.add("编码值存储");
        if (session.hasMirrorAuthority || session.winningMirrorRedirect) features.add("实体内镜像覆盖");
        if (session.hasExternalAuthority) features.add("实体外权威");
        if (session.delayedRollbackKnown || session.delayedStatus.contains("回滚")) features.add("周期回写");
        if (features.isEmpty()) features.add("未发现额外防护特征");

        if (session.winningChannel != null) {
            evidence.add("本次最终由“" + session.winningChannel + "”通过当场校验");
        } else {
            evidence.add("本次没有通道通过当场校验");
        }
        if (session.delayedStatus.contains("保留") || session.delayedStatus.contains("移除")) {
            evidence.add("延迟复查确认目标值在实体 tick 后仍然有效");
        } else if (session.delayedStatus.contains("回滚")) {
            evidence.add("延迟复查发现目标值被周期逻辑改回");
        } else if (session.delayedStatus.contains("等待")) {
            evidence.add("最终持久性仍等待下一 tick 复查");
        }

        String result;
        if (!session.success) result = "修改失败或分析尚未完成";
        else if (session.delayedStatus.contains("回滚")) result = "当场成功，但下一 tick 被回滚";
        else if (session.delayedStatus.contains("等待")) result = "当场成功，等待延迟复查";
        else if (session.delayedStatus.contains("无法") || session.delayedStatus.contains("取代")
                || session.delayedStatus.contains("撤销")) result = "当场成功，但延迟复查无法给出确定结论";
        else result = "修改成功，结果已完成复查或无需复查";

        String confidence;
        if (primary.equals("无法可靠判定型")) confidence = "低";
        else if (session.delayedStatus.contains("等待") || session.delayedStatus.contains("无法")
                || session.delayedStatus.contains("取代") || session.delayedStatus.contains("撤销")) confidence = "中";
        else if (session.success) confidence = "高";
        else confidence = "中";

        String module = session.winningChannel;
        if (session.winningConstantOverride && "数据流逆向".equals(module)) module = "常数覆写";
        return new Judgment(primary, String.join("、", features),
                module == null ? "未确定" : module,
                result, confidence, evidence);
    }

    private static boolean containsSubtract(HealthDataflowAnalyzer.Expr expression,
                                            Set<HealthDataflowAnalyzer.Expr> visited) {
        if (expression == null || !visited.add(expression)) return false;
        if (expression instanceof HealthDataflowAnalyzer.Op op) {
            if (op.opcode() == Opcodes.ISUB || op.opcode() == Opcodes.LSUB
                    || op.opcode() == Opcodes.FSUB || op.opcode() == Opcodes.DSUB) return true;
            for (HealthDataflowAnalyzer.Expr arg : op.args()) {
                if (containsSubtract(arg, visited)) return true;
            }
        } else if (expression instanceof HealthDataflowAnalyzer.Choice choice) {
            for (HealthDataflowAnalyzer.Expr alternative : choice.alternatives()) {
                if (containsSubtract(alternative, visited)) return true;
            }
        } else if (expression instanceof HealthDataflowAnalyzer.StoreWrite write) {
            return containsSubtract(write.valueExpr(), visited);
        } else if (expression instanceof HealthDataflowAnalyzer.Call call) {
            for (HealthDataflowAnalyzer.Expr arg : call.args()) {
                if (containsSubtract(arg, visited)) return true;
            }
        }
        return false;
    }

    private static String expressionShape(HealthDataflowAnalyzer.Expr expression) {
        if (expression == null) return "无";
        return expression.getClass().getSimpleName();
    }

    private static String sourceKinds(List<HealthDataflowAnalyzer.Source> sources) {
        if (sources == null || sources.isEmpty()) return "无";
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (HealthDataflowAnalyzer.Source source : sources) {
            counts.merge(sourceKind(source), 1, Integer::sum);
        }
        List<String> values = new ArrayList<>();
        counts.forEach((kind, count) -> values.add(kind + (count > 1 ? "×" + count : "")));
        return String.join("、", values);
    }

    private static String sourceKind(HealthDataflowAnalyzer.Source source) {
        if (source instanceof HealthDataflowAnalyzer.ConstOverrideSource) return "常量覆写点";
        if (source instanceof HealthDataflowAnalyzer.SynchedDataSource) return "同步实体数据";
        if (source instanceof HealthDataflowAnalyzer.MapEntrySource) return "映射表条目";
        if (source instanceof HealthDataflowAnalyzer.CapabilityDataSource) return "能力容器数据";
        if (source instanceof HealthDataflowAnalyzer.ArrayElementSource) return "数组元素";
        if (source instanceof HealthDataflowAnalyzer.StaticFieldSource) return "静态字段";
        if (source instanceof HealthDataflowAnalyzer.ChainedFieldSource) return "外部对象字段链";
        if (source instanceof HealthDataflowAnalyzer.FieldChainSource) return "实体字段链";
        if (source instanceof HealthDataflowAnalyzer.MethodCallSource) return "方法调用写点";
        if (source instanceof HealthDataflowAnalyzer.MethodPropertySource) return "方法属性";
        return "其他可写来源";
    }

    private static boolean containsSource(List<HealthDataflowAnalyzer.Source> sources,
                                          Class<? extends HealthDataflowAnalyzer.Source> type) {
        return sources.stream().anyMatch(type::isInstance);
    }

    private static String sanitizeFileName(String value) {
        String sanitized = value == null ? "entity" : value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (sanitized.isEmpty()) sanitized = "entity";
        if (sanitized.length() > 48) sanitized = sanitized.substring(0, 48);
        return sanitized;
    }

    private static String safeDetail(String detail) {
        return detail == null ? "" : detail.replace('\n', ' ').replace('\r', ' ');
    }

    private static String formatFloat(float value) {
        return Float.isFinite(value) ? Float.toString(value) : "不可用";
    }

    private static String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    private static String onOff(boolean value) {
        return value ? "开启" : "关闭";
    }

    private record Attempt(String status, String detail) {}

    private record Judgment(String primaryType, String features, String module,
                            String result, String confidence, List<String> evidence) {}

    private static final class Session {
        private final LocalDateTime createdAt = LocalDateTime.now();
        private final int entityId;
        private final UUID entityUuid;
        private final Class<?> entityClass;
        private final String entityName;
        private final float target;
        private final Map<String, Attempt> attempts = new LinkedHashMap<>();
        private float before = Float.NaN;
        private float after = Float.NaN;
        private float delayedActual = Float.NaN;
        private boolean success;
        private String error;
        private String winningChannel;
        private String delayedStatus = "尚未登记";
        private long ticketRevision;
        private Path path;
        private String analysisKind = "尚未分析";
        private String primaryExpression = "无";
        private String primarySources = "无";
        private int primarySourceCount;
        private String externalStatus = "尚无结果";
        private String effectiveStatus = "尚未建立";
        private String maintenanceStatus = "尚未完成";
        private String anchorStatus = "未知";
        private boolean hasConstantOverride;
        private boolean hasSynchedData;
        private boolean hasMapStorage;
        private boolean hasNestedStorage;
        private boolean hasEncodedStorage;
        private boolean hasEffectiveModel;
        private boolean hasReverseAccumulator;
        private boolean hasExternalAuthority;
        private boolean hasMirrorAuthority;
        private boolean delayedRollbackKnown;
        private boolean winningConstantOverride;
        private boolean winningMultiSource;
        private boolean winningMirrorRedirect;
        private String winningStorageKind = "未确定";
        private final AtomicInteger backgroundTasks = new AtomicInteger();
        private final List<String> diagnostics = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean finished;
        private volatile boolean delayedPending;
        private volatile boolean diagnosticLimitReached;

        private Session(LivingEntity entity, float target) {
            this.entityId = entity.getId();
            this.entityUuid = entity.getUUID();
            this.entityClass = entity.getClass();
            this.entityName = entity.getName().getString();
            this.target = target;
        }

        private void captureLog(String message) {
            if (message == null || HEALTH_LOG_PREFIXES.stream().noneMatch(message::startsWith)) return;
            synchronized (diagnostics) {
                if (diagnostics.size() >= MAX_DIAGNOSTIC_LINES) {
                    if (!diagnosticLimitReached) {
                        diagnosticLimitReached = true;
                        diagnostics.add("[HealthReport] 详细诊断已达到行数上限，后续内容省略");
                    }
                    return;
                }
                diagnostics.add("[" + LocalDateTime.now().format(DETAIL_TIME) + "] ["
                        + Thread.currentThread().getName() + "] " + message);
            }
        }

        private List<String> diagnosticSnapshot() {
            synchronized (diagnostics) {
                return List.copyOf(diagnostics);
            }
        }
    }
}
