package net.eca.util.health;

import net.eca.util.EntityUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Executes immediate mutation actions in capability order while preserving the established fallbacks.
 */
public final class HealthMutationPipeline {

    private HealthMutationPipeline() {}

    public static Result apply(LivingEntity entity, float target) {
        EcaSetHealthManager.warmAnchorTrust(entity);
        float before = EcaSetHealthManager.safeGetHealth(entity);

        EntityUtil.setBasicHealth(entity, target);
        if (EcaSetHealthManager.verify(entity, target)) {
            return new Result(true, before);
        }
        if (entity instanceof Player) return new Result(false, before);
        if (EcaSetHealthManager.applyDataflow(entity, target)) {
            return new Result(true, before);
        }
        if (EcaSetHealthManager.applyExternalScan(entity, target)) {
            return new Result(true, before);
        }
        if (EcaSetHealthManager.applyMethodProbe(entity, target)) {
            return new Result(true, before);
        }
        if (EcaSetHealthManager.applyNumericInversion(entity, target)) {
            return new Result(true, before);
        }
        return new Result(false, before);
    }

    public record Result(boolean success, float before) {}
}
