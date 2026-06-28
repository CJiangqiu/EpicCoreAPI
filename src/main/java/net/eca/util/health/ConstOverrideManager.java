package net.eca.util.health;

import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;
import net.minecraft.world.entity.LivingEntity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 常数语义实体的 getHealth 覆盖表：按 UUID 记录改血目标值。
 * 仅用于 getHealth 返回不可变常数、无可写存储的实体——其余实体走真改存储路径，不进此表。
 * resolveHealth() 被编译到 CONSTANT 实体的 getHealth 每个 FRETURN 前：
 * 配置开启时查 OVERRIDES 表返回覆盖值（无覆盖则返回原常数），配置关闭时直接返回原值。
 * 全局 HEAD hook 不再需要检查 ConstOverrideManager。
 */
public final class ConstOverrideManager {

    private static final ConcurrentHashMap<UUID, Float> OVERRIDES = new ConcurrentHashMap<>();

    /* 已打 FRETURN patch 的类内部名，避免重复 retransform */
    public static final Set<String> PATCHED_CLASSES = ConcurrentHashMap.newKeySet();

    /* 首次调用的实体类打印一次诊断，确认 FRETURN patch 生效 */
    private static final Set<String> DIAG_DUMPED = ConcurrentHashMap.newKeySet();

    private ConstOverrideManager() {}

    /* 由 patched 字节码在每个 FRETURN 前调用。
       entity 是 this，originalValue 是原字节码中的常数值。
       配置未开启时直接返回 originalValue（零开销，零行为变化）。 */
    public static float resolveHealth(LivingEntity entity, float originalValue) {
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()
                || !EcaConfiguration.getAttackSetHealthEnableConstOverrideSafely()) {
            return originalValue;
        }
        Float override = OVERRIDES.get(entity.getUUID());
        float result = override != null ? override : originalValue;
        if (DIAG_DUMPED.add(entity.getClass().getName())) {
            EcaLogger.info("[ConstOverride] resolveHealth entity={} original={} override={} result={}",
                    entity.getClass().getName(), originalValue, override, result);
        }
        return result;
    }

    public static void setOverride(LivingEntity entity, float value) {
        if (entity != null) OVERRIDES.put(entity.getUUID(), value);
    }

    public static Float getOverride(LivingEntity entity) {
        return entity == null ? null : OVERRIDES.get(entity.getUUID());
    }

    public static void removeOverride(LivingEntity entity) {
        if (entity != null) OVERRIDES.remove(entity.getUUID());
    }

    public static void clear() {
        OVERRIDES.clear();
    }
}
