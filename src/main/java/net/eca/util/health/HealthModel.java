package net.eca.util.health;

import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime model of the verified observation, mutation capability and persistence behavior of one entity class.
 */
public final class HealthModel {

    private static final Map<Class<?>, HealthModel> MODELS = new ConcurrentHashMap<>();

    public enum ObservationOrigin {
        EXTERNAL,
        EFFECTIVE_HEALTH
    }

    private volatile Observation observation;
    private volatile ObservationOrigin observationOrigin;
    private volatile boolean effectiveObservationConfirmed;
    private volatile boolean delayedRollbackObserved;

    private HealthModel() {}

    public static HealthModel forClass(Class<?> entityClass) {
        if (entityClass == null) return null;
        return MODELS.computeIfAbsent(entityClass, ignored -> new HealthModel());
    }

    public Observation observation() {
        return observation;
    }

    public ObservationOrigin observationOrigin() {
        return observationOrigin;
    }

    public void setObservation(Observation observation, ObservationOrigin origin) {
        this.observation = observation;
        this.observationOrigin = observation == null ? null : origin;
    }

    public void clearEffectiveObservation() {
        if (observationOrigin == ObservationOrigin.EFFECTIVE_HEALTH) setObservation(null, null);
    }

    public boolean effectiveObservationConfirmed() {
        return effectiveObservationConfirmed;
    }

    public void setEffectiveObservationConfirmed(boolean confirmed) {
        effectiveObservationConfirmed = confirmed;
    }

    public boolean delayedRollbackObserved() {
        return delayedRollbackObserved;
    }

    public void markDelayedRollbackObserved() {
        delayedRollbackObserved = true;
    }

    @FunctionalInterface
    public interface Observation {
        float read(LivingEntity entity);
    }
}
