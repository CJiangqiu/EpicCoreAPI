package net.eca.api;

import net.eca.agent.EcaAgent;
import net.eca.agent.PackageWhitelist;
import net.eca.agent.ReturnToggle;
import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.eca.util.health.HealthLockManager;
import net.eca.util.reflect.LwjglUtil;
import net.eca.util.spawn.SpawnBanManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EcaAPI {

    // 锁定血量
    /**
     * Lock entity health at a specific value.
     * When enabled, the entity's health is locked in two ways:
     * 1. getHealth() always returns the locked value (via bytecode hook)
     * 2. Real health is reset to the locked value every tick (via Mixin)
     *
     * This provides true health locking - the entity cannot die from damage
     * as long as the lock is active (unless killed instantly with damage > locked value).
     *
     * Use cases:
     * - Boss invincibility phases
     * - Tutorial mode (beginner protection)
     * - PVP damage limitation
     * - Heal negation effects
     *
     * The locked value is synchronized to clients via SynchedEntityData.
     * @param entity the living entity
     * @param value the health lock value
     */
    public static void lockHealth(LivingEntity entity, float value) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        HealthLockManager.setLock(entity, value);
    }

    // 解锁血量
    /**
     * Unlock entity health.
     * After unlocking, the entity's getHealth() method will return the actual health value.
     * @param entity the living entity
     */
    public static void unlockHealth(LivingEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        HealthLockManager.removeLock(entity);
    }

    // 获取当前锁定值（如果没有锁定返回 null）
    /**
     * Get the current health lock value for an entity.
     * @param entity the living entity
     * @return the locked value, or null if health is not locked
     */
    public static Float getLockedHealth(LivingEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        return HealthLockManager.getLock(entity);
    }

    // 检查是否被锁定
    /**
     * Check if an entity has health locked.
     * @param entity the living entity
     * @return true if health is locked, false otherwise
     */
    public static boolean isHealthLocked(LivingEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        return HealthLockManager.hasLock(entity);
    }

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
     * Phase 3: Bytecode reverse tracking via HealthAnalyzerManager
     * @param entity the living entity
     * @param health the target health value
     * @return true if modification succeeded
     */
    public static boolean setHealth(LivingEntity entity, float health) {
        return EntityUtil.setHealth(entity, health);
    }


    // 获取实体无敌状态
    /**
     * Get the invulnerability state of an entity (ECA system).
     * Uses EntityData for LivingEntity types (synchronized to clients automatically).
     * Non-LivingEntity types always return false.
     * @param entity the entity to check
     * @return true if the entity is invulnerable, false otherwise
     */
    public static boolean isInvulnerable(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (!(entity instanceof LivingEntity livingEntity)) {
            return false;
        }
        return livingEntity.getEntityData().get(EntityUtil.INVULNERABLE);
    }

    // 设置实体无敌状态
    /**
     * Set the invulnerability state of an entity (ECA system).
     * Uses EntityData for LivingEntity types (synchronized to clients automatically).
     * Non-LivingEntity types are ignored.
     *
     * IMPORTANT: This method automatically manages health lock:
     * - When enabling invulnerability: locks health at current value
     * - When disabling invulnerability: unlocks health
     *
     * @param entity the entity to modify
     * @param invulnerable true to make the entity invulnerable, false otherwise
     */
    public static void setInvulnerable(Entity entity, boolean invulnerable) {
        if (entity == null) {
            return;
        }
        if (!(entity instanceof LivingEntity livingEntity)) {
            return;
        }

        if (invulnerable) {
            // 开启无敌：先锁血，再设置无敌状态
            float currentHealth = livingEntity.getHealth();
            lockHealth(livingEntity, currentHealth);
            livingEntity.getEntityData().set(EntityUtil.INVULNERABLE, true);
        } else {
            // 关闭无敌：先解除无敌状态，再解锁血量
            livingEntity.getEntityData().set(EntityUtil.INVULNERABLE, false);
            unlockHealth(livingEntity);
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

    // 清除实体（使用LWJGL实现，需要开启激进攻击逻辑配置）
    /**
     * Remove an entity using LWJGL API.
     * DANGER! Requires "Enable Radical Logic" in Attack config.
     * @param entity the entity to remove
     * @return true if removal succeeded, false otherwise (including when config is disabled)
     */
    public static boolean memoryRemoveEntity(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()) {
            EcaLogger.warn("memoryRemoveEntity requires Attack Radical Logic to be enabled in config");
            return false;
        }
        return LwjglUtil.lwjglRemove(entity);
    }

    // 清理实体 Boss 血条
    /**
     * Clean up boss bars associated with an entity.
     * This method scans all instance fields of the entity and removes any ServerBossEvent instances found.
     * Use this when you want to remove boss bars without completely removing the entity.
     * @param entity the entity whose boss bars should be cleaned up
     */
    public static void cleanupBossBar(Entity entity) {
        EntityUtil.cleanupBossBar(entity);
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


    // ============ 禁生成 API ============

    // 添加禁生成
    /**
     * Add a spawn ban for the specified entity type in a level.
     * Entities of this type will be blocked from spawning for the specified duration.
     * The ban is stored per-dimension and persists with world saves.
     *
     * Use cases:
     * - Temporarily disable mob spawning after boss death
     * - Prevent specific entities from respawning during events
     * - Create mob-free zones for building or exploration
     *
     * @param level the server level
     * @param type the entity type to ban
     * @param timeInSeconds ban duration in seconds
     * @return true if ban was added successfully
     * @throws IllegalArgumentException if level or type is null
     */
    public static boolean addSpawnBan(ServerLevel level, EntityType<?> type, int timeInSeconds) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("EntityType cannot be null");
        }
        return SpawnBanManager.addBan(level, type, timeInSeconds);
    }

    // 检查是否被禁生成
    /**
     * Check if an entity type is currently banned from spawning.
     * @param level the server level
     * @param type the entity type to check
     * @return true if the entity type is banned
     * @throws IllegalArgumentException if level or type is null
     */
    public static boolean isSpawnBanned(ServerLevel level, EntityType<?> type) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("EntityType cannot be null");
        }
        return SpawnBanManager.isBanned(level, type);
    }

    // 获取禁生成剩余时间
    /**
     * Get the remaining spawn ban time for an entity type.
     * @param level the server level
     * @param type the entity type to check
     * @return remaining time in seconds, 0 if not banned
     * @throws IllegalArgumentException if level or type is null
     */
    public static int getSpawnBanTime(ServerLevel level, EntityType<?> type) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("EntityType cannot be null");
        }
        return SpawnBanManager.getRemainingTime(level, type);
    }

    // 清除禁生成
    /**
     * Clear the spawn ban for an entity type.
     * @param level the server level
     * @param type the entity type to unban
     * @return true if a ban was removed
     * @throws IllegalArgumentException if level or type is null
     */
    public static boolean clearSpawnBan(ServerLevel level, EntityType<?> type) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("EntityType cannot be null");
        }
        return SpawnBanManager.clearBan(level, type);
    }

    // 获取所有禁生成
    /**
     * Get all current spawn bans for a level.
     * @param level the server level
     * @return immutable map of EntityType to remaining seconds
     * @throws IllegalArgumentException if level is null
     */
    public static Map<EntityType<?>, Integer> getAllSpawnBans(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        return SpawnBanManager.getAllBans(level);
    }

    // 清除所有禁生成
    /**
     * Clear all spawn bans for a level.
     * @param level the server level
     * @throws IllegalArgumentException if level is null
     */
    public static void clearAllSpawnBans(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        SpawnBanManager.clearAllBans(level);
    }


    // 危险！需要开启激进攻击逻辑配置，会尝试对目标实体的所属mod的全部布尔和void方法进行return transformation
    /**
     * Enable AllReturn for the specified entity's mod.
     * DANGER! Requires "Enable Radical Logic" in Attack config.
     * Will perform return transformation on all boolean and void methods of the target entity's mod.
     * @param entity the entity whose mod classes will be transformed
     * @return true if AllReturn was enabled successfully, false if radical logic is disabled or agent not available
     */
    public static boolean enableAllReturn(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()) {
            EcaLogger.warn("AllReturn requires Attack Radical Logic to be enabled in config");
            return false;
        }
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            EcaLogger.warn("AllReturn: Agent is not initialized");
            return false;
        }

        Class<?> entityClass = entity.getClass();
        String binaryName = entityClass.getName();
        if (ReturnToggle.isExcludedBinaryName(binaryName)) {
            return false;
        }

        String packagePrefix = getPackagePrefix(binaryName);
        String internalPrefix = packagePrefix != null ? packagePrefix.replace('.', '/') : null;

        ReturnToggle.setAllReturnEnabled(true);
        ReturnToggle.addAllowedPackagePrefix(internalPrefix);

        URL codeSource = getCodeSourceLocation(entityClass);
        List<Class<?>> candidates = collectCandidates(inst, codeSource, packagePrefix);

        if (candidates.isEmpty()) {
            return false;
        }

        List<String> targets = new ArrayList<>();
        for (Class<?> clazz : candidates) {
            targets.add(clazz.getName().replace('.', '/'));
        }
        ReturnToggle.addExplicitTargets(targets.toArray(new String[0]));

        try {
            inst.retransformClasses(candidates.toArray(new Class<?>[0]));
            return true;
        } catch (Throwable t) {
            EcaLogger.warn("AllReturn batch retransform failed: {}", t.getMessage());
            return false;
        }
    }

    // 关闭AllReturn
    /**
     * Disable AllReturn and clear all targets.
     */
    public static void disableAllReturn() {
        ReturnToggle.setAllReturnEnabled(false);
        ReturnToggle.clearAllTargets();
    }

    // 检查AllReturn是否启用
    /**
     * Check if AllReturn is currently enabled.
     * @return true if AllReturn is enabled
     */
    public static boolean isAllReturnEnabled() {
        return ReturnToggle.isAllReturnEnabled();
    }

    // 全局AllReturn开关（影响所有已加载的mod）
    /**
     * Enable or disable global AllReturn mode.
     * DANGER! Requires "Enable Radical Logic" in Attack config.
     * When enabled, will perform return transformation on all boolean and void methods
     * of ALL loaded mods (excluding whitelisted packages).
     * @param enable true to enable, false to disable
     * @return true if operation succeeded, false if radical logic is disabled or agent not available
     */
    public static boolean setGlobalAllReturn(boolean enable) {
        Instrumentation inst = EcaAgent.getInstrumentation();

        if (!enable) {
            ReturnToggle.setAllReturnEnabled(false);
            ReturnToggle.clearAllTargets();
            invokeAgentReturnToggle(inst, "setAllReturnEnabled", new Class<?>[] { boolean.class }, false);
            invokeAgentReturnToggle(inst, "clearAllTargets", new Class<?>[0]);
            return true;
        }

        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()) {
            EcaLogger.warn("GlobalAllReturn requires Attack Radical Logic to be enabled in config");
            return false;
        }

        if (inst == null) {
            EcaLogger.warn("GlobalAllReturn: Agent is not initialized");
            return false;
        }

        // 收集所有非白名单的类（不依赖 modPackagePrefixes）
        List<Class<?>> candidates = new ArrayList<>();
        Set<String> collectedPrefixes = new HashSet<>();

        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (!inst.isModifiableClass(clazz)) continue;
            if (clazz.isInterface() || clazz.isArray() || clazz.isPrimitive()) continue;

            String className = clazz.getName();
            if (ReturnToggle.isExcludedBinaryName(className)) continue;

            candidates.add(clazz);

            // 收集包前缀
            String packagePrefix = getPackagePrefix(className);
            if (packagePrefix != null) {
                String internalPrefix = packagePrefix.replace('.', '/');
                collectedPrefixes.add(internalPrefix);
            }
        }

        if (candidates.isEmpty()) {
            EcaLogger.warn("GlobalAllReturn: No candidate classes found");
            return false;
        }

        // 启用 AllReturn 并添加所有包前缀（与实体版相同方式）
        ReturnToggle.setAllReturnEnabled(true);
        invokeAgentReturnToggle(inst, "setAllReturnEnabled", new Class<?>[] { boolean.class }, true);

        for (String prefix : collectedPrefixes) {
            ReturnToggle.addAllowedPackagePrefix(prefix);
            invokeAgentReturnToggle(inst, "addAllowedPackagePrefix", new Class<?>[] { String.class }, prefix);
        }

        // 添加显式目标
        List<String> targets = new ArrayList<>();
        for (Class<?> clazz : candidates) {
            targets.add(clazz.getName().replace('.', '/'));
        }
        ReturnToggle.addExplicitTargets(targets.toArray(new String[0]));
        invokeAgentReturnToggle(inst, "addExplicitTargets", new Class<?>[] { String[].class }, (Object) targets.toArray(new String[0]));

        EcaLogger.info("GlobalAllReturn: Retransforming {} classes from {} package prefixes",
            candidates.size(), collectedPrefixes.size());

        try {
            inst.retransformClasses(candidates.toArray(new Class<?>[0]));
        } catch (Throwable t) {
            EcaLogger.warn("GlobalAllReturn: Batch retransform failed: {}", t.getMessage());
        }

        return true;
    }

    private static void invokeAgentReturnToggle(Instrumentation inst, String method, Class<?>[] paramTypes, Object... args) {
        Class<?> agentToggle = findAgentReturnToggle(inst);
        if (agentToggle == null) {
            return;
        }
        try {
            agentToggle.getMethod(method, paramTypes).invoke(null, args);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Class<?> findAgentReturnToggle(Instrumentation inst) {
        if (inst == null) {
            return null;
        }
        ClassLoader localLoader = EcaAPI.class.getClassLoader();
        Class<?> fallback = null;
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (!"net.eca.agent.ReturnToggle".equals(clazz.getName())) {
                continue;
            }
            ClassLoader loader = clazz.getClassLoader();
            if (loader != localLoader) {
                return clazz;
            }
            fallback = clazz;
        }
        return fallback;
    }

    // ==================== 包名白名单 API ====================

    // 添加受保护的包名前缀（保护 mod 免受 AllReturn 等危险操作影响）
    /**
     * Add a protected package prefix to the whitelist.
     * Classes in protected packages will not be affected by AllReturn and other dangerous operations.
     * @param packagePrefix the package prefix to protect (e.g., "com.yourmod.")
     */
    public static void addProtectedPackage(String packagePrefix) {
        PackageWhitelist.addProtection(packagePrefix);
    }

    // 移除受保护的包名前缀（不能移除内置保护）
    /**
     * Remove a protected package prefix from the whitelist.
     * Built-in protections (JDK, Minecraft, Forge, etc.) cannot be removed.
     * @param packagePrefix the package prefix to unprotect
     * @return true if successfully removed, false if it was a built-in protection or not found
     */
    public static boolean removeProtectedPackage(String packagePrefix) {
        return PackageWhitelist.removeProtection(packagePrefix);
    }

    // 检查包名是否受保护
    /**
     * Check if a class is protected by the whitelist.
     * @param className the binary class name (e.g., "com.yourmod.MyClass")
     * @return true if the class is protected
     */
    public static boolean isPackageProtected(String className) {
        return PackageWhitelist.isProtectedBinary(className);
    }

    // 获取所有受保护的包名前缀
    /**
     * Get all protected package prefixes (built-in + custom).
     * @return unmodifiable set of all protected prefixes
     */
    public static Set<String> getAllProtectedPackages() {
        return PackageWhitelist.getAll();
    }

    //AllReturn 内部方法

    private static String getPackagePrefix(String binaryName) {
        int lastDot = binaryName.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        return binaryName.substring(0, lastDot + 1);
    }

    private static URL getCodeSourceLocation(Class<?> clazz) {
        try {
            ProtectionDomain domain = clazz.getProtectionDomain();
            if (domain == null) return null;
            CodeSource source = domain.getCodeSource();
            if (source == null) return null;
            return source.getLocation();
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static List<Class<?>> collectCandidates(Instrumentation inst, URL codeSource, String packagePrefix) {
        List<Class<?>> candidates = new ArrayList<>();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (!inst.isModifiableClass(clazz)) continue;
            if (clazz.isInterface() || clazz.isArray() || clazz.isPrimitive()) continue;

            String className = clazz.getName();
            if (ReturnToggle.isExcludedBinaryName(className)) continue;

            if (codeSource != null) {
                URL classSource = getCodeSourceLocation(clazz);
                if (classSource == null || !classSource.equals(codeSource)) continue;
            } else if (packagePrefix != null) {
                if (!className.startsWith(packagePrefix)) continue;
            } else {
                continue;
            }
            candidates.add(clazz);
        }
        return candidates;
    }

    private EcaAPI() {}
}
