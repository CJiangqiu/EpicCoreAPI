package net.eca.util.health;

import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;

//血量锁定管理器
public class HealthLockManager {

    //加密偏移量
    private static final float ENCRYPTION_OFFSET = 1024.0f;

    //NBT Key（mixin失效时的回退存储）
    private static final String NBT_HEALTH_LOCK_VALUE = "ecaHealthLockValue";
    private static final String NBT_HEAL_BAN_VALUE = "ecaHealBanValue";
    private static final String NBT_MAX_HEALTH_LOCK_VALUE = "ecaMaxHealthLockValue";

    //SynchedEntityData 读取失败日志的一次性开关，避免构造期对每个实体刷屏
    private static volatile boolean synchedReadFailureLogged = false;

    /*
     getHealth/getMaxHealth 的 HEAD hook 会在 LivingEntity 构造函数里就触发本类的读取，此时实体尚未构造完成。
     若其它 mod 在 SynchedEntityData.get 上挂了 mixin（如读取 gameMode、isCreative），就可能在这一刻抛异常导致玩家无法进入世界。
     这里把读取兜住，任何 Throwable 都退化为"无锁定"（返回 null）放行回原版，绝不缓存失败结果，每次仍真实重读。
    */
    private static String readSynchedSafely(LivingEntity entity, EntityDataAccessor<String> accessor) {
        try {
            return entity.getEntityData().get(accessor);
        } catch (Throwable t) {
            if (!synchedReadFailureLogged) {
                synchedReadFailureLogged = true;
                EcaLogger.info("Skipped health-lock SynchedEntityData read (entity likely under construction or third-party mixin conflict): " + t);
            }
            return null;
        }
    }

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
            encrypted = readSynchedSafely(entity, EntityUtil.HEALTH_LOCK_VALUE);
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
        String encrypted = encryptHealth(value);
        if (EntityUtil.HEAL_BAN_VALUE != null) {
            entity.getEntityData().set(EntityUtil.HEAL_BAN_VALUE, encrypted);
        } else {
            CompoundTag data = entity.getPersistentData();
            data.putString(NBT_HEAL_BAN_VALUE, encrypted);
        }
    }

    //移除禁疗
    public static void removeHealBan(LivingEntity entity) {
        if (entity == null) return;
        if (EntityUtil.HEAL_BAN_VALUE != null) {
            entity.getEntityData().set(EntityUtil.HEAL_BAN_VALUE, "");
        } else {
            entity.getPersistentData().putString(NBT_HEAL_BAN_VALUE, "");
        }
    }

    //获取禁疗值（如果没有禁疗返回 null）
    public static Float getHealBan(LivingEntity entity) {
        if (entity == null) return null;
        String encrypted;
        if (EntityUtil.HEAL_BAN_VALUE != null) {
            encrypted = readSynchedSafely(entity, EntityUtil.HEAL_BAN_VALUE);
        } else {
            CompoundTag data = entity.getPersistentData();
            encrypted = data.getString(NBT_HEAL_BAN_VALUE);
        }
        if (encrypted == null || encrypted.isEmpty()) {
            return null;
        }
        return decryptHealth(encrypted);
    }

    //设置最大生命值锁定
    public static void setMaxHealthLock(LivingEntity entity, float value) {
        if (entity == null) return;
        String encrypted = encryptHealth(value);
        if (EntityUtil.MAX_HEALTH_LOCK_VALUE != null) {
            entity.getEntityData().set(EntityUtil.MAX_HEALTH_LOCK_VALUE, encrypted);
        } else {
            entity.getPersistentData().putString(NBT_MAX_HEALTH_LOCK_VALUE, encrypted);
        }
    }

    //移除最大生命值锁定
    public static void removeMaxHealthLock(LivingEntity entity) {
        if (entity == null) return;
        String unlockedValue = encryptHealth(0.0f);
        if (EntityUtil.MAX_HEALTH_LOCK_VALUE != null) {
            entity.getEntityData().set(EntityUtil.MAX_HEALTH_LOCK_VALUE, unlockedValue);
        } else {
            entity.getPersistentData().putString(NBT_MAX_HEALTH_LOCK_VALUE, unlockedValue);
        }
    }

    //获取最大生命值锁定值（如果没有锁定返回 null）
    public static Float getMaxHealthLock(LivingEntity entity) {
        if (entity == null) return null;
        String encrypted;
        if (EntityUtil.MAX_HEALTH_LOCK_VALUE != null) {
            encrypted = readSynchedSafely(entity, EntityUtil.MAX_HEALTH_LOCK_VALUE);
        } else {
            encrypted = entity.getPersistentData().getString(NBT_MAX_HEALTH_LOCK_VALUE);
        }
        if (encrypted == null || encrypted.isEmpty()) {
            return null;
        }
        float decrypted = decryptHealth(encrypted);
        return decrypted > 0.0f ? decrypted : null;
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
