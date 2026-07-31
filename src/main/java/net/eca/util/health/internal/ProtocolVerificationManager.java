package net.eca.util.health.internal;

import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer.Source;
import net.eca.util.health.protocol.ValidationPlan;
import net.eca.util.health.protocol.ValidationResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ProtocolVerificationManager {
    private static final int MAX_PENDING = 1024;
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<Class<?>, ValidationResult> LAST_RESULTS = new ConcurrentHashMap<>();
    private static final Map<UUID, PersistedExpectation> PERSISTED_EXPECTATIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, List<SourceSnapshot>> REGISTERED_ROLLBACKS = new ConcurrentHashMap<>();
    private static final Map<UUID, ProtocolNumericInverter.NumericRollback> REGISTERED_NUMERIC_ROLLBACKS =
            new ConcurrentHashMap<>();
    private static final Map<UUID, ProtocolStateSnapshot> REGISTERED_STATE_ROLLBACKS =
            new ConcurrentHashMap<>();

    private ProtocolVerificationManager() {
    }

    public static void schedule(LivingEntity entity, float before, float target) {
        if (entity == null) return;
        if (entity instanceof Player || !Float.isFinite(target)
                || entity.level() == null || entity.level().isClientSide) {
            clearRegisteredRollbacks(entity.getUUID());
            return;
        }
        MinecraftServer server = entity.level().getServer();
        if (server == null) {
            clearRegisteredRollbacks(entity.getUUID());
            return;
        }
        if (PENDING.size() >= MAX_PENDING && !PENDING.containsKey(entity.getId())) {
            clearRegisteredRollbacks(entity.getUUID());
            EcaLogger.info("[LifeProtocol] delayed validation budget exhausted size={}", PENDING.size());
            return;
        }
        int dueTick = server.getTickCount() + 1;
        List<SourceSnapshot> registeredRollback = REGISTERED_ROLLBACKS.remove(entity.getUUID());
        ProtocolNumericInverter.NumericRollback numericRollback =
                REGISTERED_NUMERIC_ROLLBACKS.remove(entity.getUUID());
        ProtocolStateSnapshot stateRollback = REGISTERED_STATE_ROLLBACKS.remove(entity.getUUID());
        List<Float> predictedHealthStates = LifeProtocolManager.predictDelayedHealthStates(entity, target);
        PENDING.put(entity.getId(), new Pending(new WeakReference<>(entity), entity.getClass(), entity.getUUID(),
                before, target, dueTick, registeredRollback == null ? List.of() : registeredRollback,
                numericRollback, stateRollback, predictedHealthStates));
    }

    static void registerRollback(LivingEntity entity, List<SourceSnapshot> snapshots) {
        if (entity == null || snapshots == null || snapshots.isEmpty()) return;
        clearRegisteredRollbacks(entity.getUUID());
        REGISTERED_ROLLBACKS.put(entity.getUUID(), List.copyOf(snapshots));
    }

    static void registerNumericRollback(LivingEntity entity,
                                        ProtocolNumericInverter.NumericRollback rollback) {
        if (entity != null && rollback != null) {
            clearRegisteredRollbacks(entity.getUUID());
            REGISTERED_NUMERIC_ROLLBACKS.put(entity.getUUID(), rollback);
        }
    }

    static void registerStateRollback(LivingEntity entity, ProtocolStateSnapshot rollback) {
        if (entity != null && rollback != null) {
            clearRegisteredRollbacks(entity.getUUID());
            REGISTERED_STATE_ROLLBACKS.put(entity.getUUID(), rollback);
        }
    }

    private static void clearRegisteredRollbacks(UUID entityUuid) {
        REGISTERED_ROLLBACKS.remove(entityUuid);
        REGISTERED_NUMERIC_ROLLBACKS.remove(entityUuid);
        REGISTERED_STATE_ROLLBACKS.remove(entityUuid);
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || PENDING.isEmpty()) return;
        int now = server.getTickCount();
        for (Iterator<Map.Entry<Integer, Pending>> iterator = PENDING.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<Integer, Pending> entry = iterator.next();
            Pending pending = entry.getValue();
            if (now < pending.dueTick()) continue;
            iterator.remove();
            validate(entry.getKey(), pending);
        }
    }

    public static Optional<ValidationResult> getLastResult(Class<?> entityClass) {
        return Optional.ofNullable(entityClass == null ? null : LAST_RESULTS.get(entityClass));
    }

    public static void expectPersistence(LivingEntity entity, float target) {
        if (entity == null || !Float.isFinite(target)) return;
        Pending pending = PENDING.get(entity.getId());
        List<SourceSnapshot> rollback = pending != null && pending.entityUuid().equals(entity.getUUID())
                ? pending.rollback() : List.of();
        ProtocolNumericInverter.NumericRollback numericRollback = pending != null
                && pending.entityUuid().equals(entity.getUUID()) ? pending.numericRollback() : null;
        ProtocolStateSnapshot stateRollback = pending != null
                && pending.entityUuid().equals(entity.getUUID()) ? pending.stateRollback() : null;
        PERSISTED_EXPECTATIONS.put(entity.getUUID(),
                new PersistedExpectation(entity.getClass(), target, rollback, numericRollback, stateRollback));
    }

    public static void onEntityJoin(LivingEntity entity) {
        if (entity == null || entity.level() == null || entity.level().isClientSide) return;
        PersistedExpectation expectation = PERSISTED_EXPECTATIONS.remove(entity.getUUID());
        if (expectation == null || expectation.entityClass() != entity.getClass()) return;
        float actual = LifeProtocolManager.readHealthAnchor(entity);
        ValidationResult.Outcome outcome = ProtocolValueSemantics.matchesWithDeathSemantics(
                actual, expectation.target())
                ? ValidationResult.Outcome.MATCHED : ValidationResult.Outcome.MISMATCHED;
        ValidationResult.Status status = outcome == ValidationResult.Outcome.MATCHED
                ? ValidationResult.Status.PASSED : ValidationResult.Status.FAILED;
        String detail = outcome == ValidationResult.Outcome.MATCHED
                ? "persisted health survived entity reload" : "persisted health diverged after entity reload";
        ValidationResult.Observation observation = new ValidationResult.Observation(
                ValidationPlan.Stage.PERSISTENCE_RELOAD, outcome, false,
                expectation.target(), actual, detail);
        LAST_RESULTS.put(entity.getClass(), new ValidationResult(status, List.of(observation), detail));
        if (outcome == ValidationResult.Outcome.MISMATCHED) {
            restore(entity, expectation.rollback());
            if (expectation.numericRollback() != null) expectation.numericRollback().restore();
            if (expectation.stateRollback() != null) expectation.stateRollback().restore();
            LifeProtocolManager.onDelayedRollback(entity.getClass(), expectation.target());
            LifeProtocolManager.invalidateResolvedProtocol(entity.getClass(), detail);
        }
    }

    public static void clear() {
        PENDING.clear();
        LAST_RESULTS.clear();
        PERSISTED_EXPECTATIONS.clear();
        REGISTERED_ROLLBACKS.clear();
        REGISTERED_NUMERIC_ROLLBACKS.clear();
        REGISTERED_STATE_ROLLBACKS.clear();
    }

    private static void validate(int entityId, Pending pending) {
        LivingEntity entity = pending.entity().get();
        if (entity == null) return;
        if (entity.getId() != entityId || !entity.getUUID().equals(pending.entityUuid())) return;
        if (entity.isRemoved() && pending.target() <= 0.0f) {
            record(pending, ValidationResult.Outcome.MATCHED, 0.0f, "entity removed after lethal mutation");
            return;
        }

        float actual = LifeProtocolManager.readHealthAnchor(entity);
        if (ProtocolValueSemantics.retainedAfterDelay(actual, pending.target())) {
            record(pending, ValidationResult.Outcome.MATCHED, actual, "health transaction survived one entity tick");
            return;
        }
        for (float predicted : pending.predictedHealthStates()) {
            if (ProtocolValueSemantics.matches(actual, predicted)) {
                record(pending, ValidationResult.Outcome.MATCHED, actual,
                        "health followed a discovered lifecycle transition");
                return;
            }
        }

        record(pending, ValidationResult.Outcome.MISMATCHED, actual, "health state was restored after mutation");
        restore(pending);
        LifeProtocolManager.onDelayedRollback(pending.entityClass(), pending.target());
        LifeProtocolManager.invalidateResolvedProtocol(pending.entityClass(),
                "cross-tick validation failed target=" + pending.target() + " actual=" + actual
                        + " before=" + pending.before());
        if (pending.target() <= 0.0f) terminateRejectedLethalProtocol(pending, actual);
    }

    private static void terminateRejectedLethalProtocol(Pending pending, float restoredObservation) {
        LivingEntity entity = pending.entity().get();
        if (entity == null || entity.isRemoved()) return;
        EntityUtil.kill(entity, entity.damageSources().genericKill());
        boolean terminated = entity.isRemoved() || entity.dead || !entity.isAlive();
        ValidationResult.Observation delayed = new ValidationResult.Observation(
                ValidationPlan.Stage.AFTER_TICK, ValidationResult.Outcome.MISMATCHED, false,
                pending.target(), restoredObservation,
                "numeric state was restored and the transaction was rolled back");
        ValidationResult.Observation lifecycle = new ValidationResult.Observation(
                ValidationPlan.Stage.LIFECYCLE,
                terminated ? ValidationResult.Outcome.MATCHED : ValidationResult.Outcome.MISMATCHED,
                true, "terminated", terminated,
                terminated ? "lethal intent completed through the lifecycle transaction"
                        : "lifecycle transaction did not terminate the entity");
        LAST_RESULTS.put(pending.entityClass(), new ValidationResult(
                terminated ? ValidationResult.Status.PASSED : ValidationResult.Status.ROLLED_BACK,
                List.of(delayed, lifecycle), lifecycle.detail()));
        EcaLogger.info("[LifeProtocol] lethal lifecycle transaction entity={} terminated={}",
                pending.entityClass().getName(), terminated);
    }

    private static void restore(Pending pending) {
        LivingEntity entity = pending.entity().get();
        if (entity == null) return;
        restore(entity, pending.rollback());
        restoreNumeric(pending);
        if (pending.stateRollback() != null) pending.stateRollback().restore();
    }

    private static void restore(LivingEntity entity, List<SourceSnapshot> rollback) {
        if (entity == null || rollback == null || rollback.isEmpty()) return;
        boolean restored = true;
        for (int index = rollback.size() - 1; index >= 0; index--) {
            SourceSnapshot snapshot = rollback.get(index);
            if (!ProtocolDataFlowEngine.dispatchWrite(snapshot.source(), entity, snapshot.value())) {
                restored = false;
            }
        }
        EcaLogger.info("[LifeProtocol] delayed rollback entity={} states={} restored={}",
                entity.getClass().getName(), rollback.size(), restored);
    }

    private static void restoreNumeric(Pending pending) {
        if (pending.numericRollback() != null) pending.numericRollback().restore();
    }

    private static void record(Pending pending, ValidationResult.Outcome outcome, float actual, String detail) {
        ValidationResult.Status status = outcome == ValidationResult.Outcome.MATCHED
                ? ValidationResult.Status.PASSED : ValidationResult.Status.FAILED;
        ValidationResult.Observation observation = new ValidationResult.Observation(
                ValidationPlan.Stage.AFTER_TICK, outcome, true, pending.target(), actual, detail);
        LAST_RESULTS.put(pending.entityClass(), new ValidationResult(status, List.of(observation), detail));
    }

    private record Pending(WeakReference<LivingEntity> entity, Class<?> entityClass, UUID entityUuid,
                           float before, float target, int dueTick, List<SourceSnapshot> rollback,
                           ProtocolNumericInverter.NumericRollback numericRollback,
                           ProtocolStateSnapshot stateRollback,
                           List<Float> predictedHealthStates) {
    }

    record SourceSnapshot(Source source, Object value) {
    }

    private record PersistedExpectation(Class<?> entityClass, float target,
                                        List<SourceSnapshot> rollback,
                                        ProtocolNumericInverter.NumericRollback numericRollback,
                                        ProtocolStateSnapshot stateRollback) {
    }
}

