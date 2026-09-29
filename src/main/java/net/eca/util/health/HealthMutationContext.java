package net.eca.util.health;

import static net.eca.util.health.HealthReportText.tr;

import net.eca.util.EcaLogger;
import net.eca.util.health.HealthDataflowAnalyzer.AnalysisResult;
import net.eca.util.health.HealthDataflowAnalyzer.Call;
import net.eca.util.health.HealthDataflowAnalyzer.ChainedFieldSource;
import net.eca.util.health.HealthDataflowAnalyzer.Expr;
import net.eca.util.health.HealthDataflowAnalyzer.FieldChainSource;
import net.eca.util.health.HealthDataflowAnalyzer.MapEntrySource;
import net.eca.util.health.HealthDataflowAnalyzer.SynchedDataSource;
import net.eca.util.health.HealthDataflowAnalyzer.Source;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Shares evidence only within one entity mutation; no live objects enter class caches. */
final class HealthMutationContext implements AutoCloseable {
    private static final ThreadLocal<HealthMutationContext> ACTIVE = new ThreadLocal<>();
    private static final int MAX_EVIDENCE = 256;
    private static final int MAX_ATTEMPTS = 256;
    private final HealthMutationContext previous;
    private final LivingEntity entity;
    private final long deadline = System.nanoTime() + 1_000_000_000L;
    private final Map<Source, String> sources = new LinkedHashMap<>();
    private final Map<MapEntrySource, MapEntrySource.ResolvedMapEntry> mapLocations = new LinkedHashMap<>();
    private final Map<String, String> expressions = new LinkedHashMap<>();
    private final List<HealthSolveResult> failures = new ArrayList<>();
    private final Set<Object> runtimeRoots = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> allowedNumericRoots = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Object, Set<Object>> readObjects = new IdentityHashMap<>();
    private final List<Expr> boundedReadExpressions = new ArrayList<>();
    private final List<Expr> numericExpressions = new ArrayList<>();
    private final Set<Object> numericDescentRoots = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Attempt> attempts = new LinkedHashSet<>();
    private final List<HealthReportText> events = new ArrayList<>();
    private String channel = "channel.initial";
    private boolean stopped;
    private HealthReportText stopReason;
    private int revision;
    private final Set<Source> readStorage = new LinkedHashSet<>();
    private final Set<Source> writtenStorage = new LinkedHashSet<>();
    private Expr primaryRead;
    private boolean inheritedReadCandidate;
    private boolean storageSearchPending;
    private final Map<Source, Submission> submissions = new LinkedHashMap<>();

    static void recordWrittenStorage(Source source) {
        HealthMutationContext context = current();
        if (context != null && source != null) context.writtenStorage.add(source);
    }

    boolean wroteStorage(Source source) { return writtenStorage.contains(source); }

    void rememberSubmission(Source source, Object candidate, BooleanSupplier validate, Runnable consume) {
        submissions.put(source, new Submission(candidate, validate, consume));
    }

    boolean validateSubmission(Source source, Object candidate) {
        Submission submission = submissions.get(source);
        if (submission == null || submission.candidate() != candidate) return true;
        if (submission.validate().getAsBoolean()) return true;
        deferStorageSearch(source);
        note(tr("submit.revalidation_failed"));
        return false;
    }

    void beginSubmission(Source source, Object candidate) {
        Submission submission = submissions.get(source);
        if (submission != null && submission.candidate() == candidate) {
            submission.consume().run();
            submissions.remove(source);
            note(tr("submit.begin"));
        }
    }

    long submissionDeadline() { return Math.min(deadline, System.nanoTime() + 25_000_000L); }

    private record Submission(Object candidate, BooleanSupplier validate, Runnable consume) {}

    void establishReadSlice(AnalysisResult tree) {
        if (tree == null || tree.classify() != AnalysisResult.Kind.REAL_HEALTH) return;
        Set<Source> slice = HealthDataflowAnalyzer.healthReadSlice(tree.returnExpr);
        slice.retainAll(tree.sources);
        slice.removeIf(source -> source.read(entity) == null);
        if (slice.isEmpty()) return;
        primaryRead = tree.returnExpr;
        inheritedReadCandidate = entity.getClass().isHidden() && tree.definingClass != entity.getClass();
        if (inheritedReadCandidate) note(tr("slice.inherited_candidate"));
        readStorage.addAll(slice);
        for (Source source : slice) sources.putIfAbsent(source, "origin.health_read");
        note(tr("slice.read", readStorage.size()));
    }

    boolean allowsStorage(Source source) {
        if (source == null) return readStorage.isEmpty();
        if (readStorage.isEmpty() || readStorage.contains(source)) return true;
        for (Source storage : readStorage) {
            HealthDataflowAnalyzer.MirrorLink link = HealthDataflowAnalyzer.peekMirrorLink(entity.getClass(), storage);
            if (link != null && link.authority().equals(source)) return true;
            link = HealthDataflowAnalyzer.peekMirrorLink(entity.getClass(), source);
            if (link != null && readStorage.contains(link.authority())) return true;
        }
        return false;
    }

    boolean hasReadSlice() { return !readStorage.isEmpty(); }

    boolean allowsEffectiveModel(HealthDataflowAnalyzer.EffectiveHealthModel model) {
        if (allowsStorage(model.storage())) return true;
        // A successful write is stronger evidence than a competing, unverified model.
        if (readStorage.stream().anyMatch(writtenStorage::contains)) return false;
        HealthModel observation = HealthModel.forClass(entity.getClass());
        return observation.effectiveObservationConfirmed()
                || (model.origin() == HealthDataflowAnalyzer.ComparisonOrigin.LIFECYCLE
                    || inheritedReadCandidate && model.origin() == HealthDataflowAnalyzer.ComparisonOrigin.TERMINAL_OBSERVER)
                    && EcaSetHealthManager.isHealthReadDecoupled(entity.getClass());
    }

    EffectiveSlice openEffectiveSlice(HealthDataflowAnalyzer.EffectiveHealthModel model) {
        return new EffectiveSlice(model);
    }

    final class EffectiveSlice implements AutoCloseable {
        private final Set<Source> previousStorage = new LinkedHashSet<>(readStorage);
        private final Expr previousRead = primaryRead;

        private EffectiveSlice(HealthDataflowAnalyzer.EffectiveHealthModel model) {
            readStorage.clear();
            readStorage.add(model.storage());
            primaryRead = model.readExpr();
            if (sources.putIfAbsent(model.storage(), "channel.effective") == null) revision++;
            note(tr("slice.effective"));
        }

        @Override public void close() {
            readStorage.clear();
            readStorage.addAll(previousStorage);
            primaryRead = previousRead;
        }
    }

    void deferStorageSearch(Source source) {
        if (readStorage.contains(source)) {
            storageSearchPending = true;
            note(tr("slice.pending"));
        }
    }

    boolean storageSearchPending() { return storageSearchPending; }

    boolean allowsJointWrite(List<Source> group) {
        if (primaryRead == null || !group.stream().allMatch(this::allowsStorage)) return false;
        if (HealthDataflowAnalyzer.sharesReadConstraint(primaryRead, group)) return true;
        Set<Source> connected = new LinkedHashSet<>();
        connected.add(group.get(0));
        boolean changed;
        do {
            changed = false;
            for (Source source : group) {
                HealthDataflowAnalyzer.MirrorLink link = HealthDataflowAnalyzer.peekMirrorLink(entity.getClass(), source);
                if (link != null && group.contains(link.authority())
                        && (connected.contains(source) || connected.contains(link.authority()))) {
                    changed |= connected.add(source);
                    changed |= connected.add(link.authority());
                }
            }
        } while (changed);
        return connected.containsAll(group);
    }

    private HealthMutationContext(LivingEntity entity) {
        this.entity = entity;
        previous = ACTIVE.get();
        ACTIVE.set(this);
    }

    static HealthMutationContext open(LivingEntity entity) { return new HealthMutationContext(entity); }
    static HealthMutationContext current() { return ACTIVE.get(); }
    static boolean stopped() { return current() != null && current().stopped; }

    static void rollbackFailed() {
        rollbackFailed(tr("restore.failed"));
    }

    static void rollbackFailed(HealthReportText reason) {
        HealthMutationContext context = current();
        if (context != null) {
            context.stopped = true;
            context.stopReason = reason;
            context.note(tr("write.stopped", reason));
        }
    }

    static void analysisPending() {
        HealthMutationContext context = current();
        if (context == null) return;
        context.stopped = true;
        context.stopReason = tr("analysis.pending");
        context.note(context.stopReason);
    }

    static void recordEvidence(HealthReportText detail) {
        if (current() != null) current().note(detail);
    }

    boolean enter(String next) {
        channel = next;
        if (stopped || System.nanoTime() > deadline) {
            stopped = true;
            HealthReportManager.recordSkipped(entity, next, stopReason == null ? tr("budget.shared_exhausted") : stopReason);
            return false;
        }
        note(tr("channel.enter", tr(next), sources.size(), failures.size()));
        return true;
    }

    void publish(String origin, AnalysisResult tree) {
        if (tree == null || tree.returnExpr == null) return;
        if (numericExpressions.size() < MAX_EVIDENCE
                && numericExpressions.stream().noneMatch(expression -> expression == tree.returnExpr)) {
            numericExpressions.add(tree.returnExpr);
        }
        if (HealthDataflowAnalyzer.hasBoundedReadBoundary(tree.returnExpr)
                && boundedReadExpressions.size() < MAX_EVIDENCE
                && !boundedReadExpressions.contains(tree.returnExpr)) boundedReadExpressions.add(tree.returnExpr);
        String expressionKey = HealthDataflowAnalyzer.evidenceKey(tree.returnExpr);
        if (expressions.size() < MAX_EVIDENCE && !expressions.containsKey(expressionKey)) {
            expressions.put(expressionKey, origin);
            revision++;
        }
        int added = 0;
        for (Source source : tree.sources) {
            if (sources.size() >= MAX_EVIDENCE) break;
            if (!sources.containsKey(source)) {
                sources.put(source, origin);
                added++;
                revision++;
                note(tr("source.discovered", tr(origin), sources.size(), storageKind(source)));
            }
        }
        if (added > 0) note(tr("source.published", tr(origin), added));
    }

    static HealthSolveResult retainFailure(HealthSolveResult result, Expr expression, Source source, Object target) {
        if (result.solved()) return result;
        HealthSolveResult enriched = result.at(expression, source, target);
        HealthMutationContext context = current();
        if (context != null && enriched.site() != null && context.failures.size() < MAX_EVIDENCE && !context.failures.contains(enriched)) {
            if (enriched.failure() == HealthSolveFailure.BUDGET_EXHAUSTED)
                context.deferStorageSearch(enriched.site().source());
            context.failures.add(enriched);
            int position = new ArrayList<>(context.sources.keySet()).indexOf(enriched.site().source()) + 1;
            context.note(tr("solve.interrupted", tr(context.channel), tr("failure." + enriched.failure().name().toLowerCase(Locale.ROOT)), position == 0 ? tr("storage.unnumbered") : "#" + position, enriched.site().expression().getClass().getSimpleName()));
        }
        return enriched;
    }

    List<Object> roots(String consumer) {
        List<Object> roots = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object root : runtimeRoots) if (seen.add(root)) roots.add(root);
        for (Source source : sources.keySet()) {
            if (!allowsStorage(source)) continue;
            if (roots.size() >= MAX_EVIDENCE) break;
            try {
                source.collectDescentAnchors(HealthDataflowAnalyzer.newContext(entity), value -> {
                    if (roots.size() < MAX_EVIDENCE && value != null && !(value instanceof Entity)
                            && !(value instanceof Enum<?>) && !(value instanceof Number)
                            && !(value instanceof String) && !(value instanceof Class<?>) && seen.add(value)) roots.add(value);
                });
            } catch (RuntimeException | LinkageError exception) {
                EcaLogger.info("[HealthMutation] candidate root read failed: {}", exception.getClass().getSimpleName());
            }
        }
        for (HealthSolveResult failure : failures) {
            if (!allowsStorage(failure.site().source())) continue;
            if (roots.size() >= MAX_EVIDENCE) break;
            for (Object value : HealthDataflowAnalyzer.collectDeadEndRoots(
                    failure.site().expression(), HealthDataflowAnalyzer.newContext(entity))) {
                if (roots.size() >= MAX_EVIDENCE) break;
                if (!(value instanceof Entity) && !(value instanceof Enum<?>) && seen.add(value)) roots.add(value);
            }
        }
        note(tr("source.roots", tr(consumer), roots.size()));
        return roots;
    }

    List<Source> numericSources() {
        return sources.keySet().stream().filter(this::allowsStorage).toList();
    }

    int revision() { return revision; }

    MapEntrySource.ResolvedMapEntry mapLocation(MapEntrySource source) { return mapLocations.get(source); }

    void rememberMapLocation(MapEntrySource source, MapEntrySource.ResolvedMapEntry location) {
        if (mapLocations.size() < MAX_EVIDENCE) mapLocations.putIfAbsent(source, location);
    }

    int sourcePriority(Source source) {
        return "origin.health_read".equals(sources.get(source)) ? 0 : 1;
    }

    private static HealthReportText storageKind(Source source) {
        if (source instanceof MapEntrySource) return tr("storage.map");
        if (source instanceof SynchedDataSource) return tr("storage.synced");
        if (source instanceof FieldChainSource || source instanceof ChainedFieldSource) return tr("storage.field");
        return tr("storage.other_candidate");
    }

    List<Object> numericRoots() {
        List<Object> roots = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Expr expression : boundedReadExpressions) {
            for (Object root : HealthDataflowAnalyzer.boundedReadRoots(expression, HealthDataflowAnalyzer.newContext(entity))) {
                if (seen.add(root)) roots.add(root);
            }
        }
        List<Object> candidates = roots("channel.numeric");
        for (Expr expression : numericExpressions) {
            candidates.addAll(HealthDataflowAnalyzer.collectDeadEndRoots(
                    expression, HealthDataflowAnalyzer.newContext(entity)));
        }
        for (Object root : candidates) {
            if (roots.size() >= MAX_EVIDENCE) break;
            if (root == null || root instanceof Entity || root instanceof Class<?>
                    || root instanceof Map<?, ?> || root instanceof Iterable<?> && !(root instanceof List<?>)) continue;
            if (seen.add(root)) roots.add(root);
            // A located opaque value may have no read trace; its private object graph remains searchable.
            if (!readObjects.containsKey(root)) numericDescentRoots.add(root);
        }
        allowedNumericRoots.addAll(roots);
        for (Object root : roots) allowedNumericRoots.addAll(readObjects.getOrDefault(root, Set.of()));
        return roots;
    }

    static boolean allowNumericDescent(Object root) {
        HealthMutationContext context = current();
        return context == null || context.numericDescentRoots.contains(root);
    }

    boolean hasBoundedNumericRead() {
        return boundedReadExpressions.stream().anyMatch(HealthDataflowAnalyzer::hasBoundedNumericReadBoundary);
    }

    static void recordReadObjects(Object root, Set<Object> objects) {
        HealthMutationContext context = current();
        if (context == null || root == null || context.readObjects.size() >= MAX_EVIDENCE) return;
        Set<Object> copy = Collections.newSetFromMap(new IdentityHashMap<>());
        copy.addAll(objects);
        context.readObjects.put(root, copy);
    }

    static boolean allowNumericObject(Object value) {
        HealthMutationContext context = current();
        return context == null || context.allowedNumericRoots.contains(value);
    }

    int priority(MethodProbe.DirectCandidate candidate) {
        for (Source source : sources.keySet()) {
            if (source instanceof FieldChainSource field && field.chain.stream().anyMatch(step ->
                    step.name().equals(candidate.memberName()))) return 0;
            if (source instanceof ChainedFieldSource field && field.chain.stream().anyMatch(step ->
                    step.name().equals(candidate.memberName()))) return 0;
        }
        for (HealthSolveResult failure : failures) {
            if (failure.site().expression() instanceof Call call && call.owner().equals(candidate.declaringInternal())
                    && call.name().equals(candidate.memberName())) return 0;
        }
        return 1;
    }

    String evidenceKey() {
        return sources.keySet().stream().map(HealthDataflowAnalyzer::evidenceKey)
                .sorted().reduce("", (left, right) -> left + "|" + right);
    }

    String codecEvidenceKey() {
        return evidenceKey() + "|" + expressions.size() + "|" + runtimeRoots.size();
    }

    void publishProbe(MethodProbe.DirectCandidate candidate) {
        if (candidate.kind() == MethodProbe.WriterKind.METHOD || runtimeRoots.size() >= MAX_EVIDENCE) return;
        for (Class<?> owner = entity.getClass(); owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            if (!owner.getName().replace('.', '/').equals(candidate.declaringInternal())) continue;
            try {
                Field field = owner.getDeclaredField(candidate.memberName());
                if (Modifier.isStatic(field.getModifiers())) return;
                field.setAccessible(true);
                Object value = field.get(entity);
                if (value != null && !(value instanceof Entity) && !(value instanceof Enum<?>)
                        && !(value instanceof Number) && !(value instanceof String) && runtimeRoots.add(value)) {
                    revision++;
                    note(tr("probe.published"));
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                EcaLogger.info("[HealthMutation] probe root read failed: {}", exception.getClass().getSimpleName());
            }
            return;
        }
    }

    static boolean attempt(Object location, Object candidate) {
        HealthMutationContext context = current();
        if (context == null) return true;
        if (context.stopped || System.nanoTime() > context.deadline || context.attempts.size() >= MAX_ATTEMPTS) {
            context.stopped = true;
            context.note(tr("write.budget_or_restore"));
            return false;
        }
        boolean added = context.attempts.add(new Attempt(location, candidate, context.revision));
        if (!added) context.note(tr("write.duplicate"));
        return added;
    }

    private void note(HealthReportText event) {
        if (events.size() < MAX_EVIDENCE && !events.contains(event)) events.add(event);
    }

    @Override public void close() {
        HealthReportManager.recordSharedEvidence(entity, List.copyOf(events));
        if (previous == null) ACTIVE.remove();
        else ACTIVE.set(previous);
    }

    private record Attempt(Object location, Object candidate, int revision) {}
}
