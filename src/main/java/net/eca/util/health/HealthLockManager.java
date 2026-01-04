package net.eca.util.health;

import net.eca.util.EntityUtil;
import net.minecraft.world.entity.LivingEntity;

//血量锁定管理器
public class HealthLockManager {

    //加密偏移量
    private static final float ENCRYPTION_OFFSET = 1024.0f;

    //设置血量锁定
    public static void setLock(LivingEntity entity, float value) {
        if (entity == null) return;
        entity.getEntityData().set(EntityUtil.HEALTH_LOCK_ENABLED, true);
        String encrypted = encryptHealth(value);
        entity.getEntityData().set(EntityUtil.HEALTH_LOCK_VALUE, encrypted);
    }

    //移除血量锁定
    public static void removeLock(LivingEntity entity) {
        if (entity == null) return;
        entity.getEntityData().set(EntityUtil.HEALTH_LOCK_ENABLED, false);
    }

    //获取锁定值（如果没有锁定返回 null）
    public static Float getLock(LivingEntity entity) {
        if (entity == null) return null;
        boolean enabled = entity.getEntityData().get(EntityUtil.HEALTH_LOCK_ENABLED);
        if (!enabled) return null;
        String encrypted = entity.getEntityData().get(EntityUtil.HEALTH_LOCK_VALUE);
        return decryptHealth(encrypted);
    }

    //检查是否被锁定
    public static boolean hasLock(LivingEntity entity) {
        if (entity == null) return false;
        return entity.getEntityData().get(EntityUtil.HEALTH_LOCK_ENABLED);
    }

    //加密血量值
    private static String encryptHealth(float health) {
        return Float.toString(health - ENCRYPTION_OFFSET);
    }

    //解密血量值
    private static Float decryptHealth(String encrypted) {
        try {
            return Float.parseFloat(encrypted) + ENCRYPTION_OFFSET;
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }
}
