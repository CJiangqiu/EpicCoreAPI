package net.eca.api;

import net.eca.agent.EcaAgent;
import net.eca.coremod.AllReturnToggle;
import net.eca.coremod.TransformerWhitelist;
import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;
import net.eca.util.EntityLocationManager;
import net.eca.util.EntityUtil;
import net.eca.util.InvulnerableEntityManager;
import net.eca.util.health.HealthLockManager;
import net.eca.util.reflect.UnsafeUtil;
import net.eca.util.entity_extension.EntityExtension;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.entity_extension.ForceLoadingManager;
import net.eca.util.entity_extension.GlobalEffectOverrideManager;
import net.eca.network.EntityExtensionOverridePacket.FogData;
import net.eca.network.EntityExtensionOverridePacket.SkyboxData;
import net.eca.network.EntityExtensionOverridePacket.MusicData;
import net.eca.util.bossshow.BossShowDefinition;
import net.eca.util.bossshow.BossShowManager;
import net.eca.util.bossshow.BossShowPlaybackTracker;
import net.eca.util.bossshow.Trigger;
import net.eca.util.spawn_ban.SpawnBanManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class EcaAPI {

    // 锁定血量
    /**
     * Lock entity health at a specific value.
     * When enabled, the entity's health is locked in two ways:
     * 1. getHealth() always returns the locked value (via bytecode hook)
     * 2. Real health is reset to the locked value every tick (via Mixin)
     * This provides true health locking - the entity cannot die from damage
     * as long as the lock is active (unless killed instantly with damage > locked value).
     * Use cases:
     * - Boss invincibility phases
     * - Tutorial mode (beginner protection)
     * - PVP damage limitation
     * - Heal negation effects
     * The locked value is synchronized to clients via SynchedEntityData.
     * @param entity the living entity
     * @param value the health lock value
     */
    public static void lockHealth(LivingEntity entity, float value) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        if (value <= 0) {
            throw new IllegalArgumentException("Lock health value must be greater than 0");
        }
        setHealth(entity, value);
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

    // ==================== 禁疗系统 ====================

    // 设置禁疗
    /**
     * Ban healing for an entity.
     * The entity cannot receive healing, but can still take damage.
     * The heal ban value will be updated when the entity is damaged.
     * Use cases:
     * - Grievous Wounds effect
     * - Poison with anti-heal
     * - Boss phase mechanics
     * The heal ban value is synchronized to clients via SynchedEntityData.
     * @param entity the living entity
     * @param value the heal ban value (current health that will be maintained)
     */
    public static void banHealing(LivingEntity entity, float value) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        setHealth(entity, value);
        HealthLockManager.setHealBan(entity, value);
    }

    // 解除禁疗
    /**
     * Unban healing for an entity.
     * After unbanning, the entity can receive healing normally.
     * @param entity the living entity
     */
    public static void unbanHealing(LivingEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        HealthLockManager.removeHealBan(entity);
    }

    // 获取当前禁疗值
    /**
     * Get the current heal ban value for an entity.
     * @param entity the living entity
     * @return the heal ban value, or null if healing is not banned
     */
    public static Float getHealBanValue(LivingEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        return HealthLockManager.getHealBan(entity);
    }

    // 检查是否被禁疗
    /**
     * Check if an entity has healing banned.
     * @param entity the living entity
     * @return true if healing is banned, false otherwise
     */
    public static boolean isHealingBanned(LivingEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        return HealthLockManager.getHealBan(entity) != null;
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
        return HealthLockManager.getLock(entity) != null;
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
     * Phase 3: Bytecode reverse tracking via HealthAnalyzerManager
     * Phase 4: Radical mode - all numeric fields (only if phases 1-3 fail, requires config)
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
        boolean managerInvulnerable = InvulnerableEntityManager.isInvulnerable(entity.getUUID());
        if (!(entity instanceof LivingEntity livingEntity)) {
            return managerInvulnerable;
        }
        if (EntityUtil.INVULNERABLE != null) {
            return livingEntity.getEntityData().get(EntityUtil.INVULNERABLE) || managerInvulnerable;
        } else {
            return livingEntity.getPersistentData().getBoolean("ecaInvulnerable") || managerInvulnerable;
        }
    }

    // 设置实体无敌状态
    /**
     * Set the invulnerability state of an entity (ECA system).
     * Uses EntityData for LivingEntity types (synchronized to clients automatically).
     * Non-LivingEntity types are ignored.
     * IMPORTANT: This method automatically manages multiple protection systems:
     * When enabling invulnerability:
     * - Revives entity and locks health at current value
     * - Blocks all incoming damage (hurt/actuallyHurt intercepted)
     * - Prevents death (die/tickDeath intercepted, isDeadOrDying/isAlive overridden)
     * - Removes harmful potion effects every tick
     * - Prevents mobs from targeting this entity
     * - Protects player inventory (clearContent/removeItem/clearOrCountMatchingItems blocked)
     * When disabling invulnerability:
     * - Clears invulnerability flag and unlocks health
     * - All above protections are lifted
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
            // 开启无敌：复活 + 锁血 + 设置无敌状态 + 添加记录
            revive(livingEntity);
            float lockValue = Math.max(EntityUtil.getHealth(livingEntity), livingEntity.getMaxHealth());
            lockValue = Math.max(lockValue, 1.0f);
            lockHealth(livingEntity, lockValue);
            if (EntityUtil.INVULNERABLE != null) {
                livingEntity.getEntityData().set(EntityUtil.INVULNERABLE, true);
            } else {
                livingEntity.getPersistentData().putBoolean("ecaInvulnerable", true);
            }
            InvulnerableEntityManager.addInvulnerable(entity);
        } else {
            // 关闭无敌：解除无敌状态 + 解锁血量 + 移除记录
            if (EntityUtil.INVULNERABLE != null) {
                livingEntity.getEntityData().set(EntityUtil.INVULNERABLE, false);
            } else {
                livingEntity.getPersistentData().putBoolean("ecaInvulnerable", false);
            }
            unlockHealth(livingEntity);
            InvulnerableEntityManager.removeInvulnerable(entity);
            // 刷新血量状态，使 Minecraft 内部死亡检测恢复正常
            livingEntity.onSyncedDataUpdated(LivingEntity.DATA_HEALTH_ID);
        }
    }


    // 设置实体死亡
    /**
     * Set an entity to dead state and handle all death-related logic.
     * @param entity the living entity to setDead
     * @param damageSource the damage source that caused the death
     */
    public static void kill(LivingEntity entity, DamageSource damageSource) {
        EntityUtil.kill(entity, damageSource);
    }

    // 复活实体
    /**
     * Revive an entity by clearing its death state.
     * @param entity the living entity to revive
     */
    public static void revive(LivingEntity entity) {
        EntityUtil.revive(entity);
    }

    // 按UUID复活实体
    /**
     * Revive an entity by UUID in the specified level.
     * @param level the server level containing the entity
     * @param uuid the UUID of the entity to revive
     */
    public static void revive(ServerLevel level, UUID uuid) {
        EntityUtil.revive(level, uuid);
    }

    // 复活实体关键容器
    /**
     * Revive all critical entity containers for an entity.
     * Attempts to re-insert the entity into tickList, lookup, sections, and tracker.
     * @param entity the living entity to revive containers for
     * @return map of container name to success result
     */
    public static Map<String, Boolean> reviveAllContainers(LivingEntity entity) {
        return EntityUtil.reviveAllContainers(entity);
    }

    // 按UUID复活实体关键容器
    /**
     * Revive all critical entity containers by UUID in the specified level.
     * @param level the server level containing the entity
     * @param uuid the UUID of the entity to revive containers for
     * @return map of container name to success result
     */
    public static Map<String, Boolean> reviveAllContainers(ServerLevel level, UUID uuid) {
        return EntityUtil.reviveAllContainers(level, uuid);
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
    public static void remove(Entity entity, Entity.RemovalReason reason) {
        EntityUtil.remove(entity, reason);
    }

    // 清除实体（使用Unsafe实现，需要开启激进攻击逻辑配置）
    /**
     * Remove an entity using Unsafe API, bypassing call-stack interception.
     * DANGER! Requires "Enable Radical Logic" in Attack config.
     * @param entity the entity to remove
     * @param reason the removal reason
     * @return true if removal succeeded, false otherwise (including when config is disabled)
     */
    public static boolean memoryRemove(Entity entity, Entity.RemovalReason reason) {
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()) {
            EcaLogger.warn("memoryRemove requires Attack Radical Logic to be enabled in config");
            return false;
        }

        EntityUtil.prepareForMemoryRemove(entity);

        return UnsafeUtil.unsafeRemove(entity, reason);
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


    // 播放 BossShow 演出
    /**
     * Start playing a BossShow cutscene for a specific player, targeting a specific entity.
     * The cutscene must already be loaded (either via {@code @RegisterBossShow} + JSON, or
     * via a JSON file under {@code config/eca/bossshow/<ns>/<name>.json}).
     * <p>
     * This bypasses history checks — the cutscene plays even if the viewer has seen it before.
     * For "natural" trigger semantics (honoring history), use {@link #playBossShowIfNew}.
     *
     * @param viewer     the player who will watch the cutscene
     * @param target     the target entity the cutscene is anchored to
     * @param cutsceneId the ResourceLocation id of the cutscene
     * @return true if playback started, false if no such definition or viewer already has a session
     */
    public static boolean playBossShow(ServerPlayer viewer,
                                       LivingEntity target,
                                       ResourceLocation cutsceneId) {
        BossShowDefinition def = BossShowManager.get(cutsceneId);
        if (def == null) return false;
        return BossShowPlaybackTracker.start(viewer, target, def, true);
    }

    // 仅当未看过时播放 BossShow 演出
    /**
     * Start playing a BossShow cutscene only if the viewer has not seen it before (honors history).
     * @param viewer     the player
     * @param target     the target entity
     * @param cutsceneId the cutscene id
     * @return true if playback started, false if already seen or not found
     */
    public static boolean playBossShowIfNew(ServerPlayer viewer,
                                            LivingEntity target,
                                            ResourceLocation cutsceneId) {
        BossShowDefinition def = BossShowManager.get(cutsceneId);
        if (def == null) return false;
        return BossShowPlaybackTracker.start(viewer, target, def, false);
    }

    // 停止 BossShow 演出
    /**
     * Stop the currently playing BossShow cutscene for the given viewer, if any.
     * @param viewer the player
     */
    public static void stopBossShow(ServerPlayer viewer) {
        BossShowPlaybackTracker.stop(viewer, false);
    }

    // 检查玩家是否正在观看 BossShow 演出
    /**
     * @param viewer the player
     * @return true if the player currently has an active BossShow session
     */
    public static boolean isBossShowPlaying(ServerPlayer viewer) {
        return BossShowPlaybackTracker.isPlaying(viewer);
    }

    // 推送自定义事件触发 BossShow 演出
    /**
     * Push a custom event name to the BossShow system. Every cutscene whose trigger is
     * {@link Trigger.Custom} with a matching {@code eventName} (case-sensitive, non-empty)
     * is started for the given viewer/target pair, honoring per-definition history.
     * <p>
     * Cutscenes are skipped silently if the viewer already has an active session, the
     * cutscene is empty, or history says the viewer has already seen it (per
     * {@code allowRepeat} on the definition).
     *
     * @param eventName the event name to match against {@code Trigger.Custom.eventName}
     * @param viewer    the player who will watch any matched cutscenes
     * @param target    the entity the cutscene is anchored to
     * @return the number of cutscenes actually started
     */
    public static int launchBossShowEvent(String eventName, ServerPlayer viewer, LivingEntity target) {
        if (eventName == null || eventName.isEmpty()) return 0;
        if (viewer == null || target == null) return 0;
        int started = 0;
        for (BossShowDefinition def : BossShowManager.getAllDefinitions().values()) {
            if (!(def.trigger() instanceof Trigger.Custom custom)) continue;
            if (!eventName.equals(custom.eventName())) continue;
            if (BossShowPlaybackTracker.start(viewer, target, def, false)) {
                started++;
            }
        }
        return started;
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
    public static boolean teleport(Entity entity, double x, double y, double z) {
        return EntityUtil.teleport(entity, x, y, z);
    }

    // 按ID获取实体
    /**
     * Resolve an entity by numeric id from the specified level using ECA resolver.
     * @param level the level to query
     * @param entityId the runtime entity id
     * @return the resolved entity, or null if not found
     */
    public static Entity getEntity(Level level, int entityId) {
        return EntityUtil.getEntity(level, entityId);
    }

    // 按UUID获取实体
    /**
     * Resolve an entity by UUID from the specified level using ECA resolver.
     * @param level the level to query
     * @param uuid the entity UUID
     * @return the resolved entity, or null if not found
     */
    public static Entity getEntity(Level level, UUID uuid) {
        return EntityUtil.getEntity(level, uuid);
    }

    // 按ID获取指定类型实体
    /**
     * Resolve an entity by id and cast it to the expected type.
     * @param level the level to query
     * @param entityId the runtime entity id
     * @param entityClass expected entity class
     * @return typed entity instance, or null if not found/type mismatch
     */
    public static <T extends Entity> T getEntity(Level level, int entityId, Class<T> entityClass) {
        return EntityUtil.getEntity(level, entityId, entityClass);
    }

    // 按UUID获取指定类型实体
    /**
     * Resolve an entity by UUID and cast it to the expected type.
     * @param level the level to query
     * @param uuid the entity UUID
     * @param entityClass expected entity class
     * @return typed entity instance, or null if not found/type mismatch
     */
    public static <T extends Entity> T getEntity(Level level, UUID uuid, Class<T> entityClass) {
        return EntityUtil.getEntity(level, uuid, entityClass);
    }

    // 全服按ID获取实体
    /**
     * Resolve an entity by id across all server levels.
     * @param server the minecraft server
     * @param entityId the runtime entity id
     * @return the resolved entity, or null if not found
     */
    public static Entity getEntity(MinecraftServer server, int entityId) {
        return EntityUtil.getEntity(server, entityId);
    }

    // 全服按UUID获取实体
    /**
     * Resolve an entity by UUID across all server levels.
     * @param server the minecraft server
     * @param uuid the entity UUID
     * @return the resolved entity, or null if not found
     */
    public static Entity getEntity(MinecraftServer server, UUID uuid) {
        return EntityUtil.getEntity(server, uuid);
    }

    // 获取维度全部实体
    /**
     * Get all entities in a level using ECA resolver.
     * @param level the level to query
     * @return list of entities, empty list if none
     */
    public static List<Entity> getEntities(Level level) {
        return EntityUtil.getEntities(level);
    }

    // 获取维度范围实体
    /**
     * Get entities in the specified area from a level.
     * @param level the level to query
     * @param area query area
     * @return entities whose bounding boxes intersect the area
     */
    public static List<Entity> getEntities(Level level, AABB area) {
        return EntityUtil.getEntities(level, area);
    }

    // 获取维度筛选实体
    /**
     * Get entities in a level with a custom filter.
     * @param level the level to query
     * @param filter filter predicate
     * @return filtered entities
     */
    public static List<Entity> getEntities(Level level, Predicate<Entity> filter) {
        return EntityUtil.getEntities(level, filter);
    }

    // 获取维度范围筛选实体
    /**
     * Get entities in area with an additional custom filter.
     * @param level the level to query
     * @param area query area
     * @param filter filter predicate
     * @return filtered entities in area
     */
    public static List<Entity> getEntities(Level level, AABB area, Predicate<Entity> filter) {
        return EntityUtil.getEntities(level, area, filter);
    }

    // 获取维度全部指定类型实体
    /**
     * Get all entities of the specified type in a level.
     * @param level the level to query
     * @param entityClass expected class
     * @return typed entity list
     */
    public static <T extends Entity> List<T> getEntities(Level level, Class<T> entityClass) {
        return EntityUtil.getEntities(level, entityClass);
    }

    // 获取维度范围指定类型实体
    /**
     * Get entities of the specified type in the given area.
     * @param level the level to query
     * @param area query area
     * @param entityClass expected class
     * @return typed entity list in area
     */
    public static <T extends Entity> List<T> getEntities(Level level, AABB area, Class<T> entityClass) {
        return EntityUtil.getEntities(level, area, entityClass);
    }

    // 获取全服全部实体
    /**
     * Get all entities across all server levels.
     * @param server the minecraft server
     * @return all resolved entities
     */
    public static List<Entity> getEntities(MinecraftServer server) {
        return EntityUtil.getEntities(server);
    }

    // 获取全服筛选实体
    /**
     * Get entities across all server levels with custom filter.
     * @param server the minecraft server
     * @param filter filter predicate
     * @return filtered entities from all levels
     */
    public static List<Entity> getEntities(MinecraftServer server, Predicate<Entity> filter) {
        return EntityUtil.getEntities(server, filter);
    }

    // ==================== 位置锁定系统 ====================

    // 锁定实体位置（当前位置）
    /**
     * Lock entity location at its current position.
     * When location is locked, any position changes will be reverted.
     * Dimension changes are automatically handled and the locked position will be updated to the new dimension.
     * @param entity the entity to lock
     */
    public static void lockLocation(Entity entity) {
        EntityLocationManager.lockLocation(entity);
    }

    // 锁定实体到指定位置
    /**
     * Lock entity location at the specified position.
     * The entity will be teleported to and held at the given coordinates.
     * @param entity the entity to lock
     * @param position the position to lock the entity at
     */
    public static void lockLocation(Entity entity, Vec3 position) {
        EntityLocationManager.lockLocation(entity, position);
    }

    // 解锁实体位置
    /**
     * Unlock entity location.
     * After unlocking, the entity can be moved/teleported freely.
     * @param entity the entity to unlock
     */
    public static void unlockLocation(Entity entity) {
        EntityLocationManager.unlockLocation(entity);
    }

    // 检查位置是否被锁定
    /**
     * Check if an entity's location is locked.
     * @param entity the entity to check
     * @return true if location is locked, false otherwise
     */
    public static boolean isLocationLocked(Entity entity) {
        return EntityLocationManager.isLocationLocked(entity);
    }

    // 获取锁定的位置
    /**
     * Get the locked location of an entity.
     * @param entity the entity
     * @return the locked location, or null if not locked
     */
    public static Vec3 getLockedLocation(Entity entity) {
        return EntityLocationManager.getLockedPosition(entity);
    }

    // ==================== 最大生命值 API ====================

    // 设置实体最大生命值
    /**
     * Set entity max health to a precise target value.
     * This method reverse-calculates the required base value from current attribute modifiers,
     * so that getMaxHealth() returns exactly the target value after all modifiers are applied.
     * Requires "Unlock Attribute Limits" config to be enabled for values above 1024.
     * @param entity the living entity
     * @param maxHealth the target max health value (must be > 0)
     * @return true if modification succeeded
     */
    public static boolean setMaxHealth(LivingEntity entity, float maxHealth) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        return EntityUtil.setMaxHealth(entity, maxHealth);
    }

    // 锁定最大生命值
    /**
     * Lock entity max health at a specific value.
     * When locked, the entity's max health is forced to the locked value every tick
     * via reverse-calculating the attribute base value.
     * Any external modifications (equipment, potions, other mods) will be overridden each tick.
     * @param entity the living entity
     * @param value the max health lock value
     */
    public static void lockMaxHealth(LivingEntity entity, float value) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        HealthLockManager.setMaxHealthLock(entity, value);
        EntityUtil.setMaxHealth(entity, value);
    }

    // 解锁最大生命值
    /**
     * Unlock entity max health.
     * After unlocking, the entity's max health can be modified normally by equipment, potions, etc.
     * @param entity the living entity
     */
    public static void unlockMaxHealth(LivingEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        HealthLockManager.removeMaxHealthLock(entity);
    }

    // 获取最大生命值锁定值
    /**
     * Get the current max health lock value for an entity.
     * @param entity the living entity
     * @return the locked max health value, or null if not locked
     */
    public static Float getLockedMaxHealth(LivingEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        return HealthLockManager.getMaxHealthLock(entity);
    }

    // 检查最大生命值是否被锁定
    /**
     * Check if an entity has max health locked.
     * @param entity the living entity
     * @return true if max health is locked, false otherwise
     */
    public static boolean isMaxHealthLocked(LivingEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        return HealthLockManager.getMaxHealthLock(entity) != null;
    }

    // 添加血量白名单关键词
    /**
     * Add a keyword to the health whitelist.
     * Fields containing this keyword will be modified during health modification.
     * @param keyword the keyword to add (case-insensitive)
     */
    public static void addHealthWhitelistKeyword(String keyword) {
        EntityUtil.addHealthWhitelistKeyword(keyword);
    }

    // 移除血量白名单关键词
    /**
     * Remove a keyword from the health whitelist.
     * @param keyword the keyword to remove (case-insensitive)
     */
    public static void removeHealthWhitelistKeyword(String keyword) {
        EntityUtil.removeHealthWhitelistKeyword(keyword);
    }

    // 获取所有血量白名单关键词
    /**
     * Get all health whitelist keywords.
     * @return a read-only copy of the health whitelist keywords
     */
    public static Set<String> getHealthWhitelistKeywords() {
        return EntityUtil.getHealthWhitelistKeywords();
    }

    // 添加血量黑名单关键词
    /**
     * Add a keyword to the health blacklist.
     * Fields containing this keyword will NOT be modified during health modification.
     * @param keyword the keyword to add (case-insensitive)
     */
    public static void addHealthBlacklistKeyword(String keyword) {
        EntityUtil.addHealthBlacklistKeyword(keyword);
    }

    // 移除血量黑名单关键词
    /**
     * Remove a keyword from the health blacklist.
     * @param keyword the keyword to remove (case-insensitive)
     */
    public static void removeHealthBlacklistKeyword(String keyword) {
        EntityUtil.removeHealthBlacklistKeyword(keyword);
    }

    // 获取所有血量黑名单关键词
    /**
     * Get all health blacklist keywords.
     * @return a read-only copy of the health blacklist keywords
     */
    public static Set<String> getHealthBlacklistKeywords() {
        return EntityUtil.getHealthBlacklistKeywords();
    }


    // ============ 实体扩展 API ============

    // 获取实体扩展注册表
    /**
     * Get the entity extension registry.
     * @return unmodifiable map of EntityType to EntityExtension
     */
    public static Map<EntityType<?>, EntityExtension> getEntityExtensionRegistry() {
        return EntityExtensionManager.getRegistryView();
    }

    // 获取当前维度活跃实体扩展类型列表
    /**
     * Get active entity extension types for a level.
     * @param level the server level
     * @return unmodifiable map of EntityType to active count
     * @throws IllegalArgumentException if level is null
     */
    public static Map<EntityType<?>, Integer> getActiveEntityExtensionTypes(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        return EntityExtensionManager.getActiveTypeCounts(level);
    }

    // 获取当前维度生效的实体扩展
    /**
     * Get the active entity extension for a level.
     * @param level the server level
     * @return active EntityExtension, or null if none
     * @throws IllegalArgumentException if level is null
     */
    public static EntityExtension getActiveEntityExtension(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        EntityType<?> type = EntityExtensionManager.getActiveType(level);
        return type != null ? EntityExtensionManager.getExtension(type) : null;
    }

    // 清空当前维度活跃表
    /**
     * Clear the active entity extension table for a level.
     * @param level the server level
     * @return void
     * @throws IllegalArgumentException if level is null
     */
    public static void clearActiveEntityExtensionTable(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        EntityExtensionManager.clearActiveTable(level);
    }


    // ============ 全局效果覆盖 API ============

    // 设置全局雾气效果
    /**
     * Set global fog effect override for a dimension.
     * This directly overrides the fog effect in the effect cache without changing the current priority.
     * Any entity extension with priority >= current cached priority can still take over later.
     * @param level the server level (dimension)
     * @param data the fog data to apply
     * @throws IllegalArgumentException if level or data is null
     */
    public static void setGlobalFog(ServerLevel level, FogData data) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        if (data == null) {
            throw new IllegalArgumentException("FogData cannot be null");
        }
        GlobalEffectOverrideManager.setFog(level, data);
    }

    // 清除全局雾气效果
    /**
     * Clear global fog effect override for a dimension.
     * @param level the server level (dimension)
     * @throws IllegalArgumentException if level is null
     */
    public static void clearGlobalFog(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        GlobalEffectOverrideManager.clearFog(level);
    }

    // 设置全局天空盒效果
    /**
     * Set global skybox effect override for a dimension.
     * This directly overrides the skybox effect in the effect cache without changing the current priority.
     * @param level the server level (dimension)
     * @param data the skybox data to apply
     * @throws IllegalArgumentException if level or data is null
     */
    public static void setGlobalSkybox(ServerLevel level, SkyboxData data) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        if (data == null) {
            throw new IllegalArgumentException("SkyboxData cannot be null");
        }
        GlobalEffectOverrideManager.setSkybox(level, data);
    }

    // 清除全局天空盒效果
    /**
     * Clear global skybox effect override for a dimension.
     * @param level the server level (dimension)
     * @throws IllegalArgumentException if level is null
     */
    public static void clearGlobalSkybox(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        GlobalEffectOverrideManager.clearSkybox(level);
    }

    // 设置全局战斗音乐效果
    /**
     * Set global combat music effect override for a dimension.
     * This directly overrides the combat music in the effect cache without changing the current priority.
     * @param level the server level (dimension)
     * @param data the music data to apply
     * @throws IllegalArgumentException if level or data is null
     */
    public static void setGlobalMusic(ServerLevel level, MusicData data) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        if (data == null) {
            throw new IllegalArgumentException("MusicData cannot be null");
        }
        GlobalEffectOverrideManager.setMusic(level, data);
    }

    // 清除全局战斗音乐效果
    /**
     * Clear global combat music effect override for a dimension.
     * @param level the server level (dimension)
     * @throws IllegalArgumentException if level is null
     */
    public static void clearGlobalMusic(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        GlobalEffectOverrideManager.clearMusic(level);
    }

    // 清除维度所有全局效果覆盖
    /**
     * Clear all global effect overrides (fog, skybox, music) for a dimension.
     * @param level the server level (dimension)
     * @throws IllegalArgumentException if level is null
     */
    public static void clearAllGlobalEffects(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        GlobalEffectOverrideManager.clearAll(level);
    }


    // ============ 禁生成 API ============

    // 禁止生成
    /**
     * Ban the specified entity type from spawning in a level.
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
    public static boolean banSpawn(ServerLevel level, EntityType<?> type, int timeInSeconds) {
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

    // 解除禁生成
    /**
     * Unban the specified entity type, allowing it to spawn again.
     * @param level the server level
     * @param type the entity type to unban
     * @return true if a ban was removed
     * @throws IllegalArgumentException if level or type is null
     */
    public static boolean unbanSpawn(ServerLevel level, EntityType<?> type) {
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

    // 解除所有禁生成
    /**
     * Unban all entity types, allowing all spawning in the level.
     * @param level the server level
     * @throws IllegalArgumentException if level is null
     */
    public static void unbanAllSpawns(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("Level cannot be null");
        }
        SpawnBanManager.clearAllBans(level);
    }


    // 危险！需要开启激进攻击逻辑配置，会尝试对目标实体的所属mod的全部布尔和void方法进行return transformation
    /**
     * Enable AllReturn for the specified entity's mod.
     * DANGER! Requires "Enable Radical Logic" in Attack config.
     * @param entity the entity used to resolve the target mod package
     * @return true if AllReturn was enabled successfully
     */
    public static boolean enableAllReturn(Entity entity) {
        if (entity == null) return false;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()) {
            EcaLogger.warn("AllReturn requires Attack Radical Logic to be enabled in config");
            return false;
        }
        if (EcaAgent.getInstrumentation() == null) {
            EcaLogger.warn("AllReturn: Agent is not initialized");
            return false;
        }

        String binaryName = entity.getClass().getName();
        if (TransformerWhitelist.isProtected(binaryName)) return false;

        String internalPrefix = toInternalPrefix(binaryName);
        if (internalPrefix == null) return false;

        AllReturnToggle.setEnabled(true);
        AllReturnToggle.addAllowedPrefix(internalPrefix);
        return true;
    }

    // 关闭AllReturn
    /**
     * Disable AllReturn and clear all targets.
     */
    public static void disableAllReturn() {
        AllReturnToggle.clearAll();
    }

    // 检查AllReturn是否启用
    /**
     * Check if AllReturn is currently enabled.
     * @return true if AllReturn is enabled
     */
    public static boolean isAllReturnEnabled() {
        return AllReturnToggle.isEnabled();
    }

    // 全局AllReturn开关（影响所有已加载的mod）
    /**
     * Enable or disable global AllReturn mode.
     * DANGER! Requires "Enable Radical Logic" in Attack config.
     * @param enable true to enable, false to disable
     * @return true if operation succeeded
     */
    public static boolean setGlobalAllReturn(boolean enable) {
        if (!enable) {
            AllReturnToggle.clearAll();
            return true;
        }

        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()) {
            EcaLogger.warn("GlobalAllReturn requires Attack Radical Logic to be enabled in config");
            return false;
        }

        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            EcaLogger.warn("GlobalAllReturn: Agent is not initialized");
            return false;
        }

        Set<String> collectedPrefixes = new HashSet<>();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (!inst.isModifiableClass(clazz)) continue;
            if (clazz.isInterface() || clazz.isArray() || clazz.isPrimitive()) continue;

            String className = clazz.getName();
            if (TransformerWhitelist.isProtected(className)) continue;

            String prefix = toInternalPrefix(className);
            if (prefix != null) collectedPrefixes.add(prefix);
        }

        if (collectedPrefixes.isEmpty()) {
            EcaLogger.warn("GlobalAllReturn: No candidate package prefixes found");
            return false;
        }

        AllReturnToggle.setEnabled(true);
        for (String prefix : collectedPrefixes) {
            AllReturnToggle.addAllowedPrefix(prefix);
        }
        return true;
    }

    private static String toInternalPrefix(String binaryName) {
        int lastDot = binaryName.lastIndexOf('.');
        if (lastDot <= 0) return null;
        return binaryName.substring(0, lastDot + 1).replace('.', '/');
    }

    // ==================== 白名单 API ====================

    // --- AllReturn 白名单：跳过 AllReturn 转换，防御性 Hook 仍然生效 ---

    // 添加 AllReturn 白名单前缀
    /**
     * Add a package prefix to the AllReturn whitelist.
     * Classes in whitelisted packages will NOT be affected by AllReturn transformation.
     * Defensive hooks (getHealth, isAlive, etc.) will still apply.
     * @param packagePrefix the package prefix (e.g., "com.yourmod.")
     */
    public static void addAllReturnWhitelist(String packagePrefix) {
        TransformerWhitelist.addAllReturn(packagePrefix);
    }

    // 移除 AllReturn 白名单前缀
    /**
     * Remove a package prefix from the AllReturn whitelist.
     * Built-in entries cannot be removed.
     * @param packagePrefix the package prefix to remove
     * @return true if successfully removed
     */
    public static boolean removeAllReturnWhitelist(String packagePrefix) {
        return TransformerWhitelist.removeAllReturn(packagePrefix);
    }

    // --- 转换白名单：跳过全部 ECA 转换（AllReturn + 防御性 Hook） ---

    // 添加转换白名单前缀
    /**
     * Add a package prefix to the transform whitelist.
     * Classes in whitelisted packages will be completely skipped by ALL ECA transformations,
     * including AllReturn AND defensive hooks (getHealth, isAlive, etc.).
     * @param packagePrefix the package prefix (e.g., "com.yourmod.")
     */
    public static void addTransformWhitelist(String packagePrefix) {
        TransformerWhitelist.addTransform(packagePrefix);
    }

    // 移除转换白名单前缀
    /**
     * Remove a package prefix from the transform whitelist.
     * Built-in entries (JDK, Minecraft, Forge, etc.) cannot be removed.
     * @param packagePrefix the package prefix to remove
     * @return true if successfully removed
     */
    public static boolean removeTransformWhitelist(String packagePrefix) {
        return TransformerWhitelist.removeTransform(packagePrefix);
    }

    // --- 查询 ---

    // 检查类是否在 AllReturn 白名单中
    /**
     * Check if a class is protected from AllReturn transformation.
     * @param className the binary class name (e.g., "com.yourmod.MyClass")
     * @return true if the class is protected from AllReturn
     */
    public static boolean isAllReturnWhitelisted(String className) {
        return TransformerWhitelist.isProtected(className);
    }

    // 检查类是否在转换白名单中
    /**
     * Check if a class is protected from all ECA transformations.
     * @param className the binary class name (e.g., "com.yourmod.MyClass")
     * @return true if the class is fully protected
     */
    public static boolean isTransformWhitelisted(String className) {
        return TransformerWhitelist.isSystemProtected(className);
    }

    // 获取所有白名单前缀
    /**
     * Get all whitelist prefixes (both levels, built-in + custom).
     * @return unmodifiable set of all prefixes
     */
    public static Set<String> getAllWhitelistedPackages() {
        return TransformerWhitelist.getAll();
    }

    // --- 兼容旧 API ---

    // 添加受保护的包名前缀（已弃用，请使用 addAllReturnWhitelist）
    /** @deprecated Use {@link #addAllReturnWhitelist(String)} instead. */
    @Deprecated
    public static void addProtectedPackage(String packagePrefix) {
        addAllReturnWhitelist(packagePrefix);
    }

    // 移除受保护的包名前缀（已弃用，请使用 removeAllReturnWhitelist）
    /** @deprecated Use {@link #removeAllReturnWhitelist(String)} instead. */
    @Deprecated
    public static boolean removeProtectedPackage(String packagePrefix) {
        return removeAllReturnWhitelist(packagePrefix);
    }

    // 检查包名是否受保护（已弃用，请使用 isAllReturnWhitelisted）
    /** @deprecated Use {@link #isAllReturnWhitelisted(String)} instead. */
    @Deprecated
    public static boolean isPackageProtected(String className) {
        return isAllReturnWhitelisted(className);
    }

    // 获取所有受保护的包名前缀（已弃用，请使用 getAllWhitelistedPackages）
    /** @deprecated Use {@link #getAllWhitelistedPackages()} instead. */
    @Deprecated
    public static Set<String> getAllProtectedPackages() {
        return getAllWhitelistedPackages();
    }

    // ==================== 强加载系统 ====================

    // 设置实体强加载状态
    /**
     * Set the force loading state of an entity.
     * When enabled, the entity's chunk is kept loaded (EntityTicking level) regardless of player proximity.
     * The force load ticket follows the entity as it moves between chunks.
     * When disabled, the chunk ticket is released. Has no effect on entities force-loaded via EntityExtension.
     * @param entity the living entity
     * @param level the server level the entity is in
     * @param forceLoad true to enable force loading, false to disable
     */
    public static void setForceLoading(LivingEntity entity, ServerLevel level, boolean forceLoad) {
        if (entity == null || level == null) return;
        if (forceLoad) {
            ForceLoadingManager.enableForceLoading(entity, level);
        } else {
            ForceLoadingManager.disableForceLoading(entity, level);
        }
    }

    // 检查实体是否被强加载（包含扩展系统和手动API两种来源）
    /**
     * Check if an entity is currently force loaded.
     * Returns true if force loaded via EntityExtension or via the API.
     * @param entity the entity to check
     * @return true if the entity is force loaded
     */
    public static boolean isForceLoaded(LivingEntity entity) {
        if (entity == null) return false;
        return ForceLoadingManager.shouldForceLoad(entity);
    }

    private EcaAPI() {}
}
