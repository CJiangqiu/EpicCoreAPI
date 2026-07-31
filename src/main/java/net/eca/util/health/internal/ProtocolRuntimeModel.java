package net.eca.util.health.internal;

import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime model of the verified observation, mutation capability and persistence behavior of one entity class.
 */
public final class ProtocolRuntimeModel {

    private static final Map<Class<?>, ProtocolRuntimeModel> MODELS = new ConcurrentHashMap<>();

    public enum ObservationOrigin {
        EXTERNAL,
        EFFECTIVE_HEALTH
    }

    private volatile Observation observation;
    private volatile ObservationOrigin observationOrigin;
    private volatile boolean effectiveObservationConfirmed;

    private ProtocolRuntimeModel() {}

    public static ProtocolRuntimeModel forClass(Class<?> entityClass) {
        if (entityClass == null) return null;
        return MODELS.computeIfAbsent(entityClass, ignored -> new ProtocolRuntimeModel());
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


    @FunctionalInterface
    public interface Observation {
        float read(LivingEntity entity);
    }
}


