package net.eca.api;

import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.eca.util.health.HealthFieldCache;
import net.eca.util.health.HealthGetterHook;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;

import java.util.Set;

public final class EcaAPI {

    // ==================== 日志 ====================

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


    // 传送实体到指定位置
    /**
     * Teleport an entity to the specified location using VarHandle direct access.
     * This method directly modifies the entity's position fields and automatically syncs to clients.
     * @param entity the entity to teleport
     * @param x the target x coordinate
     * @param y the target y coordinate
     * @param z the target z coordinate
     * @return true if teleportation succeeded, false otherwise
     */
    public static boolean teleportEntity(Entity entity, double x, double y, double z) {
        return EntityUtil.teleportEntity(entity, x, y, z);
    }


    // 清除外部实体数据
    /**
     * Clear external numeric EntityData added by other mods.
     * This method ONLY executes when Defence Radical Logic is enabled in configuration.
     * When enabled, it sets all numeric EntityDataAccessors (Integer, Float, Double) to 0,
     * except for vanilla DATA_HEALTH_ID and fields matching the blacklist keywords.
     * If Defence Radical Logic is disabled, this method does nothing and returns immediately.
     * @param entity the living entity to clear data from
     */
    public static void clearExternalEntityData(LivingEntity entity) {
        EntityUtil.clearExternalEntityData(entity);
    }

    // ==================== 关键词名单管理 API ====================

    // 生命值白名单管理
    /**
     * Add a keyword to the health whitelist.
     * Fields containing this keyword will be modified during health modification.
     * @param keyword the keyword to add (case-insensitive)
     */
    public static void addHealthWhitelistKeyword(String keyword) {
        EntityUtil.addHealthWhitelistKeyword(keyword);
    }

    /**
     * Remove a keyword from the health whitelist.
     * @param keyword the keyword to remove (case-insensitive)
     */
    public static void removeHealthWhitelistKeyword(String keyword) {
        EntityUtil.removeHealthWhitelistKeyword(keyword);
    }

    /**
     * Get all health whitelist keywords.
     * @return a read-only copy of the health whitelist keywords
     */
    public static Set<String> getHealthWhitelistKeywords() {
        return EntityUtil.getHealthWhitelistKeywords();
    }

    // 生命值黑名单管理
    /**
     * Add a keyword to the health blacklist.
     * Fields containing this keyword will NOT be modified during health modification.
     * @param keyword the keyword to add (case-insensitive)
     */
    public static void addHealthBlacklistKeyword(String keyword) {
        EntityUtil.addHealthBlacklistKeyword(keyword);
    }

    /**
     * Remove a keyword from the health blacklist.
     * @param keyword the keyword to remove (case-insensitive)
     */
    public static void removeHealthBlacklistKeyword(String keyword) {
        EntityUtil.removeHealthBlacklistKeyword(keyword);
    }

    /**
     * Get all health blacklist keywords.
     * @return a read-only copy of the health blacklist keywords
     */
    public static Set<String> getHealthBlacklistKeywords() {
        return EntityUtil.getHealthBlacklistKeywords();
    }

    // 实体数据清除黑名单管理
    /**
     * Add a keyword to the data clear blacklist.
     * Fields containing this keyword will NOT be cleared during external entity data clearing.
     * @param keyword the keyword to add (case-insensitive)
     */
    public static void addDataClearBlacklistKeyword(String keyword) {
        EntityUtil.addDataClearBlacklistKeyword(keyword);
    }

    /**
     * Remove a keyword from the data clear blacklist.
     * @param keyword the keyword to remove (case-insensitive)
     */
    public static void removeDataClearBlacklistKeyword(String keyword) {
        EntityUtil.removeDataClearBlacklistKeyword(keyword);
    }

    /**
     * Get all data clear blacklist keywords.
     * @return a read-only copy of the data clear blacklist keywords
     */
    public static Set<String> getDataClearBlacklistKeywords() {
        return EntityUtil.getDataClearBlacklistKeywords();
    }

    private EcaAPI() {}
}
