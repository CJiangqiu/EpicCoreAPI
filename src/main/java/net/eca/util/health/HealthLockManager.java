package net.eca.util.health;

import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/*
 * 血量锁定管理器 — 三字段加密 + 完整性校验 + MethodHandle 间接调用
 *
 * 新加密体系（锁血 + 最大血量锁定）：
 *   VALUE  = key - (int)锁定值   （密文）
 *   KEY    = 随机 0000-9999      （setLock 时一次生成，不轮换）
 *   CHECK  = key + encrypted     （校验码）
 *
 * 读时先校验 CHECK，失败视为篡改，返回 null（记录日志）。
 * 禁疗保持旧加密（不变）。
 */
public class HealthLockManager {

    // ==================== MethodHandle 间接调用（防字节码静态分析） ====================

    private static final MethodHandle DECRYPT_MH;
    private static final MethodHandle CHECK_MH;
    private static final MethodHandle ENCRYPT_MH;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            DECRYPT_MH = lookup.findStatic(HealthLockManager.class, "decryptCore",
                    MethodType.methodType(int.class, int.class, int.class));
            CHECK_MH = lookup.findStatic(HealthLockManager.class, "computeCheck",
                    MethodType.methodType(int.class, int.class, int.class));
            ENCRYPT_MH = lookup.findStatic(HealthLockManager.class, "encryptCore",
                    MethodType.methodType(int.class, int.class, int.class));
        } catch (Exception e) {
            throw new RuntimeException("HealthLockManager: MethodHandle init failed", e);
        }
    }

    @SuppressWarnings("unused") // 通过 MethodHandle 间接调用
    private static int decryptCore(int encrypted, int key) {
        return key - encrypted;
    }

    @SuppressWarnings("unused") // 通过 MethodHandle 间接调用
    private static int computeCheck(int encrypted, int key) {
        return key + encrypted;
    }

    @SuppressWarnings("unused") // 通过 MethodHandle 间接调用
    private static int encryptCore(int value, int key) {
        return key - value;
    }

    // ==================== NBT Key ====================

    // 锁血（新加密，int）
    private static final String NBT_HEALTH_LOCK_ENC   = "ecaHealthLockEnc";
    private static final String NBT_HEALTH_LOCK_KEY   = "ecaHealthLockKey";
    private static final String NBT_HEALTH_LOCK_CHECK = "ecaHealthLockCheck";
    // 最大血量锁定（新加密，int）
    private static final String NBT_MAX_HEALTH_LOCK_ENC   = "ecaMaxHealthLockEnc";
    private static final String NBT_MAX_HEALTH_LOCK_KEY   = "ecaMaxHealthLockKey";
    private static final String NBT_MAX_HEALTH_LOCK_CHECK = "ecaMaxHealthLockCheck";
    // 禁疗（旧加密，不变）
    private static final String NBT_HEAL_BAN_VALUE = "ecaHealBanValue";

    // ==================== 快速路径 ====================

    /*
      按 entityId 记录当前有活跃锁的实体。
      绝大多数实体从未被锁定——get 方法中先查此集合，不在直接返回 null。
     */
    private static final Set<Integer> HEALTH_LOCK_IDS      = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> HEAL_BAN_IDS         = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> MAX_HEALTH_LOCK_IDS  = ConcurrentHashMap.newKeySet();

    // 从 NBT 恢复后重新填充快速路径集合（由 LivingEntityMixin.readAdditionalSaveData 调用）
    /**
     * Repopulate the fast-path sets from already-restored SynchedEntityData.
     * Called after NBT data is read back into SynchedEntityData during entity load,
     * so that {@link #getLock}, {@link #getHealBan}, and {@link #getMaxHealthLock}
     * don't return null due to an empty fast-path set.
     *
     * @param entity the entity whose fast paths should be restored
     */
    public static void restoreFastPaths(LivingEntity entity) {
        if (entity == null) return;

        // 锁血快速路径
        if (EntityUtil.HEALTH_LOCK_VALUE != null
                && EntityUtil.HEALTH_LOCK_KEY != null
                && EntityUtil.HEALTH_LOCK_CHECK != null
                && validateIntegrity(entity,
                    EntityUtil.HEALTH_LOCK_VALUE, EntityUtil.HEALTH_LOCK_KEY, EntityUtil.HEALTH_LOCK_CHECK)) {
            Integer decrypted = decrypt(entity, EntityUtil.HEALTH_LOCK_VALUE, EntityUtil.HEALTH_LOCK_KEY);
            if (decrypted != null && decrypted > 0) {
                HEALTH_LOCK_IDS.add(entity.getId());
            }
        }

        // 禁疗快速路径
        if (EntityUtil.HEAL_BAN_VALUE != null) {
            String healBan = null;
            try {
                healBan = entity.getEntityData().get(EntityUtil.HEAL_BAN_VALUE);
            } catch (Throwable ignored) {}
            if (healBan != null && !healBan.isEmpty()) {
                HEAL_BAN_IDS.add(entity.getId());
            }
        }

        // 最大血量锁定快速路径
        if (EntityUtil.MAX_HEALTH_LOCK_VALUE != null
                && EntityUtil.MAX_HEALTH_LOCK_KEY != null
                && EntityUtil.MAX_HEALTH_LOCK_CHECK != null
                && validateIntegrity(entity,
                    EntityUtil.MAX_HEALTH_LOCK_VALUE, EntityUtil.MAX_HEALTH_LOCK_KEY, EntityUtil.MAX_HEALTH_LOCK_CHECK)) {
            Integer decrypted = decrypt(entity, EntityUtil.MAX_HEALTH_LOCK_VALUE, EntityUtil.MAX_HEALTH_LOCK_KEY);
            if (decrypted != null && decrypted > 0) {
                MAX_HEALTH_LOCK_IDS.add(entity.getId());
            }
        }
    }

    // ==================== 工具方法 ====================

    private static int parseIntSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }

    private static volatile boolean synchedReadFailureLogged = false;

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

    // ==================== 新加密核心：三字段写入/清除/校验/解密 ====================

    private static void writeEncrypted(LivingEntity entity, int lockValue,
                                        EntityDataAccessor<String> encField,
                                        EntityDataAccessor<String> keyField,
                                        EntityDataAccessor<String> checkField) {
        int key = ThreadLocalRandom.current().nextInt(10000);
        int encrypted;
        int check;
        try {
            encrypted = (int) ENCRYPT_MH.invokeExact(lockValue, key);
            check     = (int) CHECK_MH.invokeExact(encrypted, key);
        } catch (Throwable e) {
            encrypted = key - lockValue;
            check     = key + encrypted;
        }
        entity.getEntityData().set(encField,   String.valueOf(encrypted));
        entity.getEntityData().set(keyField,   String.valueOf(key));
        entity.getEntityData().set(checkField, String.valueOf(check));
    }

    private static void clearEncrypted(LivingEntity entity,
                                        EntityDataAccessor<String> encField,
                                        EntityDataAccessor<String> keyField,
                                        EntityDataAccessor<String> checkField) {
        entity.getEntityData().set(encField,   "");
        entity.getEntityData().set(keyField,   "0");
        entity.getEntityData().set(checkField, "");
    }

    private static boolean validateIntegrity(LivingEntity entity,
                                              EntityDataAccessor<String> encField,
                                              EntityDataAccessor<String> keyField,
                                              EntityDataAccessor<String> checkField) {
        String encStr   = readSynchedSafely(entity, encField);
        String keyStr   = readSynchedSafely(entity, keyField);
        String checkStr = readSynchedSafely(entity, checkField);
        if (encStr == null || encStr.isEmpty()
                || keyStr == null || keyStr.isEmpty()
                || checkStr == null || checkStr.isEmpty()) return false;
        int encrypted    = parseIntSafe(encStr);
        int key          = parseIntSafe(keyStr);
        int storedCheck  = parseIntSafe(checkStr);
        try {
            int expected = (int) CHECK_MH.invokeExact(encrypted, key);
            return storedCheck == expected;
        } catch (Throwable e) {
            return key + encrypted == storedCheck;
        }
    }

    // 返回解密值，无法读取或字段为空返回 null
    private static Integer decrypt(LivingEntity entity,
                                    EntityDataAccessor<String> encField,
                                    EntityDataAccessor<String> keyField) {
        String encStr = readSynchedSafely(entity, encField);
        String keyStr = readSynchedSafely(entity, keyField);
        if (encStr == null || encStr.isEmpty() || keyStr == null || keyStr.isEmpty()) return null;
        int encrypted = parseIntSafe(encStr);
        int key       = parseIntSafe(keyStr);
        try {
            return (int) DECRYPT_MH.invokeExact(encrypted, key);
        } catch (Throwable e) {
            return key - encrypted;
        }
    }

    // ==================== NBT 回退：三字段写入/清除/解密 ====================

    private static void writeNbtEncrypted(CompoundTag data, int lockValue,
                                           String encKey, String keyKey, String checkKey) {
        int key = ThreadLocalRandom.current().nextInt(10000);
        int encrypted;
        int check;
        try {
            encrypted = (int) ENCRYPT_MH.invokeExact(lockValue, key);
            check     = (int) CHECK_MH.invokeExact(encrypted, key);
        } catch (Throwable e) {
            encrypted = key - lockValue;
            check     = key + encrypted;
        }
        data.putInt(encKey,   encrypted);
        data.putInt(keyKey,   key);
        data.putInt(checkKey, check);
    }

    private static Integer readNbtDecrypt(CompoundTag data, String encKey, String keyKey, String checkKey) {
        int encrypted   = data.getInt(encKey);
        int key         = data.getInt(keyKey);
        int storedCheck = data.getInt(checkKey);
        if (encrypted == 0 && key == 0 && storedCheck == 0) return null;
        int expected;
        try {
            expected = (int) CHECK_MH.invokeExact(encrypted, key);
        } catch (Throwable e) {
            expected = key + encrypted;
        }
        if (storedCheck != expected) {
            EcaLogger.info("[HealthLock] NBT integrity check failed enc={} key={} expected={} stored={}",
                    encrypted, key, expected, storedCheck);
            return null;
        }
        try {
            return (int) DECRYPT_MH.invokeExact(encrypted, key);
        } catch (Throwable e) {
            return key - encrypted;
        }
    }

    // ==================== 血量锁定（新加密） ====================

    public static void setLock(LivingEntity entity, float value) {
        if (entity == null) return;
        int lockValue = Math.max(1, (int) value);
        HEALTH_LOCK_IDS.add(entity.getId());
        if (EntityUtil.HEALTH_LOCK_VALUE != null
                && EntityUtil.HEALTH_LOCK_KEY != null
                && EntityUtil.HEALTH_LOCK_CHECK != null) {
            writeEncrypted(entity, lockValue,
                    EntityUtil.HEALTH_LOCK_VALUE, EntityUtil.HEALTH_LOCK_KEY, EntityUtil.HEALTH_LOCK_CHECK);
        } else {
            writeNbtEncrypted(entity.getPersistentData(), lockValue,
                    NBT_HEALTH_LOCK_ENC, NBT_HEALTH_LOCK_KEY, NBT_HEALTH_LOCK_CHECK);
        }
    }

    public static void removeLock(LivingEntity entity) {
        if (entity == null) return;
        HEALTH_LOCK_IDS.remove(entity.getId());
        if (EntityUtil.HEALTH_LOCK_VALUE != null
                && EntityUtil.HEALTH_LOCK_KEY != null
                && EntityUtil.HEALTH_LOCK_CHECK != null) {
            clearEncrypted(entity,
                    EntityUtil.HEALTH_LOCK_VALUE, EntityUtil.HEALTH_LOCK_KEY, EntityUtil.HEALTH_LOCK_CHECK);
        } else {
            CompoundTag data = entity.getPersistentData();
            data.putInt(NBT_HEALTH_LOCK_ENC, 0);
            data.putInt(NBT_HEALTH_LOCK_KEY, 0);
            data.putInt(NBT_HEALTH_LOCK_CHECK, 0);
        }
    }

    public static Float getLock(LivingEntity entity) {
        if (entity == null) return null;
        if (!HEALTH_LOCK_IDS.contains(entity.getId())) return null;

        if (EntityUtil.HEALTH_LOCK_VALUE != null
                && EntityUtil.HEALTH_LOCK_KEY != null
                && EntityUtil.HEALTH_LOCK_CHECK != null) {
            if (!validateIntegrity(entity,
                    EntityUtil.HEALTH_LOCK_VALUE, EntityUtil.HEALTH_LOCK_KEY, EntityUtil.HEALTH_LOCK_CHECK)) {
                EcaLogger.info("[HealthLock] integrity check failed entity={} id={}",
                        entity.getClass().getName(), entity.getId());
                return null;
            }
            Integer decrypted = decrypt(entity, EntityUtil.HEALTH_LOCK_VALUE, EntityUtil.HEALTH_LOCK_KEY);
            return decrypted != null && decrypted > 0 ? (float) decrypted : null;
        }
        // NBT 回退
        Integer nbtResult = readNbtDecrypt(entity.getPersistentData(),
                NBT_HEALTH_LOCK_ENC, NBT_HEALTH_LOCK_KEY, NBT_HEALTH_LOCK_CHECK);
        return nbtResult != null && nbtResult > 0 ? (float) nbtResult.intValue() : null;
    }

    // ==================== 最大血量锁定（新加密） ====================

    public static void setMaxHealthLock(LivingEntity entity, float value) {
        if (entity == null) return;
        int lockValue = Math.max(1, (int) value);
        MAX_HEALTH_LOCK_IDS.add(entity.getId());
        if (EntityUtil.MAX_HEALTH_LOCK_VALUE != null
                && EntityUtil.MAX_HEALTH_LOCK_KEY != null
                && EntityUtil.MAX_HEALTH_LOCK_CHECK != null) {
            writeEncrypted(entity, lockValue,
                    EntityUtil.MAX_HEALTH_LOCK_VALUE, EntityUtil.MAX_HEALTH_LOCK_KEY, EntityUtil.MAX_HEALTH_LOCK_CHECK);
        } else {
            writeNbtEncrypted(entity.getPersistentData(), lockValue,
                    NBT_MAX_HEALTH_LOCK_ENC, NBT_MAX_HEALTH_LOCK_KEY, NBT_MAX_HEALTH_LOCK_CHECK);
        }
    }

    public static void removeMaxHealthLock(LivingEntity entity) {
        if (entity == null) return;
        MAX_HEALTH_LOCK_IDS.remove(entity.getId());
        if (EntityUtil.MAX_HEALTH_LOCK_VALUE != null
                && EntityUtil.MAX_HEALTH_LOCK_KEY != null
                && EntityUtil.MAX_HEALTH_LOCK_CHECK != null) {
            clearEncrypted(entity,
                    EntityUtil.MAX_HEALTH_LOCK_VALUE, EntityUtil.MAX_HEALTH_LOCK_KEY, EntityUtil.MAX_HEALTH_LOCK_CHECK);
        } else {
            CompoundTag data = entity.getPersistentData();
            data.putInt(NBT_MAX_HEALTH_LOCK_ENC, 0);
            data.putInt(NBT_MAX_HEALTH_LOCK_KEY, 0);
            data.putInt(NBT_MAX_HEALTH_LOCK_CHECK, 0);
        }
    }

    public static Float getMaxHealthLock(LivingEntity entity) {
        if (entity == null) return null;
        if (!MAX_HEALTH_LOCK_IDS.contains(entity.getId())) return null;

        if (EntityUtil.MAX_HEALTH_LOCK_VALUE != null
                && EntityUtil.MAX_HEALTH_LOCK_KEY != null
                && EntityUtil.MAX_HEALTH_LOCK_CHECK != null) {
            if (!validateIntegrity(entity,
                    EntityUtil.MAX_HEALTH_LOCK_VALUE, EntityUtil.MAX_HEALTH_LOCK_KEY, EntityUtil.MAX_HEALTH_LOCK_CHECK)) {
                EcaLogger.info("[HealthLock] max health integrity check failed entity={} id={}",
                        entity.getClass().getName(), entity.getId());
                return null;
            }
            Integer decrypted = decrypt(entity, EntityUtil.MAX_HEALTH_LOCK_VALUE, EntityUtil.MAX_HEALTH_LOCK_KEY);
            return decrypted != null && decrypted > 0 ? (float) decrypted : null;
        }
        // NBT 回退
        Integer nbtResult = readNbtDecrypt(entity.getPersistentData(),
                NBT_MAX_HEALTH_LOCK_ENC, NBT_MAX_HEALTH_LOCK_KEY, NBT_MAX_HEALTH_LOCK_CHECK);
        return nbtResult != null && nbtResult > 0 ? (float) nbtResult.intValue() : null;
    }

    // ==================== 禁疗（旧加密，不变） ====================

    private static final float ENCRYPTION_OFFSET = 1024.0f;

    public static void setHealBan(LivingEntity entity, float value) {
        if (entity == null) return;
        HEAL_BAN_IDS.add(entity.getId());
        String encrypted = Float.toString(value - ENCRYPTION_OFFSET);
        if (EntityUtil.HEAL_BAN_VALUE != null) {
            entity.getEntityData().set(EntityUtil.HEAL_BAN_VALUE, encrypted);
        } else {
            entity.getPersistentData().putString(NBT_HEAL_BAN_VALUE, encrypted);
        }
    }

    public static void removeHealBan(LivingEntity entity) {
        if (entity == null) return;
        HEAL_BAN_IDS.remove(entity.getId());
        if (EntityUtil.HEAL_BAN_VALUE != null) {
            entity.getEntityData().set(EntityUtil.HEAL_BAN_VALUE, "");
        } else {
            entity.getPersistentData().putString(NBT_HEAL_BAN_VALUE, "");
        }
    }

    public static Float getHealBan(LivingEntity entity) {
        if (entity == null) return null;
        if (!HEAL_BAN_IDS.contains(entity.getId())) return null;
        String encrypted;
        if (EntityUtil.HEAL_BAN_VALUE != null) {
            encrypted = readSynchedSafely(entity, EntityUtil.HEAL_BAN_VALUE);
        } else {
            encrypted = entity.getPersistentData().getString(NBT_HEAL_BAN_VALUE);
        }
        if (encrypted == null || encrypted.isEmpty()) return null;
        try { return Float.parseFloat(encrypted) + ENCRYPTION_OFFSET; }
        catch (NumberFormatException e) { return 0.0f; }
    }
}
