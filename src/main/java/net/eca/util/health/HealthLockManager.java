package net.eca.util.health;

import net.eca.util.EntityUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;

//血量锁定管理器
public class HealthLockManager {

    //加密偏移量
    private static final float ENCRYPTION_OFFSET = 1024.0f;

    //NBT Key（mixin失效时的回退存储）
    private static final String NBT_HEALTH_LOCK_ENABLED = "ecaHealthLockEnabled";
    private static final String NBT_HEALTH_LOCK_VALUE = "ecaHealthLockValue";

    //设置血量锁定
    public static void setLock(LivingEntity entity, float value) {
        if (entity == null) return;
        String encrypted = encryptHealth(value);
        if (EntityUtil.HEALTH_LOCK_ENABLED != null && EntityUtil.HEALTH_LOCK_VALUE != null) {
            entity.getEntityData().set(EntityUtil.HEALTH_LOCK_ENABLED, true);
            entity.getEntityData().set(EntityUtil.HEALTH_LOCK_VALUE, encrypted);
        } else {
            CompoundTag data = entity.getPersistentData();
            data.putBoolean(NBT_HEALTH_LOCK_ENABLED, true);
            data.putString(NBT_HEALTH_LOCK_VALUE, encrypted);
        }
    }

    //移除血量锁定
    public static void removeLock(LivingEntity entity) {
        if (entity == null) return;
        if (EntityUtil.HEALTH_LOCK_ENABLED != null) {
            entity.getEntityData().set(EntityUtil.HEALTH_LOCK_ENABLED, false);
        } else {
            entity.getPersistentData().putBoolean(NBT_HEALTH_LOCK_ENABLED, false);
        }
    }

    //获取锁定值（如果没有锁定返回 null）
    public static Float getLock(LivingEntity entity) {
        if (entity == null) return null;
        boolean enabled;
        String encrypted;
        if (EntityUtil.HEALTH_LOCK_ENABLED != null && EntityUtil.HEALTH_LOCK_VALUE != null) {
            enabled = entity.getEntityData().get(EntityUtil.HEALTH_LOCK_ENABLED);
            if (!enabled) return null;
            encrypted = entity.getEntityData().get(EntityUtil.HEALTH_LOCK_VALUE);
        } else {
            CompoundTag data = entity.getPersistentData();
            enabled = data.getBoolean(NBT_HEALTH_LOCK_ENABLED);
            if (!enabled) return null;
            encrypted = data.getString(NBT_HEALTH_LOCK_VALUE);
        }
        return decryptHealth(encrypted);
    }

    //检查是否被锁定
    public static boolean hasLock(LivingEntity entity) {
        if (entity == null) return false;
        if (EntityUtil.HEALTH_LOCK_ENABLED != null) {
            return entity.getEntityData().get(EntityUtil.HEALTH_LOCK_ENABLED);
        } else {
            return entity.getPersistentData().getBoolean(NBT_HEALTH_LOCK_ENABLED);
        }
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
