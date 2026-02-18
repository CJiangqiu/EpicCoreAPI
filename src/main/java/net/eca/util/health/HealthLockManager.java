package net.eca.util.health;

import net.eca.util.EntityUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;

//血量锁定管理器
public class HealthLockManager {

    //加密偏移量
    private static final float ENCRYPTION_OFFSET = 1024.0f;

    //NBT Key（mixin失效时的回退存储）
    private static final String NBT_HEALTH_LOCK_VALUE = "ecaHealthLockValue";
    private static final String NBT_HEAL_BAN_VALUE = "ecaHealBanValue";
    private static final String NBT_MAX_HEALTH_LOCK_VALUE = "ecaMaxHealthLockValue";

    //设置血量锁定
    public static void setLock(LivingEntity entity, float value) {
        if (entity == null) return;
        String encrypted = encryptHealth(value);
        if (EntityUtil.HEALTH_LOCK_VALUE != null) {
            entity.getEntityData().set(EntityUtil.HEALTH_LOCK_VALUE, encrypted);
        } else {
            CompoundTag data = entity.getPersistentData();
            data.putString(NBT_HEALTH_LOCK_VALUE, encrypted);
        }
    }

    //移除血量锁定
    public static void removeLock(LivingEntity entity) {
        if (entity == null) return;
        String unlockedValue = encryptHealth(0.0f);
        if (EntityUtil.HEALTH_LOCK_VALUE != null) {
            entity.getEntityData().set(EntityUtil.HEALTH_LOCK_VALUE, unlockedValue);
        } else {
            entity.getPersistentData().putString(NBT_HEALTH_LOCK_VALUE, unlockedValue);
        }
    }

    //获取锁定值（如果没有锁定返回 null）
    public static Float getLock(LivingEntity entity) {
        if (entity == null) return null;
        String encrypted;
        if (EntityUtil.HEALTH_LOCK_VALUE != null) {
            encrypted = entity.getEntityData().get(EntityUtil.HEALTH_LOCK_VALUE);
        } else {
            CompoundTag data = entity.getPersistentData();
            encrypted = data.getString(NBT_HEALTH_LOCK_VALUE);
        }
        if (encrypted == null || encrypted.isEmpty()) {
            return null;
        }
        float decrypted = decryptHealth(encrypted);
        return decrypted > 0.0f ? decrypted : null;
    }

    //设置禁疗
    public static void setHealBan(LivingEntity entity, float value) {
        if (entity == null) return;
        if (EntityUtil.HEAL_BAN_VALUE != null) {
            entity.getEntityData().set(EntityUtil.HEAL_BAN_VALUE, value);
        } else {
            CompoundTag data = entity.getPersistentData();
            data.putFloat(NBT_HEAL_BAN_VALUE, value);
        }
    }

    //移除禁疗
    public static void removeHealBan(LivingEntity entity) {
        if (entity == null) return;
        if (EntityUtil.HEAL_BAN_VALUE != null) {
            entity.getEntityData().set(EntityUtil.HEAL_BAN_VALUE, 0.0f);
        } else {
            CompoundTag data = entity.getPersistentData();
            data.putFloat(NBT_HEAL_BAN_VALUE, 0.0f);
        }
    }

    //获取禁疗值（如果没有禁疗返回 null）
    public static Float getHealBan(LivingEntity entity) {
        if (entity == null) return null;
        Float value;
        if (EntityUtil.HEAL_BAN_VALUE != null) {
            value = entity.getEntityData().get(EntityUtil.HEAL_BAN_VALUE);
        } else {
            CompoundTag data = entity.getPersistentData();
            value = data.getFloat(NBT_HEAL_BAN_VALUE);
        }
        return value != null && value > 0.0f ? value : null;
    }

    //设置最大生命值锁定
    public static void setMaxHealthLock(LivingEntity entity, float value) {
        if (entity == null) return;
        if (EntityUtil.MAX_HEALTH_LOCK_VALUE != null) {
            entity.getEntityData().set(EntityUtil.MAX_HEALTH_LOCK_VALUE, value);
        } else {
            entity.getPersistentData().putFloat(NBT_MAX_HEALTH_LOCK_VALUE, value);
        }
    }

    //移除最大生命值锁定
    public static void removeMaxHealthLock(LivingEntity entity) {
        if (entity == null) return;
        if (EntityUtil.MAX_HEALTH_LOCK_VALUE != null) {
            entity.getEntityData().set(EntityUtil.MAX_HEALTH_LOCK_VALUE, 0.0f);
        } else {
            entity.getPersistentData().putFloat(NBT_MAX_HEALTH_LOCK_VALUE, 0.0f);
        }
    }

    //获取最大生命值锁定值（如果没有锁定返回 null）
    public static Float getMaxHealthLock(LivingEntity entity) {
        if (entity == null) return null;
        Float value;
        if (EntityUtil.MAX_HEALTH_LOCK_VALUE != null) {
            value = entity.getEntityData().get(EntityUtil.MAX_HEALTH_LOCK_VALUE);
        } else {
            value = entity.getPersistentData().getFloat(NBT_MAX_HEALTH_LOCK_VALUE);
        }
        return value != null && value > 0.0f ? value : null;
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
