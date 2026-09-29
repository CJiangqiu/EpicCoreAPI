package net.eca.util.health;

import static net.eca.util.health.HealthReportText.tr;

import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

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
import java.util.LinkedHashSet;
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
    private static final Map<UUID, SyncRequest> CLIENT_SYNC = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Set<Session>> TRACKED = new ConcurrentHashMap<>();
    private static final int MAX_DIAGNOSTIC_LINES = 10_000;
    private static final DateTimeFormatter DETAIL_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final List<String> HEALTH_LOG_PREFIXES = List.of(
            "[HealthDataflow]", "[HealthAnchor]", "[dataflow]", "[ExternalScan]",
            "[EffectiveHealth]", "[MethodProbe]", "[NumericInverter]", "[ExternalMirror]",
            "[DelayedVerify]", "[AssociatedWriter]");

    private HealthReportManager() {}

    public static void begin(LivingEntity entity, float target) {
        begin(entity, target, EcaConfiguration.getHealthReportLanguageSafely());
    }

    public static void begin(LivingEntity entity, float target, String language) {
        if (entity == null) return;
        Session session = new Session(entity, target, language);
        ACTIVE.set(session);
        TRACKED.computeIfAbsent(entity.getClass(), ignored -> ConcurrentHashMap.newKeySet()).add(session);
        EcaLogger.setDiagnosticCapture(session::captureLog);
    }

    public static void recordInitialValue(LivingEntity entity, float value) {
        Session session = active(entity);
        if (session != null) session.before = value;
    }

    public static void recordAlreadySatisfied(LivingEntity entity) {
        Session session = active(entity);
        if (session != null) session.alreadySatisfied = true;
        recordSkipped(entity, "channel.vanilla", tr("write.already_satisfied"));
    }

    public static UUID beginClientSync(LivingEntity entity) {
        UUID request = UUID.randomUUID();
        Session session = active(entity);
        if (session != null) {
            expireClientSync();
            if (CLIENT_SYNC.size() < 256) {
                session.clientSync = tr("sync.pending");
                CLIENT_SYNC.put(request, new SyncRequest(session, System.nanoTime() + 10_000_000_000L));
            } else session.clientSync = tr("sync.untracked");
        }
        return request;
    }

    public static void clientSyncSendFailed(UUID request) {
        SyncRequest pending = CLIENT_SYNC.remove(request);
        if (pending != null) pending.session.clientSync = tr("sync.send_failed");
    }

    public static void completeClientSync(UUID request, UUID entityUuid, ServerPlayer sender,
                                          boolean verified, float actual) {
        SyncRequest pending = CLIENT_SYNC.get(request);
        if (pending == null || System.nanoTime() >= pending.expires || !pending.session.entityUuid.equals(entityUuid)) return;
        Session session = pending.session;
        Entity entity = sender.level().getEntity(session.entityId);
        if (entity == null || !entity.getUUID().equals(entityUuid)) return;
        synchronized (session) {
            if (!pending.responders.add(sender.getUUID())) return;
            if (verified && HealthValueSemantics.matches(actual, session.target)) pending.accepted++;
            else pending.failed++;
            session.clientSync = tr("sync.responses", pending.accepted, pending.failed, formatFloat(actual));
            if (session.finished) write(session);
        }
    }

    public static void expireClientSync() {
        long now = System.nanoTime();
        CLIENT_SYNC.forEach((request, pending) -> {
            if (now < pending.expires || !CLIENT_SYNC.remove(request, pending)) return;
            synchronized (pending.session) {
                if (pending.responders.isEmpty()) pending.session.clientSync = tr("sync.no_response");
                if (pending.session.finished) write(pending.session);
            }
        });
    }

    private static final class SyncRequest {
        final Session session;
        final long expires;
        final Set<UUID> responders = new LinkedHashSet<>();
        int accepted;
        int failed;

        SyncRequest(Session session, long expires) { this.session = session; this.expires = expires; }
    }

    public static void recordAttempt(LivingEntity entity, String channel, boolean success, HealthReportText detail) {
        recordAttempt(entity, channel, success, detail, true);
    }

    public static void recordAttempt(LivingEntity entity, String channel, boolean success, HealthReportText detail,
                                     boolean selectWinner) {
        Session session = active(entity);
        if (session == null) return;
        Set<HealthReportText> failures = session.failureDetails.get(channel);
        List<HealthReportText> details = !success && failures != null && !failures.isEmpty()
                ? List.copyOf(failures) : detail == null ? List.of() : List.of(detail);
        session.attempts.put(channel, new Attempt(success ? tr("status.success") : tr("status.unsuccessful"), details));
        if (success && selectWinner) {
            session.winningChannel = channel;
            if ("channel.vanilla".equals(channel)) session.winningStorageKind = tr("storage.vanilla");
        }
    }

    public static void recordSkipped(LivingEntity entity, String channel, HealthReportText reason) {
        Session session = active(entity);
        if (session != null) session.attempts.put(channel,
                new Attempt(tr("status.skipped"), reason == null ? List.of() : List.of(reason)));
    }

    static void recordFailureDetail(LivingEntity entity, String channel, HealthReportText detail) {
        Session session = active(entity);
        if (session == null || detail == null) return;
        session.failureDetails.computeIfAbsent(channel, ignored -> new LinkedHashSet<>()).add(detail);
    }

    static void recordSharedEvidence(LivingEntity entity, List<HealthReportText> events) {
        Session session = active(entity);
        if (session != null) session.sharedEvidence = events;
    }

    public static void recordExternalMirror(LivingEntity entity, boolean success) {
        recordAttempt(entity, "channel.mirror", success, success ? tr("mirror.pending") : tr("mirror.unused"));
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
    }

    public static void attachDelayedTicket(LivingEntity entity, DelayedHealthVerifier.Ticket ticket) {
        Session session = active(entity);
        if (session == null || ticket == null) return;
        session.ticketRevision = ticket.revision();
        session.delayedPending = true;
        session.delayedStatus = tr("delay.waiting");
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
        if (session.ticketRevision == 0L) session.delayedStatus = tr("delay.not_needed");
        collectAnalysis(session, entity);
        write(session);
        cleanup(session);
        return session.path != null && Files.exists(session.path) ? session.path : null;
    }

    public static void finishWithError(LivingEntity entity, Throwable throwable) {
        Session session = active(entity);
        if (session == null) return;
        session.error = throwable == null ? tr("error.unknown") : throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
    }

    public static void completeDelayed(DelayedHealthVerifier.Ticket ticket, HealthReportText status, float actual) {
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
        CLIENT_SYNC.clear();
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
                session.analysisKind = tr("analysis.unresolved");
            } else {
                session.analysisKind = switch (result.classify()) {
                    case REAL_HEALTH -> tr("analysis.real");
                    case NOT_REAL_HEALTH -> tr("analysis.decoy");
                    case UNRESOLVED -> tr("analysis.partial");
                };
                session.primaryExpression = expressionShape(result.returnExpr);
                session.primarySources = sourceKinds(result.sources);
                session.primarySourceCount = result.sources.size();
                session.readCalculation = hasCalculation(result.returnExpr);
                session.staticStorage = result.sources.stream().anyMatch(source -> hasStaticOrigin(source, 0));
                session.storageContents = storageContents(result.sources, entity);
            }
        } catch (Throwable throwable) {
            if (throwable instanceof VirtualMachineError error) throw error;
            session.analysisKind = tr("analysis.error", throwable.getClass().getSimpleName());
        }

        refreshBackgroundAnalysis(session);

        HealthModel model = HealthModel.forClass(entityClass);
        session.anchorStatus = EcaSetHealthManager.isAnchorUntrusted(entity)
                ? tr("anchor.untrusted")
                : model.observation() == null
                    ? tr("anchor.default")
                    : tr("anchor.alternative", model.observationOrigin());
        session.delayedRollbackKnown = model.delayedRollbackObserved();
    }

    private static void refreshBackgroundAnalysis(Session session) {
        Class<?> entityClass = session.entityClass;
        HealthDataflowAnalyzer.AnalysisResult external =
                HealthDataflowAnalyzer.peekExternalScanResult(entityClass);
        session.externalStatus = external == null
                ? tr("analysis.background")
                : tr("analysis.external", external.sources.size(), sourceKinds(external.sources));

        HealthDataflowAnalyzer.EffectiveHealthModel effective =
                HealthDataflowAnalyzer.peekEffectiveHealthModel(entityClass);
        if (effective != null) {
            session.effectiveStatus = tr("analysis.effective", expressionShape(effective.readExpr()), sourceKind(effective.storage()));
        } else {
            session.effectiveStatus = tr("analysis.not_established");
        }

        HealthDataflowAnalyzer.MaintenancePlan maintenance =
                HealthDataflowAnalyzer.peekMaintenancePlan(entityClass);
        session.maintenanceStatus = HealthDataflowAnalyzer.isMaintenancePlanResolved(entityClass)
                ? tr("analysis.maintenance", maintenance.branches().size(), maintenance.maintenanceWriteCount(), yesNo(maintenance.hasExternalTransactionSource()))
                : tr("analysis.incomplete");
        session.hasExternalAuthority = maintenance.hasExternalTransactionSource();
        session.hasMirrorAuthority = HealthDataflowAnalyzer.hasMirrorAuthority(entityClass);
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
        refreshBackgroundAnalysis(session);
        Judgment judgment = judge(session);
        HealthReportText.Builder report = new HealthReportText.Builder(session.language);
        report.append(tr("title"))
                .append(tr("label.created")).append(session.createdAt.format(DISPLAY_TIME)).append('\n')
                .append(tr("label.name")).append(session.entityName).append('\n')
                .append(tr("label.uuid")).append(session.entityUuid).append('\n')
                .append(tr("label.id")).append(session.entityId).append("\n\n")
                .append(tr("section.storage"));
        for (Finding finding : judgment.findings()) {
            report.append(finding.subject()).append(": ").append(finding.value()).append('\n')
                    .append(tr("label.confidence")).append(finding.confidence()).append('\n')
                    .append(tr("label.evidence")).append(finding.evidence()).append("\n\n");
        }
        report
                .append(tr("label.module")).append(judgment.module()).append('\n')
                .append(tr("label.result")).append(judgment.result()).append('\n');

        report.append(tr("section.mutation"))
                .append(tr("label.target")).append(session.target).append('\n')
                .append(tr("label.before")).append(formatFloat(session.before)).append('\n')
                .append(tr("label.after")).append(formatFloat(session.after)).append('\n')
                .append(tr("label.immediate")).append(session.success ? tr("status.success") : tr("status.failed")).append('\n')
                .append(tr("label.winning_storage")).append(session.winningStorageKind).append('\n')
                .append(tr("label.delayed")).append(session.delayedStatus).append('\n');
        report.append(tr("label.client_sync")).append(session.clientSync).append('\n');
        if (Float.isFinite(session.delayedActual)) {
            report.append(tr("label.delayed_actual")).append(formatFloat(session.delayedActual)).append('\n');
        }
        if (session.error != null) report.append(tr("label.error")).append(session.error).append('\n');

        report.append(tr("section.channels"));
        appendAttempt(report, session, "channel.vanilla", true);
        appendAttempt(report, session, "channel.dataflow", EcaConfiguration.getAttackSetHealthEnableDataflowSafely());
        appendAttempt(report, session, "channel.external", EcaConfiguration.getAttackSetHealthEnableExternalScanSafely());
        appendAttempt(report, session, "channel.effective", EcaConfiguration.getAttackSetHealthEnableExternalScanSafely());
        appendAttempt(report, session, "channel.probe", EcaConfiguration.getAttackSetHealthEnableMethodProbeSafely());
        appendAttempt(report, session, "channel.numeric", EcaConfiguration.getAttackSetHealthEnableNumericInversionSafely());
        appendAttempt(report, session, "channel.mirror", EcaConfiguration.getAttackSetHealthEnableExternalScanSafely());

        report.append(tr("section.analysis"))
                .append(tr("label.classification")).append(session.analysisKind).append('\n')
                .append(tr("label.expression")).append(session.primaryExpression).append('\n')
                .append(tr("label.sources_count")).append(session.primarySourceCount).append('\n')
                .append(tr("label.sources")).append(session.primarySources).append('\n')
                .append(tr("label.anchor")).append(session.anchorStatus).append('\n')
                .append(tr("label.external")).append(session.externalStatus).append('\n')
                .append(tr("label.effective")).append(session.effectiveStatus).append('\n')
                .append(tr("label.maintenance")).append(session.maintenanceStatus).append('\n')
                .append(tr("label.known_rollback")).append(yesNo(session.delayedRollbackKnown)).append('\n')
                .append(tr("section.config"))
                .append(tr("label.compatibility")).append(onOff(EcaConfiguration.getForceCompatibilityModeSafely())).append('\n')
                .append(tr("label.radical")).append(onOff(EcaConfiguration.getAttackEnableRadicalLogicSafely())).append('\n')
                .append(tr("label.constant")).append(onOff(EcaConfiguration.getAttackSetHealthEnableConstOverrideSafely())).append('\n')
                .append(tr("label.dataflow")).append(onOff(EcaConfiguration.getAttackSetHealthEnableDataflowSafely())).append('\n')
                .append(tr("label.external")).append(onOff(EcaConfiguration.getAttackSetHealthEnableExternalScanSafely())).append('\n')
                .append(tr("label.probe")).append(onOff(EcaConfiguration.getAttackSetHealthEnableMethodProbeSafely())).append('\n')
                .append(tr("label.numeric")).append(onOff(EcaConfiguration.getAttackSetHealthEnableNumericInversionSafely())).append('\n');
        report.append(tr("section.shared"));
        if (session.sharedEvidence.isEmpty()) report.append(tr("shared.empty"));
        else for (HealthReportText event : session.sharedEvidence) report.append("- ").append(event).append('\n');
        report.append(tr("background.notice"));
        report.append(tr("section.raw"));
        List<String> diagnostics = session.diagnosticSnapshot();
        if (diagnostics.isEmpty()) report.append(tr("diagnostics.empty"));
        else for (String diagnostic : diagnostics) report.append(diagnostic).append('\n');
        return report.toString();
    }

    private static void appendAttempt(HealthReportText.Builder report, Session session, String channel, boolean enabled) {
        Attempt attempt = session.attempts.get(channel);
        if (attempt != null) {
            report.append(tr(channel)).append(": ").append(attempt.status());
            for (int i = 0; i < attempt.details().size(); i++) {
                report.append(i == 0 ? " — " : tr("detail.separator")).append(attempt.details().get(i));
            }
        } else if (!enabled) {
            report.append(tr(channel)).append(tr("channel.disabled"));
        } else {
            report.append(tr(channel)).append(tr("channel.not_entered"));
        }
        report.append('\n');
    }

    private static Judgment judge(Session session) {
        List<Finding> findings = new ArrayList<>();
        boolean verifiedStorage = session.success && !session.winningStorageKind.equals(tr("status.undetermined"))
                && !session.winningConstantOverride;
        findings.add(new Finding(tr("finding.location"),
                verifiedStorage ? session.winningStorageKind
                        : session.primarySourceCount > 0 ? tr("storage.candidates", session.primarySources) : tr("status.not_determined"),
                verifiedStorage ? tr("confidence.high") : session.primarySourceCount > 0 ? tr("confidence.medium") : tr("status.unknown"),
                verifiedStorage ? tr("evidence.verified")
                        : tr("evidence.candidate")));
        findings.add(new Finding(tr("finding.contents"), session.storageContents,
                session.storageContents.equals(tr("status.not_determined")) ? tr("status.unknown") : tr("confidence.high"),
                tr("evidence.contents")));
        findings.add(new Finding(tr("finding.external"),
                session.staticStorage ? tr("storage.external_candidate") : tr("status.not_determined"),
                session.staticStorage ? tr("confidence.medium") : tr("status.unknown"),
                session.staticStorage ? tr("evidence.static")
                        : tr("evidence.ownership")));
        boolean mirror = session.hasMirrorAuthority || session.hasExternalAuthority || session.winningMirrorRedirect;
        findings.add(new Finding(tr("finding.copy"), mirror ? tr("mirror.clues") : tr("status.not_determined"),
                mirror ? tr("confidence.medium") : tr("status.unknown"), mirror ? tr("evidence.mirror")
                        : tr("evidence.no_copy")));
        findings.add(new Finding(tr("finding.calculation"), session.readCalculation ? tr("bool.yes") : tr("status.not_determined"),
                session.readCalculation ? tr("confidence.high") : tr("status.unknown"),
                session.readCalculation ? tr("evidence.calculation") : tr("evidence.no_calculation")));
        boolean rolledBack = session.delayedStatus.equals(tr("delay.rolled_back"));
        boolean retained = session.delayedStatus.equals(tr("delay.retained"));
        findings.add(new Finding(tr("finding.restoration"),
                rolledBack ? tr("restore.observed") : retained ? tr("restore.not_observed")
                        : session.delayedRollbackKnown ? tr("restore.previously_observed") : tr("status.unverified"),
                rolledBack || retained ? tr("confidence.high") : session.delayedRollbackKnown ? tr("confidence.medium") : tr("status.unknown"),
                rolledBack || retained ? session.delayedStatus
                        : tr("evidence.delayed")));
        HealthReportText result = !session.success ? tr("result.failed")
                : session.alreadySatisfied ? tr("result.already_satisfied")
                : rolledBack ? tr("result.restored")
                : retained ? tr("result.retained")
                : session.delayedStatus.equals(tr("delay.removed")) ? tr("result.removed")
                : tr("result.pending");
        HealthReportText module = session.winningConstantOverride ? tr("module.constant")
                : session.alreadySatisfied ? tr("status.none")
                : session.winningChannel == null ? tr("status.undetermined") : tr(session.winningChannel);
        return new Judgment(module == null ? tr("status.undetermined") : module, result, findings);
    }

    private static boolean hasCalculation(HealthDataflowAnalyzer.Expr expression) {
        Set<HealthDataflowAnalyzer.Expr> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<HealthDataflowAnalyzer.Expr> pending = new ArrayList<>();
        if (expression != null) pending.add(expression);
        while (!pending.isEmpty() && visited.size() < 50_000) {
            HealthDataflowAnalyzer.Expr current = pending.remove(pending.size() - 1);
            if (!visited.add(current)) continue;
            if (current instanceof HealthDataflowAnalyzer.Op) return true;
            if (current instanceof HealthDataflowAnalyzer.Call call) {
                if (call.owner().equals("java/lang/Math") || call.owner().equals("java/lang/StrictMath")) return true;
                pending.addAll(call.args());
            } else if (current instanceof HealthDataflowAnalyzer.Choice choice) pending.addAll(choice.alternatives());
        }
        return false;
    }

    private static boolean hasStaticOrigin(HealthDataflowAnalyzer.Expr expression, int depth) {
        if (expression == null || depth > 64) return false;
        if (expression instanceof HealthDataflowAnalyzer.StaticFieldSource) return true;
        if (expression instanceof HealthDataflowAnalyzer.MapEntrySource map) return hasStaticOrigin(map.containerExpr, depth + 1);
        if (expression instanceof HealthDataflowAnalyzer.ChainedFieldSource field) return hasStaticOrigin(field.root, depth + 1);
        if (expression instanceof HealthDataflowAnalyzer.Call call)
            return call.args().stream().anyMatch(arg -> hasStaticOrigin(arg, depth + 1));
        if (expression instanceof HealthDataflowAnalyzer.Choice choice)
            return choice.alternatives().stream().anyMatch(arg -> hasStaticOrigin(arg, depth + 1));
        return false;
    }

    private static Object storageContents(List<HealthDataflowAnalyzer.Source> sources, LivingEntity entity) {
        Set<HealthReportText> contents = new LinkedHashSet<>();
        HealthDataflowAnalyzer.EvalContext context = HealthDataflowAnalyzer.newContext(entity);
        for (HealthDataflowAnalyzer.Source source : sources) {
            Object value;
            if (source instanceof HealthDataflowAnalyzer.MapEntrySource entry) {
                value = entry.read(entity);
            } else if (source instanceof HealthDataflowAnalyzer.FieldChainSource
                    || source instanceof HealthDataflowAnalyzer.ChainedFieldSource
                    || source instanceof HealthDataflowAnalyzer.StaticFieldSource
                    || source instanceof HealthDataflowAnalyzer.SynchedDataSource) {
                value = source.read(entity);
            } else continue;
            if (value instanceof Enum<?>) contents.add(tr("value.enum_shared"));
            else if (value instanceof Number) contents.add(tr("value.number"));
            else if (value instanceof String) contents.add(tr("value.text"));
            else if (value instanceof Boolean) contents.add(tr("value.boolean"));
            else if (value != null) contents.add(tr("value.reference"));
        }
        return contents.isEmpty() ? tr("status.not_determined") : List.copyOf(contents);
    }

    private static Object expressionShape(HealthDataflowAnalyzer.Expr expression) {
        if (expression == null) return tr("status.none");
        return expression.getClass().getSimpleName();
    }

    private static Object sourceKinds(List<HealthDataflowAnalyzer.Source> sources) {
        if (sources == null || sources.isEmpty()) return tr("status.none");
        Map<HealthReportText, Integer> counts = new LinkedHashMap<>();
        for (HealthDataflowAnalyzer.Source source : sources) {
            counts.merge(sourceKind(source), 1, Integer::sum);
        }
        List<HealthReportText> values = new ArrayList<>();
        counts.forEach((kind, count) -> values.add(count > 1 ? tr("list.count", kind, count) : kind));
        return List.copyOf(values);
    }

    private static HealthReportText sourceKind(HealthDataflowAnalyzer.Source source) {
        if (source instanceof HealthDataflowAnalyzer.ConstOverrideSource) return tr("storage.constant");
        if (source instanceof HealthDataflowAnalyzer.SynchedDataSource) return tr("storage.synced");
        if (source instanceof HealthDataflowAnalyzer.MapEntrySource) return tr("storage.map");
        if (source instanceof HealthDataflowAnalyzer.CapabilityDataSource) return tr("storage.capability");
        if (source instanceof HealthDataflowAnalyzer.ArrayElementSource) return tr("storage.array");
        if (source instanceof HealthDataflowAnalyzer.StaticFieldSource) return tr("storage.static");
        if (source instanceof HealthDataflowAnalyzer.ChainedFieldSource) return tr("storage.field");
        if (source instanceof HealthDataflowAnalyzer.FieldChainSource) return tr("storage.entity_chain");
        if (source instanceof HealthDataflowAnalyzer.MethodCallSource) return tr("storage.method_write");
        if (source instanceof HealthDataflowAnalyzer.MethodPropertySource) return tr("storage.property");
        return tr("storage.other");
    }

    private static String sanitizeFileName(String value) {
        String sanitized = value == null ? "entity" : value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (sanitized.isEmpty()) sanitized = "entity";
        if (sanitized.length() > 48) sanitized = sanitized.substring(0, 48);
        return sanitized;
    }

    private static Object formatFloat(float value) {
        return Float.isFinite(value) ? Float.toString(value) : tr("status.unavailable");
    }

    private static HealthReportText yesNo(boolean value) {
        return value ? tr("bool.yes") : tr("bool.no");
    }

    private static HealthReportText onOff(boolean value) {
        return value ? tr("config.on") : tr("config.off");
    }

    private record Attempt(HealthReportText status, List<HealthReportText> details) {}

    private record Finding(HealthReportText subject, Object value, HealthReportText confidence, HealthReportText evidence) {}

    private record Judgment(HealthReportText module, HealthReportText result, List<Finding> findings) {}

    private static final class Session {
        private final LocalDateTime createdAt = LocalDateTime.now();
        private final int entityId;
        private final UUID entityUuid;
        private final Class<?> entityClass;
        private final String entityName;
        private final String language;
        private final float target;
        private final Map<String, Attempt> attempts = new LinkedHashMap<>();
        private final Map<String, Set<HealthReportText>> failureDetails = new LinkedHashMap<>();
        private List<HealthReportText> sharedEvidence = List.of();
        private float before = Float.NaN;
        private float after = Float.NaN;
        private float delayedActual = Float.NaN;
        private boolean success;
        private boolean alreadySatisfied;
        private HealthReportText clientSync = tr("sync.not_requested");
        private Object error;
        private String winningChannel;
        private HealthReportText delayedStatus = tr("delay.unregistered");
        private long ticketRevision;
        private Path path;
        private HealthReportText analysisKind = tr("analysis.not_started");
        private Object primaryExpression = tr("status.none");
        private Object primarySources = tr("status.none");
        private int primarySourceCount;
        private HealthReportText externalStatus = tr("analysis.no_result");
        private HealthReportText effectiveStatus = tr("analysis.not_established");
        private HealthReportText maintenanceStatus = tr("analysis.incomplete");
        private HealthReportText anchorStatus = tr("status.unknown");
        private boolean readCalculation;
        private boolean staticStorage;
        private Object storageContents = tr("status.not_determined");
        private boolean hasExternalAuthority;
        private boolean hasMirrorAuthority;
        private boolean delayedRollbackKnown;
        private boolean winningConstantOverride;
        private boolean winningMirrorRedirect;
        private Object winningStorageKind = tr("status.undetermined");
        private final AtomicInteger backgroundTasks = new AtomicInteger();
        private final List<String> diagnostics = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean finished;
        private volatile boolean delayedPending;
        private volatile boolean diagnosticLimitReached;

        private Session(LivingEntity entity, float target, String language) {
            this.entityId = entity.getId();
            this.entityUuid = entity.getUUID();
            this.entityClass = entity.getClass();
            this.entityName = entity.getName().getString();
            this.target = target;
            this.language = HealthReportText.normalizeLanguage(language);
        }

        private void captureLog(String message) {
            if (message == null || HEALTH_LOG_PREFIXES.stream().noneMatch(message::startsWith)) return;
            synchronized (diagnostics) {
                if (diagnostics.size() >= MAX_DIAGNOSTIC_LINES) {
                    if (!diagnosticLimitReached) {
                        diagnosticLimitReached = true;
                        diagnostics.add(HealthReportText.render(tr("diagnostics.limit"), language));
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
