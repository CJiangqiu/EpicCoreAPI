package net.eca.coremod;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Typed boundary used by the optional runtime fallback without remapped game references.
 */
public final class EcaProFallbackHooks {
    private EcaProFallbackHooks() {
    }

    public static Float getHealthHead(Object instance) {
        if (!(instance instanceof LivingEntity entity)) return null;
        float value = LivingEntityHook.processGetHealth(entity);
        return Float.isNaN(value) ? null : value;
    }

    public static Float getHealthReturn(Object instance, Object original) {
        if (!(instance instanceof LivingEntity entity) || !(original instanceof Number number)) return null;
        return LivingEntityHook.processGetHealthResult(entity, number.floatValue());
    }

    public static Float getMaxHealth(Object instance) {
        if (!(instance instanceof LivingEntity entity)) return null;
        float value = LivingEntityHook.processGetMaxHealth(entity);
        return Float.isNaN(value) ? null : value;
    }

    public static Boolean isDeadOrDying(Object instance) {
        if (!(instance instanceof LivingEntity entity)) return null;
        int value = LivingEntityHook.processIsDeadOrDying(entity);
        return value < 0 ? null : value != 0;
    }

    public static Boolean isAlive(Object instance) {
        if (!(instance instanceof LivingEntity entity)) return null;
        int value = LivingEntityHook.processIsAlive(entity);
        return value < 0 ? null : value != 0;
    }

    public static Boolean isRemoved(Object instance) {
        if (!(instance instanceof Entity entity)) return null;
        int value = EntityHook.processIsRemoved(entity);
        return value < 0 ? null : value != 0;
    }
}
