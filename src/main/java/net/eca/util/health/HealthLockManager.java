package net.eca.util.health;

import net.eca.util.EntityUtil;
import net.minecraft.world.entity.LivingEntity;

//血量锁定管理器
public class HealthLockManager {

    // 设置血量锁定
    public static void setLock(LivingEntity entity, float value) {
        if (entity == null) return;
        entity.getEntityData().set(EntityUtil.HEALTH_LOCK_ENABLED, true);
        entity.getEntityData().set(EntityUtil.HEALTH_LOCK_VALUE, value);
    }

    // 移除血量锁定
    public static void removeLock(LivingEntity entity) {
        if (entity == null) return;
        entity.getEntityData().set(EntityUtil.HEALTH_LOCK_ENABLED, false);
    }

    // 获取锁定值（如果没有锁定返回 null）
    public static Float getLock(LivingEntity entity) {
        if (entity == null) return null;
        boolean enabled = entity.getEntityData().get(EntityUtil.HEALTH_LOCK_ENABLED);
        if (!enabled) return null;
        return entity.getEntityData().get(EntityUtil.HEALTH_LOCK_VALUE);
    }

    // 检查是否被锁定
    public static boolean hasLock(LivingEntity entity) {
        if (entity == null) return false;
        return entity.getEntityData().get(EntityUtil.HEALTH_LOCK_ENABLED);
    }
}
