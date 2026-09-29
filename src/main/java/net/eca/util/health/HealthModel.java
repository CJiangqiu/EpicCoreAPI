package net.eca.util.health;

import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 按实体类共享血量观测方式及其可信状态，供读取锚点选择和后续改血判断复用。
 * 区分外部观测与有效血量模型，单独记录模型是否已确认、是否出现过延迟回滚，
 * 避免把找到读取方式等同于写入能够持久生效；本类不负责求解或写入实体存储。
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
