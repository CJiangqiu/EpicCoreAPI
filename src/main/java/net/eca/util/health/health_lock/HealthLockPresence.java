package net.eca.util.health.health_lock;

/** Conservative presence hints for one entity's synchronized lock fields. */
public interface HealthLockPresence {
    boolean eca$mayHaveHealthLock();

    boolean eca$mayHaveMaxHealthLock();
}
