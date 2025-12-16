package net.eca.api;

import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.eca.util.health.HealthFieldCache;
import net.eca.util.health.HealthGetterHook;
import net.eca.util.reflect.ObfuscationMapping;
import net.eca.util.reflect.ReflectUtil;
import net.eca.util.reflect.VarHandleUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class EcaAPI {

    public enum AccessMode {
        REFLECT,
        VAR_HANDLE
    }

    private static AccessMode defaultAccessMode = AccessMode.REFLECT;


    // 设置默认访问模式
    /**
     * Set the default access mode for field operations.
     * @param mode the access mode (REFLECT or VAR_HANDLE)
     */
    public static void setDefaultAccessMode(AccessMode mode) {
        defaultAccessMode = mode;
    }

    // 获取当前访问模式
    /**
     * Get the current default access mode.
     * @return the current access mode
     */
    public static AccessMode getDefaultAccessMode() {
        return defaultAccessMode;
    }


    // 获取字段值（使用默认模式）
    /**
     * Get field value using the default access mode.
     * @param target the target object (null for static fields)
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key like "Entity.entityData"
     * @param <T> the expected return type
     * @return the field value
     */
    public static <T> T getFieldValue(Object target, Class<?> clazz, String fieldKey) {
        return getFieldValue(target, clazz, fieldKey, defaultAccessMode);
    }

    // 获取字段值（指定模式）
    /**
     * Get field value using the specified access mode.
     * @param target the target object (null for static fields)
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param mode the access mode to use
     * @param <T> the expected return type
     * @return the field value
     */
    public static <T> T getFieldValue(Object target, Class<?> clazz, String fieldKey, AccessMode mode) {
        if (mode == AccessMode.VAR_HANDLE) {
            return VarHandleUtil.get(target, clazz, fieldKey);
        }
        return ReflectUtil.getFieldValue(target, clazz, fieldKey);
    }

    // 设置字段值（使用默认模式）
    /**
     * Set field value using the default access mode.
     * @param target the target object (null for static fields)
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param value the value to set
     * @return true if successful, false if failed
     */
    public static boolean setFieldValue(Object target, Class<?> clazz, String fieldKey, Object value) {
        return setFieldValue(target, clazz, fieldKey, value, defaultAccessMode);
    }

    // 设置字段值（指定模式）
    /**
     * Set field value using the specified access mode.
     * @param target the target object (null for static fields)
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param value the value to set
     * @param mode the access mode to use
     * @return true if successful, false if failed
     */
    public static boolean setFieldValue(Object target, Class<?> clazz, String fieldKey, Object value, AccessMode mode) {
        if (mode == AccessMode.VAR_HANDLE) {
            return VarHandleUtil.set(target, clazz, fieldKey, value);
        } else {
            return ReflectUtil.setFieldValue(target, clazz, fieldKey, value);
        }
    }

    // ==================== 底层访问 ====================

    // 获取Field对象
    /**
     * Get the Field object for direct use.
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @return the Field object
     */
    public static Field getField(Class<?> clazz, String fieldKey) {
        return ReflectUtil.getField(clazz, fieldKey);
    }

    // 获取VarHandle对象
    /**
     * Get the VarHandle object for direct use.
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @return the VarHandle object
     */
    public static VarHandle getVarHandle(Class<?> clazz, String fieldKey) {
        return VarHandleUtil.getVarHandle(clazz, fieldKey);
    }

    // 获取Method对象
    /**
     * Get the Method object for direct use.
     * @param clazz the class containing the method
     * @param methodKey the method mapping key
     * @param paramTypes the method parameter types
     * @return the Method object
     */
    public static Method getMethod(Class<?> clazz, String methodKey, Class<?>... paramTypes) {
        return ReflectUtil.getMethod(clazz, methodKey, paramTypes);
    }

    // ==================== 运行时动态字段访问 ====================

    // 通过Field对象获取VarHandle
    /**
     * Get VarHandle from a Field object (for runtime-discovered fields).
     * Useful for dynamic field access scenarios like Phase 2/2.5 field scanning.
     * @param field the field object
     * @return the VarHandle object
     */
    public static VarHandle getVarHandleFromField(Field field) {
        return VarHandleUtil.getVarHandleFromField(field);
    }

    // 通过Field对象获取字段值（VarHandle方式）
    /**
     * Get field value using a Field object with VarHandle.
     * @param target the target object (null for static fields)
     * @param field the field object
     * @param <T> the expected return type
     * @return the field value
     */
    public static <T> T getFieldValueFromField(Object target, Field field) {
        return VarHandleUtil.getFieldValue(target, field);
    }

    // 通过Field对象设置字段值（VarHandle方式，带类型转换）
    /**
     * Set field value using a Field object with VarHandle.
     * Automatically handles type conversion (e.g., int to float).
     * Returns false on failure instead of throwing exceptions.
     * @param target the target object (null for static fields)
     * @param field the field object
     * @param value the value to set
     * @return true if successful, false if failed
     */
    public static boolean setFieldValueFromField(Object target, Field field, Object value) {
        return VarHandleUtil.setFieldValue(target, field, value);
    }

    // 通过字段名设置字段值（便捷方法，带类型转换）
    /**
     * Set field value by field name with automatic type conversion.
     * This is a convenience method for dynamic field access.
     * @param target the target object
     * @param clazz the class containing the field
     * @param fieldName the actual field name (not mapping key)
     * @param value the value to set
     * @return true if successful, false if failed
     */
    public static boolean setFieldValueByName(Object target, Class<?> clazz, String fieldName, Object value) {
        return VarHandleUtil.setFieldValueByName(target, clazz, fieldName, value);
    }

    // ==================== 方法调用 ====================

    // 调用方法
    /**
     * Invoke a method using reflection.
     * @param target the target object (null for static methods)
     * @param clazz the class containing the method
     * @param methodKey the method mapping key
     * @param paramTypes the method parameter types
     * @param args the method arguments
     * @param <T> the expected return type
     * @return the method return value
     */
    public static <T> T invokeMethod(Object target, Class<?> clazz, String methodKey, Class<?>[] paramTypes, Object... args) {
        return ReflectUtil.invokeMethod(target, clazz, methodKey, paramTypes, args);
    }

    // ==================== VarHandle原子操作 ====================

    // 原子性获取并设置
    /**
     * Atomically get and set field value using VarHandle.
     * @param target the target object
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param newValue the new value to set
     * @param <T> the value type
     * @return the previous value
     */
    public static <T> T getAndSet(Object target, Class<?> clazz, String fieldKey, T newValue) {
        return VarHandleUtil.getAndSet(target, clazz, fieldKey, newValue);
    }

    // 比较并设置
    /**
     * Atomically compare and set field value using VarHandle.
     * @param target the target object
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param expectedValue the expected current value
     * @param newValue the new value to set
     * @return true if successful
     */
    public static boolean compareAndSet(Object target, Class<?> clazz, String fieldKey, Object expectedValue, Object newValue) {
        return VarHandleUtil.compareAndSet(target, clazz, fieldKey, expectedValue, newValue);
    }

    // ==================== 映射表管理 ====================

    // 注册自定义字段映射
    /**
     * Register a custom field mapping.
     * @param fieldKey the field mapping key
     * @param obfuscatedName the obfuscated field name
     */
    public static void registerFieldMapping(String fieldKey, String obfuscatedName) {
        ObfuscationMapping.registerFieldMapping(fieldKey, obfuscatedName);
    }

    // 注册自定义方法映射
    /**
     * Register a custom method mapping.
     * @param methodKey the method mapping key
     * @param obfuscatedName the obfuscated method name
     */
    public static void registerMethodMapping(String methodKey, String obfuscatedName) {
        ObfuscationMapping.registerMethodMapping(methodKey, obfuscatedName);
    }

    // 检查字段映射是否存在
    /**
     * Check if a field mapping exists.
     * @param fieldKey the field mapping key
     * @return true if mapping exists
     */
    public static boolean hasFieldMapping(String fieldKey) {
        return ObfuscationMapping.hasFieldMapping(fieldKey);
    }

    // 检查方法映射是否存在
    /**
     * Check if a method mapping exists.
     * @param methodKey the method mapping key
     * @return true if mapping exists
     */
    public static boolean hasMethodMapping(String methodKey) {
        return ObfuscationMapping.hasMethodMapping(methodKey);
    }

    // ==================== 缓存管理 ====================

    // 清空所有缓存
    /**
     * Clear all reflection and VarHandle caches.
     */
    public static void clearAllCaches() {
        ReflectUtil.clearCache();
        VarHandleUtil.clearCache();
    }

    // ==================== 日志系统 ====================

    // 通过委托接口注册mod日志器
    /**
     * Register a mod logger using the ILoggerDelegate interface.
     * Output format: [ECA/Prefix] message
     * @param delegate the logger delegate implementation
     * @return the ModLogger instance
     */
    public static EcaLogger.ModLogger registerLogger(ILoggerDelegate delegate) {
        return EcaLogger.register(delegate);
    }

    // 通过mod id和自定义前缀注册
    /**
     * Register a mod logger with custom prefix.
     * @param modId the mod id (used as key)
     * @param displayPrefix the display prefix for log messages
     * @return the ModLogger instance
     */
    public static EcaLogger.ModLogger registerLogger(String modId, String displayPrefix) {
        return EcaLogger.register(modId, displayPrefix);
    }

    // 通过mod id注册（前缀使用mod id）
    /**
     * Register a mod logger using mod id as prefix.
     * @param modId the mod id
     * @return the ModLogger instance
     */
    public static EcaLogger.ModLogger registerLogger(String modId) {
        return EcaLogger.register(modId);
    }

    // 获取已注册的mod日志器
    /**
     * Get a registered mod logger by mod id.
     * @param modId the mod id
     * @return the ModLogger instance, or null if not registered
     */
    public static EcaLogger.ModLogger getLogger(String modId) {
        return EcaLogger.get(modId);
    }

    // 获取或注册mod日志器
    /**
     * Get or register a mod logger by mod id.
     * @param modId the mod id
     * @return the ModLogger instance
     */
    public static EcaLogger.ModLogger getOrRegisterLogger(String modId) {
        return EcaLogger.getOrRegister(modId);
    }

    // ECA日志 - INFO
    /**
     * Log an info message with [ECA] prefix.
     * @param msg the message to log
     */
    public static void logInfo(String msg) {
        EcaLogger.info(msg);
    }

    // ECA日志 - INFO（带参数）
    /**
     * Log an info message with [ECA] prefix and arguments.
     * @param fmt the format string
     * @param args the arguments
     */
    public static void logInfo(String fmt, Object... args) {
        EcaLogger.info(fmt, args);
    }

    // ECA日志 - WARN
    /**
     * Log a warning message with [ECA] prefix.
     * @param msg the message to log
     */
    public static void logWarn(String msg) {
        EcaLogger.warn(msg);
    }

    // ECA日志 - WARN（带参数）
    /**
     * Log a warning message with [ECA] prefix and arguments.
     * @param fmt the format string
     * @param args the arguments
     */
    public static void logWarn(String fmt, Object... args) {
        EcaLogger.warn(fmt, args);
    }

    // ECA日志 - ERROR
    /**
     * Log an error message with [ECA] prefix.
     * @param msg the message to log
     */
    public static void logError(String msg) {
        EcaLogger.error(msg);
    }

    // ECA日志 - ERROR（带参数）
    /**
     * Log an error message with [ECA] prefix and arguments.
     * @param fmt the format string
     * @param args the arguments
     */
    public static void logError(String fmt, Object... args) {
        EcaLogger.error(fmt, args);
    }

    // ECA日志 - ERROR（带异常）
    /**
     * Log an error message with [ECA] prefix and throwable.
     * @param msg the message to log
     * @param throwable the exception to log
     */
    public static void logError(String msg, Throwable throwable) {
        EcaLogger.error(msg, throwable);
    }

    // ECA日志 - DEBUG
    /**
     * Log a debug message with [ECA] prefix.
     * @param msg the message to log
     */
    public static void logDebug(String msg) {
        EcaLogger.debug(msg);
    }

    // ECA日志 - DEBUG（带参数）
    /**
     * Log a debug message with [ECA] prefix and arguments.
     * @param fmt the format string
     * @param args the arguments
     */
    public static void logDebug(String fmt, Object... args) {
        EcaLogger.debug(fmt, args);
    }

    // ==================== 生命值分析 ====================

    // 手动触发实体生命值分析
    /**
     * Manually trigger health analysis for an entity.
     * This will analyze the entity's getHealth() method implementation and cache the result.
     * Normally, analysis is triggered automatically when getHealth() is called, but this method
     * can be used to pre-analyze entities before modification.
     * @param entity the living entity to analyze
     */
    public static void triggerHealthAnalysis(LivingEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        HealthGetterHook.triggerAnalysis(entity);
    }

    // 获取实体类的生命值缓存
    /**
     * Get the health field cache for an entity class.
     * Returns null if the entity class has not been analyzed yet.
     * The cache contains:
     * - Access pattern (how to access the health field/container)
     * - Reverse transformation formula (if the health value is computed)
     * - Write function (how to modify the health value)
     * @param entityClass the entity class
     * @return the health field cache, or null if not analyzed
     */
    public static HealthFieldCache getHealthCache(Class<? extends LivingEntity> entityClass) {
        if (entityClass == null) {
            throw new IllegalArgumentException("Entity class cannot be null");
        }
        return HealthGetterHook.getCache(entityClass);
    }

    // 修改实体生命值（智能修改，支持复杂存储方式）
    /**
     * Modify entity health intelligently.
     * This method uses the cached analysis result to modify the health value correctly,
     * even if the entity stores health in a custom container or applies transformations.
     * @param entity the living entity
     * @param targetHealth the target health value
     * @return true if modification succeeded
     */
    public static boolean modifyEntityHealth(LivingEntity entity, float targetHealth) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }

        // 触发分析（如果尚未分析）
        HealthGetterHook.triggerAnalysis(entity);

        // 获取缓存
        HealthFieldCache cache = HealthGetterHook.getCache(entity.getClass());
        if (cache == null || cache.writePath == null) {
            EcaLogger.warn("[EcaAPI] No health cache or write path found for: {}", entity.getClass().getSimpleName());
            return false;
        }

        try {
            // 应用逆向公式（如果有）
            float valueToWrite = targetHealth;
            if (cache.reverseTransform != null) {
                valueToWrite = cache.reverseTransform.apply(targetHealth);
            }

            // 执行写入
            return cache.writePath.apply(entity, valueToWrite);
        } catch (Exception e) {
            EcaLogger.error("[EcaAPI] Failed to modify health for: {}", entity.getClass().getSimpleName(), e);
            return false;
        }
    }

    // ==================== 实体血量工具 ====================

    // 获取实体真实血量
    /**
     * Get entity real health using VarHandle direct access.
     * This bypasses any custom getHealth() implementations and reads directly from DATA_HEALTH_ID.
     * @param entity the living entity
     * @return the real health value, or 0.0f if entity is null
     */
    public static float getHealth(LivingEntity entity) {
        return EntityUtil.getHealth(entity);
    }

    // 设置实体血量（多阶段）
    /**
     * Set entity health using multi-phase modification.
     * Phase 1: Modify vanilla DATA_HEALTH_ID
     * Phase 2: Smart scan for EntityDataAccessors and fields matching health keywords
     * Phase 2.5: Radical mode (all numeric fields, if enabled in config)
     * Phase 3: Bytecode reverse tracking via HealthGetterHook
     * @param entity the living entity
     * @param health the target health value
     * @return true if modification succeeded
     */
    public static boolean setHealth(LivingEntity entity, float health) {
        return EntityUtil.setHealth(entity, health);
    }

    // ==================== 无敌状态管理 ====================

    // ECA 无敌状态 NBT 键名
    public static final String NBT_INVULNERABLE = "eca:invulnerable";

    // 获取实体无敌状态
    /**
     * Get the invulnerability state of an entity (ECA system).
     * Reads from entity's PersistentData NBT.
     * @param entity the entity to check
     * @return true if the entity is invulnerable, false otherwise
     */
    public static boolean isInvulnerable(Entity entity) {
        if (entity == null) {
            return false;
        }
        return entity.getPersistentData().getBoolean(NBT_INVULNERABLE);
    }

    // 设置实体无敌状态
    /**
     * Set the invulnerability state of an entity (ECA system).
     * Writes to entity's PersistentData NBT.
     * @param entity the entity to modify
     * @param invulnerable true to make the entity invulnerable, false otherwise
     */
    public static void setInvulnerable(Entity entity, boolean invulnerable) {
        if (entity == null) {
            return;
        }
        entity.getPersistentData().putBoolean(NBT_INVULNERABLE, invulnerable);
    }

    // 初始化实体无敌状态 NBT（由事件调用）
    /**
     * Initialize the invulnerability NBT for an entity if not present.
     * Called by EntityJoinLevelEvent handler.
     * @param entity the entity to initialize
     */
    public static void initInvulnerableNBT(Entity entity) {
        if (entity == null) {
            return;
        }
        if (!entity.getPersistentData().contains(NBT_INVULNERABLE)) {
            entity.getPersistentData().putBoolean(NBT_INVULNERABLE, false);
        }
    }

    // ==================== 实体死亡模块 API ====================

    // 设置实体死亡
    /**
     * Set an entity to dead state and handle all death-related logic.
     * This will set health to 0, trigger die(), drop loot, grant advancements, and completely remove the entity.
     * Note: Death messages are NOT sent - implement custom death message logic in your mod if needed.
     * @param entity the living entity to kill
     * @param damageSource the damage source that caused the death
     */
    public static void killEntity(LivingEntity entity, DamageSource damageSource) {
        EntityUtil.setDead(entity, damageSource);
    }

    // 复活实体
    /**
     * Revive an entity by clearing its death state.
     * This resets the 'dead' flag and 'deathTime' field using VarHandle.
     * @param entity the living entity to revive
     */
    public static void reviveEntity(LivingEntity entity) {
        EntityUtil.reviveEntity(entity);
    }

    // ==================== 实体清除模块 API ====================

    // 完整清除实体
    /**
     * Completely remove an entity from the world, including all internal containers.
     * This performs deep cleanup including:
     * - AI system cleanup (goals, targets, navigation)
     * - Boss bar cleanup
     * - Riding/passenger relationships
     * - Server-side containers (ChunkMap, EntityTickList, EntityLookup, EntitySectionStorage, etc.)
     * - Client-side containers (if applicable)
     * @param entity the entity to remove
     * @param reason the removal reason (e.g., Entity.RemovalReason.KILLED, DISCARDED, etc.)
     */
    public static void removeEntity(Entity entity, Entity.RemovalReason reason) {
        EntityUtil.removeEntity(entity, reason);
    }

    // 清理Boss血条

    private EcaAPI() {}
}
