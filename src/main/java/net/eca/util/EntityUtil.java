package net.eca.util;

import net.eca.config.EcaConfiguration;
import net.eca.network.ClientRemovePacket;
import net.eca.network.EntityHealthSyncPacket;
import net.eca.network.EntityContainerCheckRequestPacket;
import net.eca.network.NetworkHandler;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.health.HealthLockManager;
import net.eca.util.health.HealthAnalyzerManager;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.core.SectionPos;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraftforge.entity.PartEntity;
import net.eca.util.selector.EcaEntitySelector;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

@SuppressWarnings({"unchecked", "rawtypes"})
//实体工具类
public class EntityUtil {

    //EntityDataAccessor 血量锁定（神秘文本血）+ 禁疗 + 无敌状态 + 最大生命值锁定
    public static EntityDataAccessor<String> HEALTH_LOCK_VALUE;
    public static EntityDataAccessor<String> HEAL_BAN_VALUE;
    public static EntityDataAccessor<Boolean> INVULNERABLE;
    public static EntityDataAccessor<String> MAX_HEALTH_LOCK_VALUE;

    //标记当前调用来自同步包，防止重复发包
    private static final ThreadLocal<Boolean> IS_FROM_SYNC = ThreadLocal.withInitial(() -> false);

    //标记是否正在调用实体自身 setHealth，防止递归重入
    private static final ThreadLocal<Boolean> IS_IN_ENTITY_HEALTH_SETTER = ThreadLocal.withInitial(() -> false);

    //正在切换维度的实体UUID集合（线程安全）
    private static final Set<UUID> DIMENSION_CHANGING_ENTITIES = ConcurrentHashMap.newKeySet();

    private static final StackWalker STACK_WALKER = StackWalker.getInstance();
    private static final List<String> VANILLA_ALLOWED_PREFIXES = List.of(
        "java.", "sun.", "jdk.", "com.sun.",
        "net.minecraft.", "com.mojang.",
        "net.minecraftforge.", "cpw.mods.",
        "org.spongepowered.asm.",
        "net.eca."
    );

    //检查调用栈中是否存在非原版/非ECA的外部调用者
    public static boolean hasExternalCaller(int limit) {
        return STACK_WALKER.walk(frames ->
            frames.skip(2)
                  .limit(limit)
                  .anyMatch(f -> {
                      String cls = f.getClassName();
                      for (String prefix : VANILLA_ALLOWED_PREFIXES) {
                          if (cls.startsWith(prefix)) return false;
                      }
                      return true;
                  })
        );
    }

    //客户端容器检查挂起请求
    private static final Map<ContainerCheckKey, CompletableFuture<Map<String, Boolean>>> PENDING_CLIENT_CONTAINER_CHECKS = new ConcurrentHashMap<>();

    private record ContainerCheckKey(UUID requestId, UUID entityUuid) {}

    //生命值关键词白名单（可动态添加）
    private static final Set<String> HEALTH_WHITELIST_KEYWORDS = ConcurrentHashMap.newKeySet();

    //生命值修改黑名单（可动态添加）
    private static final Set<String> HEALTH_BLACKLIST_KEYWORDS = ConcurrentHashMap.newKeySet();


    static {
        //初始化生命值白名单默认值
        HEALTH_WHITELIST_KEYWORDS.addAll(List.of("health", "heal", "hp", "life", "vital"));

        //初始化生命值黑名单默认值
        HEALTH_BLACKLIST_KEYWORDS.addAll(List.of(
            "ai", "goal", "target", "brain", "memory", "sensor", "skill", "ability", "spell", "cast",
            "animation", "swing", "cooldown", "duration", "delay", "timer", "tick", "time",
            "age", "lifetime", "deathtime", "hurttime", "invulnerabletime", "hurt", "max"
        ));
    }

    //检查实体是否正在切换维度
    /**
     * Check if an entity is currently changing dimensions.
     * This method checks the entity's removal reason to determine if it's being removed due to dimension change.
     * Used internally by Mixins to allow dimension change operations even for invulnerable entities.
     *
     * @param entity the entity to check
     * @return true if the entity's removal reason is CHANGED_DIMENSION, false otherwise
     */
    public static boolean isChangingDimension(Entity entity) {
        if (entity == null) {
            return false;
        }

        // 使用UUID集合判断，避免字段读取的时序问题和残留问题
        return DIMENSION_CHANGING_ENTITIES.contains(entity.getUUID());
    }

    //通过UUID检查实体是否正在切换维度（供容器层使用）
    public static boolean isChangingDimension(UUID uuid) {
        return uuid != null && DIMENSION_CHANGING_ENTITIES.contains(uuid);
    }

    public static Entity getEntity(Level level, int entityId) {
        return EcaEntitySelector.getEntity(level, entityId);
    }

    public static Entity getEntity(Level level, UUID uuid) {
        return EcaEntitySelector.getEntity(level, uuid);
    }

    public static Entity getEntity(MinecraftServer server, int entityId) {
        return EcaEntitySelector.getEntity(server, entityId);
    }

    public static Entity getEntity(MinecraftServer server, UUID uuid) {
        return EcaEntitySelector.getEntity(server, uuid);
    }

    public static <T extends Entity> T getEntity(Level level, int entityId, Class<T> entityClass) {
        return EcaEntitySelector.getEntity(level, entityId, entityClass);
    }

    public static <T extends Entity> T getEntity(Level level, UUID uuid, Class<T> entityClass) {
        return EcaEntitySelector.getEntity(level, uuid, entityClass);
    }

    public static List<Entity> getEntities(Level level) {
        return EcaEntitySelector.getEntities(level);
    }

    public static List<Entity> getEntities(Level level, AABB area) {
        return EcaEntitySelector.getEntities(level, area);
    }

    public static List<Entity> getEntities(Level level, Predicate<Entity> filter) {
        return EcaEntitySelector.getEntities(level, filter);
    }

    public static List<Entity> getEntities(Level level, AABB area, Predicate<Entity> filter) {
        return EcaEntitySelector.getEntities(level, area, filter);
    }

    public static <T extends Entity> List<T> getEntities(Level level, Class<T> entityClass) {
        return EcaEntitySelector.getEntities(level, entityClass);
    }

    public static <T extends Entity> List<T> getEntities(Level level, AABB area, Class<T> entityClass) {
        return EcaEntitySelector.getEntities(level, area, entityClass);
    }

    public static List<Entity> getEntities(MinecraftServer server) {
        return EcaEntitySelector.getEntities(server);
    }

    public static List<Entity> getEntities(MinecraftServer server, Predicate<Entity> filter) {
        return EcaEntitySelector.getEntities(server, filter);
    }

    //检查实体在服务端关键容器中的存在情况
    public static Map<String, Boolean> checkEntityInContainers(ServerLevel level, UUID entityUUID) {
        Map<String, Boolean> result = checkEntityInServerContainers(level, entityUUID);
        Map<String, Boolean> clientResult = requestClientContainerCheck(level, entityUUID);
        result.put("ClientCheck.response", clientResult != null);
        if (clientResult != null) {
            result.putAll(clientResult);
        }
        return result;
    }

    private static Map<String, Boolean> checkEntityInServerContainers(ServerLevel level, UUID entityUUID) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (level == null || entityUUID == null) {
            return result;
        }

        Entity entity = getEntity(level, entityUUID);
        result.put("ServerLevel.getEntity(uuid)", entity != null);

        PersistentEntitySectionManager<Entity> entityManager = level.entityManager;

        try {
            result.put("PersistentEntitySectionManager.knownUuids", entityManager.knownUuids.contains(entityUUID));
        } catch (Exception e) {
            result.put("PersistentEntitySectionManager.knownUuids", false);
        }

        try {
            boolean inCorrectSection = false;
            if (entity != null) {
                long sectionKey = SectionPos.asLong(entity.blockPosition());
                EntitySection<Entity> section = entityManager.sectionStorage.sections.get(sectionKey);
                inCorrectSection = section != null && section.getEntities().anyMatch(e -> e == entity);
            }
            result.put("EntitySectionStorage.sections", inCorrectSection);
        } catch (Exception e) {
            result.put("EntitySectionStorage.sections", false);
        }

        try {
            result.put("EntityLookup.byUuid", entityManager.visibleEntityStorage.byUuid.containsKey(entityUUID));
        } catch (Exception e) {
            result.put("EntityLookup.byUuid", false);
        }

        try {
            boolean byId = entity != null && entityManager.visibleEntityStorage.byId.containsKey(entity.getId());
            result.put("EntityLookup.byId", byId);
        } catch (Exception e) {
            result.put("EntityLookup.byId", false);
        }

        try {
            result.put("ServerLevel.entityTickList", entity != null && level.entityTickList.contains(entity));
        } catch (Exception e) {
            result.put("ServerLevel.entityTickList", false);
        }

        try {
            result.put("ChunkMap.entityMap", entity != null && level.chunkSource.chunkMap.entityMap.containsKey(entity.getId()));
        } catch (Exception e) {
            result.put("ChunkMap.entityMap", false);
        }

        try {
            boolean seenByValid = false;
            if (entity != null) {
                Object tracked = level.chunkSource.chunkMap.entityMap.get(entity.getId());
                if (tracked != null) {
                    ChunkMap.TrackedEntity trackedEntity = (ChunkMap.TrackedEntity) tracked;
                    seenByValid = trackedEntity.seenBy != null;
                }
            }
            result.put("ChunkMap.TrackedEntity.seenBy", seenByValid);
        } catch (Exception e) {
            result.put("ChunkMap.TrackedEntity.seenBy", false);
        }

        try {
            result.put("Entity.levelCallback", entity != null && entity.levelCallback != EntityInLevelCallback.NULL);
        } catch (Exception e) {
            result.put("Entity.levelCallback", false);
        }

        try {
            boolean inPlayers = !(entity instanceof ServerPlayer) || level.players.contains(entity);
            result.put("ServerLevel.players", inPlayers);
        } catch (Exception e) {
            result.put("ServerLevel.players", false);
        }

        try {
            boolean inNavigatingMobs = !(entity instanceof Mob) || level.navigatingMobs.contains(entity);
            result.put("ServerLevel.navigatingMobs", inNavigatingMobs);
        } catch (Exception e) {
            result.put("ServerLevel.navigatingMobs", false);
        }

        return result;
    }

    private static Map<String, Boolean> requestClientContainerCheck(ServerLevel level, UUID entityUUID) {
        if (level == null || entityUUID == null) {
            return null;
        }

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            EcaLogger.info("[EntityUtil] Client container check skipped: no player in level, uuid={}", entityUUID);
            return null;
        }

        ServerPlayer requester = players.get(0);
        UUID requestId = UUID.randomUUID();
        ContainerCheckKey key = new ContainerCheckKey(requestId, entityUUID);
        CompletableFuture<Map<String, Boolean>> future = new CompletableFuture<>();
        PENDING_CLIENT_CONTAINER_CHECKS.put(key, future);

        try {
            NetworkHandler.sendToPlayer(new EntityContainerCheckRequestPacket(requestId, entityUUID), requester);
            Map<String, Boolean> result = future.get(1, TimeUnit.SECONDS);
            if (result == null) {
                EcaLogger.info("[EntityUtil] Client container check failed, uuid={}", entityUUID);
            }
            return result;
        } catch (TimeoutException e) {
            EcaLogger.info("[EntityUtil] Client container check timeout, uuid={}", entityUUID);
            return null;
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Client container check error, uuid={}, msg={}", entityUUID, e.getMessage());
            return null;
        } finally {
            PENDING_CLIENT_CONTAINER_CHECKS.remove(key);
        }
    }

    public static void completeClientContainerCheck(UUID requestId, UUID entityUuid, Map<String, Boolean> result) {
        if (requestId == null || entityUuid == null) {
            return;
        }
        ContainerCheckKey key = new ContainerCheckKey(requestId, entityUuid);
        CompletableFuture<Map<String, Boolean>> future = PENDING_CLIENT_CONTAINER_CHECKS.remove(key);
        if (future != null) {
            future.complete(result);
        }
    }

    //按实体实例复活关键容器（服务端）
    public static Map<String, Boolean> reviveAllContainers(LivingEntity entity) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (entity == null) {
            return result;
        }
        if (!EcaConfiguration.getDefenceEnableRadicalLogicSafely()) {
            return result;
        }
        if (!(entity.level() instanceof ServerLevel serverLevel)) {
            return result;
        }
        if (isChangingDimension(entity)) {
            EcaLogger.info("[EntityUtil] Revive containers skipped: changing dimension, uuid={}", entity.getUUID());
            return result;
        }
        return reviveAllContainers(serverLevel, entity.getUUID());
    }

    //按UUID复活实体关键容器（服务端）
    public static Map<String, Boolean> reviveAllContainers(ServerLevel level, UUID entityUUID) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (!EcaConfiguration.getDefenceEnableRadicalLogicSafely()) {
            return result;
        }
        if (level == null || entityUUID == null) {
            return result;
        }

        Entity entity = getEntity(level, entityUUID);
        if (entity == null) {
            EcaLogger.info("[EntityUtil] Revive containers skipped: entity not found, uuid={}", entityUUID);
            return result;
        }

        if (isChangingDimension(entity)) {
            EcaLogger.info("[EntityUtil] Revive containers skipped: changing dimension, uuid={}", entityUUID);
            return result;
        }

        PersistentEntitySectionManager<Entity> entityManager = level.entityManager;
        Map<String, Boolean> before = checkEntityInServerContainers(level, entityUUID);

        // 先尝试修复 levelCallback，避免仅回调缺失时走 addNewEntity 造成不稳定
        if (!Boolean.TRUE.equals(before.get("Entity.levelCallback"))) {
            rebuildEntityLevelCallback(entityManager, entity);
        }

        //补Section/Lookup/levelCallback基础注册
        if (!Boolean.TRUE.equals(before.get("EntitySectionStorage.sections"))
            || !Boolean.TRUE.equals(before.get("EntityLookup.byUuid"))
            || !Boolean.TRUE.equals(before.get("EntityLookup.byId"))) {
            try {
                // addNewEntity 内部会检查 knownUuids，如果已存在则直接返回 false
                // 受保护实体的 UUID 通常不会从 knownUuids 移除，需要先临时移除再重新注册
                entityManager.knownUuids.remove(entityUUID);
                entityManager.addNewEntity(entity);
            } catch (Exception e) {
                // 确保 knownUuids 不会因异常丢失
                entityManager.knownUuids.add(entityUUID);
                EcaLogger.info("[EntityUtil] addNewEntity failed, uuid={}, msg={}", entityUUID, e.getMessage());
            }
        } else if (!Boolean.TRUE.equals(before.get("PersistentEntitySectionManager.knownUuids"))) {
            entityManager.knownUuids.add(entityUUID);
        }

        //补TickList
        if (!level.entityTickList.contains(entity)) {
            level.entityTickList.add(entity);
        }

        //补ChunkMap追踪
        if (!level.chunkSource.chunkMap.entityMap.containsKey(entity.getId())
            || !Boolean.TRUE.equals(before.get("ChunkMap.TrackedEntity.seenBy"))) {
            try {
                entityManager.callbacks.onTrackingStart(entity);
            } catch (Exception e) {
                if (isAlreadyTrackedException(e)) {
                    EcaLogger.info("[EntityUtil] Ignore already tracked during revive, uuid={}", entityUUID);
                } else {
                    EcaLogger.info("[EntityUtil] onTrackingStart failed, uuid={}, msg={}", entityUUID, e.getMessage());
                }
            }
        }

        //按类型补容器
        if (entity instanceof ServerPlayer player && !level.players.contains(player)) {
            level.players.add(player);
        }
        if (entity instanceof Mob mob && !level.navigatingMobs.contains(mob)) {
            level.navigatingMobs.add(mob);
        }

        // addNewEntity 返回 false 时不会抛异常，这里再兜底一次回调重建
        if (entity.levelCallback == EntityInLevelCallback.NULL) {
            rebuildEntityLevelCallback(entityManager, entity);
        }

        result.putAll(checkEntityInServerContainers(level, entityUUID));
        return result;
    }

    private static void rebuildEntityLevelCallback(PersistentEntitySectionManager<Entity> entityManager, Entity entity) {
        if (entityManager == null || entity == null) {
            return;
        }
        if (entity.levelCallback != EntityInLevelCallback.NULL) {
            return;
        }

        try {
            long sectionKey = SectionPos.asLong(entity.blockPosition());
            EntitySection<Entity> section = entityManager.sectionStorage.getOrCreateSection(sectionKey);
            if (!section.getEntities().anyMatch(current -> current == entity)) {
                section.add(entity);
            }
            EntityInLevelCallback callback = entityManager.new Callback(entity, sectionKey, section);
            entity.setLevelCallback(callback);
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] rebuild levelCallback failed, uuid={}, msg={}", entity.getUUID(), e.getMessage());
        }
    }

    //按UUID复活实体（清除死亡状态 + 容器修复）
    public static void revive(ServerLevel level, UUID entityUUID) {
        if (level == null || entityUUID == null) {
            return;
        }
        Entity entity = getEntity(level, entityUUID);
        if (!(entity instanceof LivingEntity livingEntity)) {
            return;
        }
        // 跳过正在切换维度的实体，防止旧实例被错误复活到原维度
        if (isChangingDimension(entity)) {
            return;
        }
        revive(livingEntity);
    }

    private static boolean isAlreadyTrackedException(Exception e) {
        if (!(e instanceof IllegalStateException)) {
            return false;
        }
        String msg = e.getMessage();
        return msg != null && msg.contains("already tracked");
    }

    //检查实体在客户端关键容器中的存在情况
    public static Map<String, Boolean> checkEntityInClientContainers(ClientLevel clientLevel, UUID entityUUID) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (clientLevel == null || entityUUID == null) {
            return result;
        }

        Entity entity = null;
        try {
            entity = EcaEntitySelector.getEntity(clientLevel, entityUUID);
        } catch (Exception ignored) {
        }
        result.put("ClientLevel.getEntity(uuid)", entity != null);

        try {
            result.put("ClientEntityStorage.entityLookup.byUuid", clientLevel.entityStorage.entityStorage.byUuid.containsKey(entityUUID));
        } catch (Exception e) {
            result.put("ClientEntityStorage.entityLookup.byUuid", false);
        }

        try {
            boolean byId = entity != null && clientLevel.entityStorage.entityStorage.byId.containsKey(entity.getId());
            result.put("ClientEntityStorage.entityLookup.byId", byId);
        } catch (Exception e) {
            result.put("ClientEntityStorage.entityLookup.byId", false);
        }

        try {
            result.put("ClientLevel.tickingEntities", entity != null && clientLevel.tickingEntities.contains(entity));
        } catch (Exception e) {
            result.put("ClientLevel.tickingEntities", false);
        }

        try {
            boolean inSection = false;
            if (entity != null) {
                Entity targetEntity = entity;
                long sectionKey = SectionPos.asLong(entity.blockPosition());
                EntitySection<Entity> section = clientLevel.entityStorage.sectionStorage.sections.get(sectionKey);
                inSection = section != null && section.getEntities().anyMatch(e -> e == targetEntity);
            }
            result.put("ClientEntityStorage.sectionStorage", inSection);
        } catch (Exception e) {
            result.put("ClientEntityStorage.sectionStorage", false);
        }

        try {
            result.put("ClientEntity.levelCallback", entity != null && entity.levelCallback != EntityInLevelCallback.NULL);
        } catch (Exception e) {
            result.put("ClientEntity.levelCallback", false);
        }

        try {
            boolean inClientPlayers = !(entity instanceof Player) || clientLevel.players.contains(entity);
            result.put("ClientLevel.players", inClientPlayers);
        } catch (Exception e) {
            result.put("ClientLevel.players", false);
        }

        return result;
    }

    /**
     * Mark an entity as currently changing dimensions.
     * Should be called when dimension change starts (removalReason = CHANGED_DIMENSION).
     *
     * @param entity the entity starting dimension change
     */
    public static void markDimensionChanging(Entity entity) {
        if (entity == null) {
            return;
        }

        UUID uuid = entity.getUUID();
        DIMENSION_CHANGING_ENTITIES.add(uuid);
    }

    /**
     * Unmark an entity from dimension changing state.
     * Should be called when dimension change completes.
     *
     * @param entity the entity that finished dimension change
     */
    public static void unmarkDimensionChanging(Entity entity) {
        if (entity == null) {
            return;
        }

        UUID uuid = entity.getUUID();
        DIMENSION_CHANGING_ENTITIES.remove(uuid);
    }

    public static void clearRemovalReasonIfProtected(Entity entity) {
        if (entity == null) {
            return;
        }

        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason == null) {
            return;
        }
        if (isChangingDimension(entity)) {
            return;
        }
        if (!reason.shouldSave()) {
            entity.removalReason = null;
        }
    }


    //获取实体真实生命值
    public static float getHealth(LivingEntity entity) {
        if (entity == null) return 0.0f;
        try {
            SynchedEntityData.DataItem dataItem = getDataItem(entity.getEntityData(), LivingEntity.DATA_HEALTH_ID.getId());
            if (dataItem == null) {
                return entity.getHealth();
            }
            return (Float) dataItem.value;
        } catch (Exception e) {
            return entity.getHealth();
        }
    }

    // ECA 注册的 accessor ID 缓存，运行时填充
    private static final Set<Integer> ECA_DATA_IDS = ConcurrentHashMap.newKeySet();
    private static volatile boolean ecaDataIdsInitialized = false;

    private static void ensureEcaDataIds() {
        if (ecaDataIdsInitialized) return;
        ecaDataIdsInitialized = true;
        if (HEALTH_LOCK_VALUE != null) ECA_DATA_IDS.add(HEALTH_LOCK_VALUE.getId());
        if (HEAL_BAN_VALUE != null) ECA_DATA_IDS.add(HEAL_BAN_VALUE.getId());
        if (INVULNERABLE != null) ECA_DATA_IDS.add(INVULNERABLE.getId());
        if (MAX_HEALTH_LOCK_VALUE != null) ECA_DATA_IDS.add(MAX_HEALTH_LOCK_VALUE.getId());
    }

    // 清除外部 mod 注入的 Float 类型实体数据（ID > vanillaMaxId ）
    @SuppressWarnings("rawtypes")
    public static void clearForeignEntityData(LivingEntity entity) {
        if (entity == null) return;
        ensureEcaDataIds();
        try {
            SynchedEntityData entityData = entity.getEntityData();
            Int2ObjectMap<?> itemsById = (Int2ObjectMap<?>) entityData.itemsById;
            if (itemsById == null) return;
            for (Int2ObjectMap.Entry<?> entry : itemsById.int2ObjectEntrySet()) {
                int id = entry.getIntKey();
                // 跳过 vanilla 范围
                if (id <= 15) continue;
                // 跳过 ECA 自己注册的
                if (ECA_DATA_IDS.contains(id)) continue;
                SynchedEntityData.DataItem dataItem = (SynchedEntityData.DataItem) entry.getValue();
                if (dataItem == null) continue;
                // 只清除 Float 类型
                if (dataItem.value instanceof Float) {
                    dataItem.value = 0.0f;
                    dataItem.dirty = true;
                }
            }
            entityData.isDirty = true;
        } catch (Throwable t) {
            EcaLogger.info("[EntityUtil] clearForeignEntityData failed: {}", t.getMessage());
        }
    }

    //获取DataItem（返回 raw type 以便直接赋值 value 字段）
    @SuppressWarnings("rawtypes")
    private static SynchedEntityData.DataItem getDataItem(SynchedEntityData entityData, int id) {
        try {
            Int2ObjectMap<?> itemsById = (Int2ObjectMap<?>) entityData.itemsById;
            return (SynchedEntityData.DataItem) itemsById.get(id);
        } catch (Exception e) {
            return null;
        }
    }

    //设置实体生命值（Phase 1 + 2 + 3 顺序全部跑一遍，最后 verify）
    public static boolean setHealth(LivingEntity entity, float expectedHealth) {
        if (entity == null) return false;
        try {
            float beforeHealth = getHealth(entity);

            //Phase 1：写 vanilla DATA_HEALTH_ID
            setBasicHealth(entity, expectedHealth);

            //玩家只执行 Phase 1
            if (entity instanceof Player) {
                syncHealthToClients(entity, expectedHealth, beforeHealth);
                return true;
            }

            //Phase 2：尝试调用实体自带的 setHealth/setHp/modifyHealth 等方法
            setHealthViaPhase2(entity, expectedHealth);

            //Phase 3：ASM dataflow 分析 + 写入真实血量存储
            setHealthViaPhase3(entity, expectedHealth);

            //只在最后 verify 一次
            boolean ok = verifyHealthChange(entity, expectedHealth);
            syncHealthToClients(entity, expectedHealth, beforeHealth);
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    //从同步包调用，在客户端执行改血（不再发包）
    public static void setHealthFromSync(LivingEntity entity, float expectedHealth) {
        IS_FROM_SYNC.set(true);
        try {
            setHealth(entity, expectedHealth);
        } finally {
            IS_FROM_SYNC.set(false);
        }
    }

    //服务端改血后同步到客户端
    private static void syncHealthToClients(LivingEntity entity, float expectedHealth, float beforeHealth) {
        if (IS_FROM_SYNC.get()) return;
        if (entity.level() == null || entity.level().isClientSide) return;
        if (Math.abs(expectedHealth - beforeHealth) <= 0.001f) return;

        try {
            NetworkHandler.sendToTrackingClients(
                new EntityHealthSyncPacket(entity.getId(), expectedHealth),
                entity
            );
        } catch (Exception ignored) {}
    }

    //验证血量修改是否成功（必须用实体自身的 getHealth()，不能读 DATA_HEALTH_ID）
    private static boolean verifyHealthChange(LivingEntity entity, float expectedHealth) {
        try {
            float actualHealth = entity.getHealth();
            return Math.abs(actualHealth - expectedHealth) <= 10.0f;
        } catch (Exception e) {
            return false;
        }
    }

    //阶段1：设置原版血量
    public static void setBasicHealth(LivingEntity entity, float expectedHealth) {
        try {
            SynchedEntityData entityData = entity.getEntityData();
            SynchedEntityData.DataItem dataItem = getDataItem(entityData, LivingEntity.DATA_HEALTH_ID.getId());
            if (dataItem == null) return;

            dataItem.value = expectedHealth;
            entity.onSyncedDataUpdated(LivingEntity.DATA_HEALTH_ID);
            dataItem.dirty = true;
            entityData.isDirty = true;
        } catch (Exception ignored) {}
    }

    //Phase 2：按关键词搜索实体自带的 setHealth/setHp/modifyHealth 等方法并调用
    //匹配条件：方法名以 set/modify/update 开头 + 包含 health/hp + 不含 max + 单个数字参数
    private static void setHealthViaPhase2(LivingEntity entity, float expectedHealth) {
        if (IS_IN_ENTITY_HEALTH_SETTER.get()) return;
        try {
            for (Class<?> clazz = entity.getClass(); clazz != null && clazz != LivingEntity.class; clazz = clazz.getSuperclass()) {
                for (Method m : clazz.getDeclaredMethods()) {
                    if (!isHealthSetterMethod(m)) continue;
                    m.setAccessible(true);
                    IS_IN_ENTITY_HEALTH_SETTER.set(true);
                    try {
                        Class<?> pt = m.getParameterTypes()[0];
                        if (pt == float.class) m.invoke(entity, expectedHealth);
                        else if (pt == double.class) m.invoke(entity, (double) expectedHealth);
                        else if (pt == int.class) m.invoke(entity, (int) expectedHealth);
                        else if (pt == long.class) m.invoke(entity, (long) expectedHealth);
                        return;  //第一个匹配就停
                    } finally {
                        IS_IN_ENTITY_HEALTH_SETTER.set(false);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static final Set<String> HEALTH_SETTER_VERBS = Set.of("set", "modify", "update");
    private static final Set<String> HEALTH_SETTER_NOUNS = Set.of("health", "hp");

    //判断方法是否符合"动词 + 名词 + 非 max + 单数字参"
    private static boolean isHealthSetterMethod(Method m) {
        if (m.getParameterCount() != 1) return false;
        Class<?> pt = m.getParameterTypes()[0];
        if (pt != float.class && pt != double.class && pt != int.class && pt != long.class) return false;
        String name = m.getName().toLowerCase();
        boolean hasVerb = false;
        for (String v : HEALTH_SETTER_VERBS) if (name.startsWith(v)) { hasVerb = true; break; }
        if (!hasVerb) return false;
        boolean hasNoun = false;
        for (String n : HEALTH_SETTER_NOUNS) if (name.contains(n)) { hasNoun = true; break; }
        return hasNoun && !name.contains("max");
    }

    //Phase 3：ASM dataflow 分析器追踪真实血量存储并写入
    private static void setHealthViaPhase3(LivingEntity entity, float expectedHealth) {
        try {
            HealthAnalyzerManager.writeAll(entity, expectedHealth);
        } catch (Exception ignored) {}
    }

    // ==================== 实体死亡模块 ====================

    //设置实体死亡状态
    public static void kill(LivingEntity entity, DamageSource damageSource) {
        if (entity == null || damageSource == null) return;

        try {
            //设置血量为0
            setHealth(entity, 0.0f);
            //设置伤害来源
            Entity sourceEntity = damageSource.getEntity();
            if (sourceEntity != null) {
                //设置lastHurtByMob（用于反击目标）
                if (sourceEntity instanceof LivingEntity livingSource) {
                    entity.setLastHurtByMob(livingSource);
                }

                //仅当攻击者是玩家时设置lastHurtByPlayer（用于掉落物和经验）
                if (sourceEntity instanceof Player player) {
                    entity.setLastHurtByPlayer(player);
                }
            }

            //调用原版die
            entity.die(damageSource);
            entity.setPose(Pose.DYING);
            entity.dead = true;
            entity.deathTime = 0;
            //触发击杀成就
            triggerKillAdvancement(entity, damageSource);
            entity.dropAllDeathLoot(damageSource);
            if (entity.isAlive() || ! entity.isRemoved()){
                //保底清除实体
                remove(entity, Entity.RemovalReason.KILLED);
            }

        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to set entity dead: {}", e.getMessage());
        }
    }

    //复活实体（清除死亡状态）
    public static void revive(LivingEntity entity) {
        if (entity == null) return;
        // 跳过正在切换维度的实体，防止旧实例被错误复活到原维度
        if (isChangingDimension(entity)) {
            return;
        }
        try {
            entity.revive();
            setBasicHealth(entity, entity.getMaxHealth());
            entity.dead = false;
            entity.deathTime = 0;
            // 恢复站立姿势（清除DYING姿势）
            entity.setPose(Pose.STANDING);
            // 安全清除移除原因（保护维度切换和区块卸载）
            clearRemovalReasonIfProtected(entity);
            // 同步修复关键容器
            reviveAllContainers(entity);
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to revive entity: {}", e.getMessage());
        }
    }

    //触发击杀成就
    private static void triggerKillAdvancement(LivingEntity entity, DamageSource damageSource) {
        try {
            ServerPlayer killerPlayer = null;

            //直接是玩家击杀
            if (damageSource.getEntity() instanceof ServerPlayer player) {
                killerPlayer = player;
            }

            //触发成就
            if (killerPlayer != null) {
                CriteriaTriggers.PLAYER_KILLED_ENTITY.trigger(killerPlayer, entity, damageSource);
            }
        } catch (Exception ignored) {}
    }

    // ==================== 实体清除模块 ====================

    //完整的实体清除方法
    public static void remove(Entity entity, Entity.RemovalReason reason) {
        if (entity == null || entity.level() == null) return;
        if (entity.level().isClientSide) return;
        ServerLevel serverLevel = (ServerLevel) entity.level();

        try {
            List<UUID> bossEventUUIDs = collectAllBossEventUUIDsForRemoval(entity);
            cleanupAI(entity);
            cleanupBossBar(entity);
            entity.removalReason = reason;
            entity.stopRiding();
            entity.getPassengers().forEach(Entity::stopRiding);
            entity.invalidateCaps();
            teleport(entity, 102400, -102400, 102400);
            broadcastRemovalToSeenBy(serverLevel, entity, bossEventUUIDs);
            removeFromServerContainers(serverLevel, entity);

        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Entity removal failed: {}", e.getMessage());
        }
    }

    //向所有曾经追踪此实体的客户端广播移除（先传到虚空保底，再发原版包触发渲染清理，最后发自定义包清理 boss bar）
    private static void broadcastRemovalToSeenBy(ServerLevel serverLevel, Entity entity, List<UUID> bossEventUUIDs) {
        ChunkMap.TrackedEntity trackedEntity = serverLevel.chunkSource.chunkMap.entityMap.get(entity.getId());
        if (trackedEntity == null) {
            NetworkHandler.sendToTrackingClients(new ClientRemovePacket(entity.getId(), bossEventUUIDs), entity);
            return;
        }

        Set<ServerPlayerConnection> seenBy = new HashSet<>(trackedEntity.seenBy);
        if (seenBy.isEmpty()) return;

        ClientboundRemoveEntitiesPacket vanillaPacket = new ClientboundRemoveEntitiesPacket(entity.getId());
        ClientRemovePacket customPacket = new ClientRemovePacket(entity.getId(), bossEventUUIDs);
        for (ServerPlayerConnection connection : seenBy) {
            ServerPlayer player = connection.getPlayer();
            player.connection.send(vanillaPacket);
            NetworkHandler.sendToPlayer(customPacket, player);
        }
    }

    public static void prepareForMemoryRemove(Entity entity) {
        if (entity == null) return;
        if (!(entity instanceof LivingEntity livingEntity)) {
            return;
        }

        if (INVULNERABLE != null) {
            livingEntity.getEntityData().set(INVULNERABLE, false);
        } else {
            livingEntity.getPersistentData().putBoolean("ecaInvulnerable", false);
        }
        InvulnerableEntityManager.removeInvulnerable(livingEntity);
        HealthLockManager.removeLock(livingEntity);
        HealthLockManager.removeHealBan(livingEntity);
        HealthLockManager.removeMaxHealthLock(livingEntity);
    }

    public static List<UUID> collectAllBossEventUUIDsForRemoval(Entity entity) {
        List<UUID> bossEventUUIDs = collectBossEventUUIDs(entity);
        if (entity != null && !entity.level().isClientSide && entity instanceof LivingEntity living) {
            bossEventUUIDs.addAll(EntityExtensionManager.collectCustomBossEventUUIDs(living));
        }
        return bossEventUUIDs;
    }

    //AI清理
    public static void cleanupAI(Entity entity) {
        if (entity instanceof Mob mob) {
            mob.goalSelector.removeAllGoals(goal -> true);
            mob.targetSelector.removeAllGoals(goal -> true);
            mob.setTarget(null);
            mob.getNavigation().stop();
        }
    }

    //Boss血条清理
    public static void cleanupBossBar(Entity entity) {
        if (entity == null) return;

        if (entity instanceof LivingEntity livingEntity) {
            EntityExtensionManager.cleanupBossBar(livingEntity);
        }

        List<ServerBossEvent> bossEvents = new ArrayList<>();

        for (Class<?> clazz = entity.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                try {
                    Object fieldValue = field.get(entity);
                    if (fieldValue instanceof ServerBossEvent serverBossEvent) {
                        bossEvents.add(serverBossEvent);
                    }
                } catch (IllegalAccessException ignored) {}
            }
        }

        for (ServerBossEvent bossEvent : bossEvents) {
            bossEvent.removeAllPlayers();
            bossEvent.setVisible(false);
        }
    }

    //收集实体的所有 ServerBossEvent UUID（用于客户端精确清理）
    public static List<UUID> collectBossEventUUIDs(Entity entity) {
        List<UUID> uuids = new ArrayList<>();
        if (entity == null) return uuids;

        for (Class<?> clazz = entity.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                try {
                    Object value = field.get(entity);
                    if (value instanceof ServerBossEvent serverBossEvent) {
                        uuids.add(serverBossEvent.getId());
                    }
                } catch (IllegalAccessException ignored) {}
            }
        }
        return uuids;
    }

    //服务端底层容器清除（顺序对齐原版 PersistentEntitySectionManager.Callback.onRemove）
    public static void removeFromServerContainers(ServerLevel serverLevel, Entity entity) {
        try {
            PersistentEntitySectionManager<Entity> entityManager = serverLevel.entityManager;

            removeFromLoadingInbox(entityManager, entity);
            removeFromSectionStorage(entityManager.sectionStorage, entity);
            removeFromEntityTickList(serverLevel.entityTickList, entity);
            serverLevel.chunkSource.chunkMap.entityMap.remove(entity.getId());
            if (entity instanceof ServerPlayer) serverLevel.players.remove(entity);
            if (entity instanceof Mob) serverLevel.navigatingMobs.remove(entity);
            if (entity.isMultipartEntity()) {
                for (PartEntity<?> part : entity.getParts()) {
                    serverLevel.dragonParts.remove(part.getId());
                }
            }
            entity.updateDynamicGameEventListener(DynamicGameEventListener::remove);
            entity.onRemovedFromWorld();
            removeFromEntityLookup(entityManager.visibleEntityStorage, entity);
            entityManager.callbacks.onDestroyed(entity);
            entityManager.knownUuids.remove(entity.getUUID());          // e. knownUuids
            entity.levelCallback = EntityInLevelCallback.NULL;           // f. levelCallback = NULL
            removeSectionIfEmpty(entityManager.sectionStorage, entity);  // g. removeSectionIfEmpty

        } catch (Exception e) {
            EcaLogger.error("[EntityUtil] Failed to remove from server containers, entityId={}, type={}, uuid={}",
                    entity.getId(), entity.getType(), entity.getUUID());
            EcaLogger.error("[EntityUtil] Server container removal stacktrace", e);
        }
    }

    //从 loadingInbox 清理正在加载的实体
    private static void removeFromLoadingInbox(PersistentEntitySectionManager<Entity> entityManager, Entity entity) {
        for (ChunkEntities<Entity> chunkEntities : entityManager.loadingInbox) {
            chunkEntities.entities.remove(entity);
        }
    }

    //从 EntitySectionStorage 遍历所有 section 移除实体（直接操作 ClassInstanceMultiMap 底层，绕过可被 Mixin 的 MC 层 API）
    private static void removeFromSectionStorage(EntitySectionStorage<Entity> sectionStorage, Entity entity) {
        for (EntitySection<Entity> section : sectionStorage.sections.values()) {
            if (section != null) {
                removeFromClassInstanceMultiMap(section.storage, entity);
            }
        }
    }

    // 直接操作 ClassInstanceMultiMap 的 allInstances(byClass) 底层 List，绕过 ClassInstanceMultiMap.remove()
    private static void removeFromClassInstanceMultiMap(ClassInstanceMultiMap<Entity> storage, Entity entity) {
        for (Map.Entry<Class<?>, List<Entity>> entry : storage.byClass.entrySet()) {
            if (entry.getKey().isInstance(entity)) {
                entry.getValue().remove(entity);
            }
        }
    }

    // 直接操作 EntityTickList.active，仿照原版 ensureActiveIsNotIterated 避免迭代器损坏
    public static void removeFromEntityTickList(EntityTickList entityTickList, Entity entity) {
        Int2ObjectMap<Entity> active = entityTickList.active;
        if (entityTickList.iterated == active) {
            entityTickList.passive.clear();
            for (Int2ObjectMap.Entry<Entity> entry : Int2ObjectMaps.fastIterable(active)) {
                entityTickList.passive.put(entry.getIntKey(), entry.getValue());
            }
            entityTickList.active = entityTickList.passive;
            entityTickList.passive = active;
        }
        entityTickList.active.remove(entity.getId());
    }

    // 直接操作 EntityLookup 的 byId 和 byUuid，绕过 EntityLookup.remove()
    public static void removeFromEntityLookup(EntityLookup<Entity> entityLookup, Entity entity) {
        entityLookup.byId.remove(entity.getId());
        entityLookup.byUuid.remove(entity.getUUID());
    }

    //清理空的 EntitySection
    private static void removeSectionIfEmpty(EntitySectionStorage<Entity> sectionStorage, Entity entity) {
        long sectionKey = SectionPos.asLong(entity.blockPosition());
        EntitySection<Entity> section = sectionStorage.sections.get(sectionKey);
        if (section != null && section.isEmpty()) {
            sectionStorage.sections.remove(sectionKey);
        }
    }

    //客户端底层容器清除
    public static void removeFromClientContainers(ClientLevel clientLevel, Entity entity) {
        try {
            clientLevel.players.remove(entity);

            if (entity.isMultipartEntity()) {
                for (PartEntity<?> part : entity.getParts()) {
                    clientLevel.partEntities.remove(part.getId());
                }
            }

            TransientEntitySectionManager<Entity> entityStorage = clientLevel.entityStorage;
            removeFromSectionStorage(entityStorage.sectionStorage, entity);              //1. ClassInstanceMultiMap 直接操作
            removeSectionIfEmpty(entityStorage.sectionStorage, entity);
            removeFromEntityTickList(clientLevel.tickingEntities, entity);               //2. EntityTickList.active 直接操作
            removeFromEntityLookup(entityStorage.entityStorage, entity);                 //3. EntityLookup byId/byUuid 直接操作

        } catch (Exception e) {
            EcaLogger.error("[EntityUtil] Failed to remove from client containers, entityId={}, type={}, uuid={}",
                    entity.getId(), entity.getType(), entity.getUUID());
            EcaLogger.error("[EntityUtil] Client container removal stacktrace", e);
        }
    }

    // ==================== 传送模块 ====================

    /**
     * Teleport an entity to the specified location using VarHandle direct access.
     * This method directly modifies the entity's position fields and updates the bounding box.
     * @param entity the entity to teleport
     * @param x the target x coordinate
     * @param y the target y coordinate
     * @param z the target z coordinate
     * @return true if teleportation succeeded, false otherwise
     */
    public static boolean teleport(Entity entity, double x, double y, double z) {
        if (entity == null) return false;

        try {
            Vec3 newPosition = new Vec3(x, y, z);

            //修改核心位置字段
            entity.position = newPosition;
            entity.xOld = x;
            entity.yOld = y;
            entity.zOld = z;

            //更新碰撞箱
            AABB newBoundingBox = entity.getDimensions(entity.getPose()).makeBoundingBox(x, y, z);
            entity.bb = newBoundingBox;

            //同步到客户端
            if (!entity.level().isClientSide && entity.level() instanceof ServerLevel serverLevel) {
                syncTeleportToClient(entity, serverLevel);
            }

            return true;
        } catch (Exception e) {
            EcaLogger.error("Teleport failed for {}: {}", entity.getType().getDescriptionId(), e.getMessage());
            return false;
        }
    }

    /**
     * Sync entity teleportation to clients.
     * @param entity the entity that was teleported
     * @param serverLevel the server level
     */
    private static void syncTeleportToClient(Entity entity, ServerLevel serverLevel) {
        try {
            ClientboundTeleportEntityPacket packet = new ClientboundTeleportEntityPacket(entity);

            //玩家特殊处理
            if (entity instanceof ServerPlayer player) {
                player.connection.teleport(
                        entity.getX(),
                        entity.getY(),
                        entity.getZ(),
                        entity.getYRot(),
                        entity.getXRot()
                );
                return;
            }

            //按 seenBy 发包，不依赖实体当前位置（避免传送到远处后 broadcast 覆盖范围为空）
            ChunkMap.TrackedEntity trackedEntity = serverLevel.chunkSource.chunkMap.entityMap.get(entity.getId());
            if (trackedEntity != null) {
                for (ServerPlayerConnection connection : trackedEntity.seenBy) {
                    connection.getPlayer().connection.send(packet);
                }
            } else {
                serverLevel.getChunkSource().broadcast(entity, packet);
            }
        } catch (Exception e) {
            EcaLogger.error("Failed to sync teleport to clients: {}", e.getMessage());
        }
    }

    // ==================== 最大生命值模块 ====================

    //设置实体最大生命值（反算baseValue，使 getMaxHealth() 精确返回目标值）
    public static boolean setMaxHealth(LivingEntity entity, float targetMaxHealth) {
        if (entity == null) return false;

        try {
            AttributeInstance instance = entity.getAttribute(Attributes.MAX_HEALTH);
            if (instance == null) return false;

            double newBaseValue = reverseCalculateBaseValue(instance, targetMaxHealth);
            instance.setBaseValue(newBaseValue);

            //验证结果
            double actual = instance.getValue();

            //当前血量超过新上限时，主动 clamp（原版不会自动处理）
            if (entity.getHealth() > actual) {
                entity.setHealth((float) actual);
            }

            return Math.abs(actual - targetMaxHealth) < 0.01;
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to set max health: {}", e.getMessage());
            return false;
        }
    }

    //反算 baseValue：根据当前 modifier 计算需要什么 baseValue 才能使 getValue() == target
    private static double reverseCalculateBaseValue(AttributeInstance instance, double target) {
        //收集三层 modifier 的叠加系数
        double additionSum = 0.0;
        for (AttributeModifier mod : instance.getModifiers(AttributeModifier.Operation.ADDITION)) {
            additionSum += mod.getAmount();
        }

        double multiplyBaseSum = 0.0;
        for (AttributeModifier mod : instance.getModifiers(AttributeModifier.Operation.MULTIPLY_BASE)) {
            multiplyBaseSum += mod.getAmount();
        }

        double multiplyTotalProduct = 1.0;
        for (AttributeModifier mod : instance.getModifiers(AttributeModifier.Operation.MULTIPLY_TOTAL)) {
            multiplyTotalProduct *= (1.0 + mod.getAmount());
        }

        // 原版公式: result = (base + additionSum) * (1 + multiplyBaseSum) * multiplyTotalProduct
        // 反算: base = target / [multiplyTotalProduct * (1 + multiplyBaseSum)] - additionSum
        double divisor = multiplyTotalProduct * (1.0 + multiplyBaseSum);
        if (Math.abs(divisor) < 1e-10) {
            // modifier 乘积为0，无法反算，直接清除所有 modifier 并设 baseValue
            instance.removeModifiers();
            return target;
        }

        return (target / divisor) - additionSum;
    }

    // ==================== 关键词名单管理 API ====================

    //添加生命值白名单关键词
    public static void addHealthWhitelistKeyword(String keyword) {
        if (keyword != null && !keyword.isEmpty()) {
            HEALTH_WHITELIST_KEYWORDS.add(keyword.toLowerCase());
        }
    }

    //移除生命值白名单关键词
    public static void removeHealthWhitelistKeyword(String keyword) {
        if (keyword != null) {
            HEALTH_WHITELIST_KEYWORDS.remove(keyword.toLowerCase());
        }
    }

    //获取生命值白名单关键词（只读副本）
    public static Set<String> getHealthWhitelistKeywords() {
        return new HashSet<>(HEALTH_WHITELIST_KEYWORDS);
    }

    //添加生命值黑名单关键词
    public static void addHealthBlacklistKeyword(String keyword) {
        if (keyword != null && !keyword.isEmpty()) {
            HEALTH_BLACKLIST_KEYWORDS.add(keyword.toLowerCase());
        }
    }

    //移除生命值黑名单关键词
    public static void removeHealthBlacklistKeyword(String keyword) {
        if (keyword != null) {
            HEALTH_BLACKLIST_KEYWORDS.remove(keyword.toLowerCase());
        }
    }

    //获取生命值黑名单关键词（只读副本）
    public static Set<String> getHealthBlacklistKeywords() {
        return new HashSet<>(HEALTH_BLACKLIST_KEYWORDS);
    }
}
