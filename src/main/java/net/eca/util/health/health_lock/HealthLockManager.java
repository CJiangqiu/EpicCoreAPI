package net.eca.util.health.health_lock;

import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.eca.util.health.EcaOwnedState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/*
 * 服务端权威锁使用进程私钥与动态 nonce 编码，不进入实体同步数据。
 * 三个同步字段仅承担客户端表现和旧存档迁移，篡改它们不能解除服务端锁定。
 */
public class HealthLockManager {

    private static final String FLOAT_PAYLOAD_PREFIX = "F:";
    private static final long INVALID_PAYLOAD = -1L;
    private static final long HEALTH_LOCK_DOMAIN = 0x4845414C54484C4FL;
    private static final long MAX_HEALTH_LOCK_DOMAIN = 0x4D41584845414C54L;
    private static final long VALUE_SECRET = ThreadLocalRandom.current().nextLong();
    private static final long TAG_SECRET = ThreadLocalRandom.current().nextLong();

    private static final Map<UUID, LockRecord> HEALTH_LOCKS = new ConcurrentHashMap<>();
    private static final Map<UUID, LockRecord> MAX_HEALTH_LOCKS = new ConcurrentHashMap<>();

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

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static long entityIdentity(UUID entityId, long domain) {
        return entityId.getMostSignificantBits()
                ^ Long.rotateLeft(entityId.getLeastSignificantBits(), 29)
                ^ domain;
    }

    private static long payloadMask(UUID entityId, long nonce, long domain) {
        return mix64(VALUE_SECRET ^ entityIdentity(entityId, domain) ^ nonce);
    }

    private static long payloadTag(UUID entityId, long nonce, long encoded, long domain) {
        return mix64(TAG_SECRET ^ entityIdentity(entityId, domain)
                ^ Long.rotateLeft(nonce, 17) ^ encoded);
    }

    private static long decodeSlot(UUID entityId, long nonce, long encoded, long tag, long domain) {
        if (tag != payloadTag(entityId, nonce, encoded, domain)) return INVALID_PAYLOAD;
        long payload = encoded ^ payloadMask(entityId, nonce, domain);
        return (payload & 0xFFFFFFFF00000000L) == 0L ? payload : INVALID_PAYLOAD;
    }

    private static final class LockRecord {
        private volatile long nonceA;
        private volatile long encodedA;
        private volatile long tagA;
        private volatile long nonceB;
        private volatile long encodedB;
        private volatile long tagB;
        private volatile int activeSlot;
        private volatile int lastRotationTick = Integer.MIN_VALUE;
        private volatile int lastRepairTick = Integer.MIN_VALUE;
        private volatile boolean damageLogged;

        private LockRecord(UUID entityId, int payload, long domain) {
            writeSlot(entityId, payload, domain, 0, ThreadLocalRandom.current().nextLong());
            writeSlot(entityId, payload, domain, 1, ThreadLocalRandom.current().nextLong());
            activeSlot = 1;
        }

        private long readPayload(UUID entityId, long domain) {
            int preferred = activeSlot;
            long payload = readSlot(entityId, domain, preferred);
            return payload != INVALID_PAYLOAD ? payload : readSlot(entityId, domain, preferred ^ 1);
        }

        private void rotateIfNeeded(UUID entityId, long domain, int tickCount) {
            if (lastRotationTick == tickCount) return;
            long payload = readPayload(entityId, domain);
            if (payload == INVALID_PAYLOAD) return;
            int nextSlot = activeSlot ^ 1;
            writeSlot(entityId, (int) payload, domain, nextSlot, ThreadLocalRandom.current().nextLong());
            activeSlot = nextSlot;
            lastRotationTick = tickCount;
        }

        private boolean beginRepair(int tickCount, int entityId) {
            if ((tickCount & 15) != (entityId & 15) || lastRepairTick == tickCount) return false;
            lastRepairTick = tickCount;
            return true;
        }

        private long readSlot(UUID entityId, long domain, int slot) {
            if (slot == 0) {
                return decodeSlot(entityId, nonceA, encodedA, tagA, domain);
            }
            return decodeSlot(entityId, nonceB, encodedB, tagB, domain);
        }

        private void writeSlot(UUID entityId, int payload, long domain, int slot, long nonce) {
            long encoded = (payload & 0xFFFFFFFFL) ^ payloadMask(entityId, nonce, domain);
            long tag = payloadTag(entityId, nonce, encoded, domain);
            if (slot == 0) {
                nonceA = nonce;
                encodedA = encoded;
                tagA = tag;
            } else {
                nonceB = nonce;
                encodedB = encoded;
                tagB = tag;
            }
        }
    }

    // ==================== NBT Key ====================

    // 锁血（新加密，int）
    // 键在 EcaOwnedState 集中登记，改血分析据同一份清单排除 ECA 自身注入
    private static final String NBT_HEALTH_LOCK_ENC   = EcaOwnedState.NBT_HEALTH_LOCK_ENC;
    private static final String NBT_HEALTH_LOCK_KEY   = EcaOwnedState.NBT_HEALTH_LOCK_KEY;
    private static final String NBT_HEALTH_LOCK_CHECK = EcaOwnedState.NBT_HEALTH_LOCK_CHECK;
    // 最大血量锁定（新加密，int）
    private static final String NBT_MAX_HEALTH_LOCK_ENC   = EcaOwnedState.NBT_MAX_HEALTH_LOCK_ENC;
    private static final String NBT_MAX_HEALTH_LOCK_KEY   = EcaOwnedState.NBT_MAX_HEALTH_LOCK_KEY;
    private static final String NBT_MAX_HEALTH_LOCK_CHECK = EcaOwnedState.NBT_MAX_HEALTH_LOCK_CHECK;
    // 禁疗（旧加密，不变）
    private static final String NBT_HEAL_BAN_VALUE = EcaOwnedState.NBT_HEAL_BAN_VALUE;

    // ==================== 快速路径 ====================

    // 禁疗保留按 entityId 的旧快速路径，避免未启用实体解析同步字符串。
    private static final Set<Integer> HEAL_BAN_IDS         = ConcurrentHashMap.newKeySet();

    // 从旧存档同步字段恢复服务端权威记录与禁疗快速路径。
    /**
     * Restore authoritative lock records and fast paths from migrated entity data.
     * Called after saved fields have been copied into SynchedEntityData during entity load.
     *
     * @param entity the entity whose fast paths should be restored
     */
    public static void restoreFastPaths(LivingEntity entity) {
        if (entity == null) return;

        restoreAuthoritativeLock(entity, HEALTH_LOCKS, HEALTH_LOCK_DOMAIN,
                EntityUtil.HEALTH_LOCK_VALUE, EntityUtil.HEALTH_LOCK_KEY, EntityUtil.HEALTH_LOCK_CHECK);

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

        restoreAuthoritativeLock(entity, MAX_HEALTH_LOCKS, MAX_HEALTH_LOCK_DOMAIN,
                EntityUtil.MAX_HEALTH_LOCK_VALUE, EntityUtil.MAX_HEALTH_LOCK_KEY,
                EntityUtil.MAX_HEALTH_LOCK_CHECK);
    }

    private static void restoreAuthoritativeLock(LivingEntity entity, Map<UUID, LockRecord> records, long domain,
                                                  EntityDataAccessor<String> encField,
                                                  EntityDataAccessor<String> keyField,
                                                  EntityDataAccessor<String> checkField) {
        if (entity.level().isClientSide || records.containsKey(entity.getUUID())
                || encField == null || keyField == null || checkField == null
                || !validateIntegrity(entity, encField, keyField, checkField)) return;
        Float value = decryptLockValue(entity, encField, keyField);
        if (value != null) {
            records.put(entity.getUUID(), new LockRecord(entity.getUUID(), encodeFloatPayload(value), domain));
        }
    }

    // ==================== 工具方法 ====================

    private static int parseIntSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }

    private static boolean isFloatPayload(String encrypted) {
        return encrypted != null && encrypted.startsWith(FLOAT_PAYLOAD_PREFIX);
    }

    private static int parseEncryptedPayload(String encrypted) {
        if (isFloatPayload(encrypted)) {
            return parseIntSafe(encrypted.substring(FLOAT_PAYLOAD_PREFIX.length()));
        }
        return parseIntSafe(encrypted);
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

    private static void writeEncrypted(LivingEntity entity, float lockValue,
                                        EntityDataAccessor<String> encField,
                                        EntityDataAccessor<String> keyField,
                                        EntityDataAccessor<String> checkField) {
        int payload = encodeFloatPayload(lockValue);
        int key = ThreadLocalRandom.current().nextInt(10000);
        int encrypted;
        int check;
        try {
            encrypted = (int) ENCRYPT_MH.invokeExact(payload, key);
            check     = (int) CHECK_MH.invokeExact(encrypted, key);
        } catch (Throwable e) {
            encrypted = key - payload;
            check     = key + encrypted;
        }
        entity.getEntityData().set(encField,   FLOAT_PAYLOAD_PREFIX + encrypted);
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
        String encStr = readSynchedSafely(entity, encField);
        if (encStr == null || encStr.isEmpty()) return false;
        String keyStr = readSynchedSafely(entity, keyField);
        if (keyStr == null || keyStr.isEmpty()) return false;
        String checkStr = readSynchedSafely(entity, checkField);
        if (checkStr == null || checkStr.isEmpty()) return false;
        int encrypted    = parseEncryptedPayload(encStr);
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
    private static Float decryptLockValue(LivingEntity entity,
                                          EntityDataAccessor<String> encField,
                                          EntityDataAccessor<String> keyField) {
        String encStr = readSynchedSafely(entity, encField);
        String keyStr = readSynchedSafely(entity, keyField);
        if (encStr == null || encStr.isEmpty() || keyStr == null || keyStr.isEmpty()) return null;
        int encrypted = parseEncryptedPayload(encStr);
        int key       = parseIntSafe(keyStr);
        int payload;
        try {
            payload = (int) DECRYPT_MH.invokeExact(encrypted, key);
        } catch (Throwable e) {
            payload = key - encrypted;
        }
        return decodeLockValue(payload, isFloatPayload(encStr));
    }

    static int encodeFloatPayload(float value) {
        return Float.floatToRawIntBits(value);
    }

    static Float decodeLockValue(int payload, boolean floatFormat) {
        float value = floatFormat ? Float.intBitsToFloat(payload) : (float) payload;
        return isValidLockValue(value) ? value : null;
    }

    private static boolean isValidLockValue(float value) {
        return value > 0.0f && (Float.isFinite(value) || value == Float.POSITIVE_INFINITY);
    }

    private static Float readAuthoritative(LivingEntity entity, LockRecord record, long domain) {
        if (record == null) return null;
        record.rotateIfNeeded(entity.getUUID(), domain, entity.tickCount);
        long payload = record.readPayload(entity.getUUID(), domain);
        if (payload == INVALID_PAYLOAD) {
            if (!record.damageLogged) {
                record.damageLogged = true;
                EcaLogger.info("[HealthLock] authoritative state damaged entity={} id={}",
                        entity.getClass().getName(), entity.getId());
            }
            return Float.POSITIVE_INFINITY;
        }
        Float value = decodeLockValue((int) payload, true);
        return value != null ? value : Float.POSITIVE_INFINITY;
    }

    private static Float readPresentation(LivingEntity entity,
                                          EntityDataAccessor<String> encField,
                                          EntityDataAccessor<String> keyField,
                                          EntityDataAccessor<String> checkField) {
        if (encField == null || keyField == null || checkField == null
                || !validateIntegrity(entity, encField, keyField, checkField)) return null;
        return decryptLockValue(entity, encField, keyField);
    }

    private static void repairPresentation(LivingEntity entity, Float authoritative,
                                           EntityDataAccessor<String> encField,
                                           EntityDataAccessor<String> keyField,
                                           EntityDataAccessor<String> checkField) {
        if (authoritative == null || encField == null || keyField == null || checkField == null) return;
        Float presentation = readPresentation(entity, encField, keyField, checkField);
        if (presentation == null
                || Float.floatToRawIntBits(presentation) != Float.floatToRawIntBits(authoritative)) {
            writeEncrypted(entity, authoritative, encField, keyField, checkField);
        }
    }

    public static void prepareForSave(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide) return;
        Float healthLock = readAuthoritative(entity, HEALTH_LOCKS.get(entity.getUUID()), HEALTH_LOCK_DOMAIN);
        repairPresentation(entity, healthLock,
                EntityUtil.HEALTH_LOCK_VALUE, EntityUtil.HEALTH_LOCK_KEY, EntityUtil.HEALTH_LOCK_CHECK);
        Float maxHealthLock = readAuthoritative(entity, MAX_HEALTH_LOCKS.get(entity.getUUID()),
                MAX_HEALTH_LOCK_DOMAIN);
        repairPresentation(entity, maxHealthLock,
                EntityUtil.MAX_HEALTH_LOCK_VALUE, EntityUtil.MAX_HEALTH_LOCK_KEY,
                EntityUtil.MAX_HEALTH_LOCK_CHECK);
    }

    public static void clearAll() {
        HEALTH_LOCKS.clear();
        MAX_HEALTH_LOCKS.clear();
        HEAL_BAN_IDS.clear();
        synchedReadFailureLogged = false;
    }

    // ==================== NBT 回退：三字段写入/清除/解密 ====================

    private static void writeNbtEncrypted(CompoundTag data, float lockValue,
                                           String encKey, String keyKey, String checkKey) {
        int payload = encodeFloatPayload(lockValue);
        int key = ThreadLocalRandom.current().nextInt(10000);
        int encrypted;
        int check;
        try {
            encrypted = (int) ENCRYPT_MH.invokeExact(payload, key);
            check     = (int) CHECK_MH.invokeExact(encrypted, key);
        } catch (Throwable e) {
            encrypted = key - payload;
            check     = key + encrypted;
        }
        data.putString(encKey, FLOAT_PAYLOAD_PREFIX + encrypted);
        data.putInt(keyKey,   key);
        data.putInt(checkKey, check);
    }

    private static Float readNbtDecrypt(CompoundTag data, String encKey, String keyKey, String checkKey) {
        String encryptedValue = data.contains(encKey, Tag.TAG_STRING)
                ? data.getString(encKey) : String.valueOf(data.getInt(encKey));
        int encrypted   = parseEncryptedPayload(encryptedValue);
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
        int payload;
        try {
            payload = (int) DECRYPT_MH.invokeExact(encrypted, key);
        } catch (Throwable e) {
            payload = key - encrypted;
        }
        return decodeLockValue(payload, isFloatPayload(encryptedValue));
    }

    // ==================== 血量锁定（新加密） ====================

    public static void setLock(LivingEntity entity, float value) {
        if (entity == null || !isValidLockValue(value)) return;
        if (!entity.level().isClientSide) {
            HEALTH_LOCKS.put(entity.getUUID(),
                    new LockRecord(entity.getUUID(), encodeFloatPayload(value), HEALTH_LOCK_DOMAIN));
        }
        if (EntityUtil.HEALTH_LOCK_VALUE != null
                && EntityUtil.HEALTH_LOCK_KEY != null
                && EntityUtil.HEALTH_LOCK_CHECK != null) {
            writeEncrypted(entity, value,
                    EntityUtil.HEALTH_LOCK_VALUE, EntityUtil.HEALTH_LOCK_KEY, EntityUtil.HEALTH_LOCK_CHECK);
        } else {
            writeNbtEncrypted(entity.getPersistentData(), value,
                    NBT_HEALTH_LOCK_ENC, NBT_HEALTH_LOCK_KEY, NBT_HEALTH_LOCK_CHECK);
        }
    }

    public static void removeLock(LivingEntity entity) {
        if (entity == null) return;
        if (!entity.level().isClientSide) HEALTH_LOCKS.remove(entity.getUUID());
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
        if (!entity.level().isClientSide) {
            LockRecord record = HEALTH_LOCKS.get(entity.getUUID());
            Float value = readAuthoritative(entity, record, HEALTH_LOCK_DOMAIN);
            if (record != null && record.beginRepair(entity.tickCount, entity.getId())) {
                // 错峰修复让同步字段不再拥有解除服务端锁定的裁决权。
                repairPresentation(entity, value,
                        EntityUtil.HEALTH_LOCK_VALUE, EntityUtil.HEALTH_LOCK_KEY, EntityUtil.HEALTH_LOCK_CHECK);
            }
            return value;
        }

        if (EntityUtil.HEALTH_LOCK_VALUE != null
                && EntityUtil.HEALTH_LOCK_KEY != null
                && EntityUtil.HEALTH_LOCK_CHECK != null) {
            return readPresentation(entity,
                    EntityUtil.HEALTH_LOCK_VALUE, EntityUtil.HEALTH_LOCK_KEY, EntityUtil.HEALTH_LOCK_CHECK);
        }
        // NBT 回退
        Float nbtResult = readNbtDecrypt(entity.getPersistentData(),
                NBT_HEALTH_LOCK_ENC, NBT_HEALTH_LOCK_KEY, NBT_HEALTH_LOCK_CHECK);
        return nbtResult;
    }

    // ==================== 最大血量锁定（新加密） ====================

    public static void setMaxHealthLock(LivingEntity entity, float value) {
        if (entity == null || !isValidLockValue(value)) return;
        if (!entity.level().isClientSide) {
            MAX_HEALTH_LOCKS.put(entity.getUUID(),
                    new LockRecord(entity.getUUID(), encodeFloatPayload(value), MAX_HEALTH_LOCK_DOMAIN));
        }
        if (EntityUtil.MAX_HEALTH_LOCK_VALUE != null
                && EntityUtil.MAX_HEALTH_LOCK_KEY != null
                && EntityUtil.MAX_HEALTH_LOCK_CHECK != null) {
            writeEncrypted(entity, value,
                    EntityUtil.MAX_HEALTH_LOCK_VALUE, EntityUtil.MAX_HEALTH_LOCK_KEY, EntityUtil.MAX_HEALTH_LOCK_CHECK);
        } else {
            writeNbtEncrypted(entity.getPersistentData(), value,
                    NBT_MAX_HEALTH_LOCK_ENC, NBT_MAX_HEALTH_LOCK_KEY, NBT_MAX_HEALTH_LOCK_CHECK);
        }
    }

    public static void removeMaxHealthLock(LivingEntity entity) {
        if (entity == null) return;
        if (!entity.level().isClientSide) MAX_HEALTH_LOCKS.remove(entity.getUUID());
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
        if (!entity.level().isClientSide) {
            LockRecord record = MAX_HEALTH_LOCKS.get(entity.getUUID());
            Float value = readAuthoritative(entity, record, MAX_HEALTH_LOCK_DOMAIN);
            if (record != null && record.beginRepair(entity.tickCount, entity.getId())) {
                repairPresentation(entity, value,
                        EntityUtil.MAX_HEALTH_LOCK_VALUE, EntityUtil.MAX_HEALTH_LOCK_KEY,
                        EntityUtil.MAX_HEALTH_LOCK_CHECK);
            }
            return value;
        }

        if (EntityUtil.MAX_HEALTH_LOCK_VALUE != null
                && EntityUtil.MAX_HEALTH_LOCK_KEY != null
                && EntityUtil.MAX_HEALTH_LOCK_CHECK != null) {
            return readPresentation(entity,
                    EntityUtil.MAX_HEALTH_LOCK_VALUE, EntityUtil.MAX_HEALTH_LOCK_KEY,
                    EntityUtil.MAX_HEALTH_LOCK_CHECK);
        }
        // NBT 回退
        Float nbtResult = readNbtDecrypt(entity.getPersistentData(),
                NBT_MAX_HEALTH_LOCK_ENC, NBT_MAX_HEALTH_LOCK_KEY, NBT_MAX_HEALTH_LOCK_CHECK);
        return nbtResult;
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
