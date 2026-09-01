package net.eca.util.health;

import net.eca.util.EcaLogger;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/* 覆盖整条改血链的外层事务；各通道仍保留自己的候选级快照。 */
public final class HealthWriteTransaction {
    private final ObjectGraphSnapshot snapshot;
    private boolean finished;

    private HealthWriteTransaction(ObjectGraphSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public static HealthWriteTransaction capture(LivingEntity entity) {
        return new HealthWriteTransaction(ObjectGraphSnapshot.captureProbe(entity, List.of()));
    }

    public void commit() {
        finished = true;
    }

    public boolean isComplete() {
        return snapshot.isComplete();
    }

    public boolean rollback() {
        if (finished) return true;
        boolean restored = snapshot.restore();
        finished = true;
        if (!restored) EcaLogger.info("[HealthTransaction] rollback incomplete");
        return restored;
    }
}
