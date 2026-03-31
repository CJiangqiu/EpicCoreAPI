package net.eca.util.reflect;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.eca.network.ClientRemovePacket;
import net.eca.network.NetworkHandler;
import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 内存操作工具类
 * 通过LWJGL内部的Unsafe通道进行内存操作，绕过字节码级别的拦截
 * 原理：
 * 1. LWJGL是Minecraft核心依赖，其内部类不会被第三方修改
 * 2. 通过反射获取LWJGL内部的Unsafe实例
 * 3. 通过Method.invoke()调用Unsafe方法，字节码中不会出现直接的Unsafe调用
 */
@SuppressWarnings("unchecked")
public class UnsafeUtil {

    // ==================== ChunkMap tick 状态（供 Mixin 调用） ====================

    private static volatile boolean chunkMapTicking = false;
    private static final List<Runnable> pendingChunkMapOps = new ArrayList<>();

    public static void onChunkMapTickStart() {
        chunkMapTicking = true;
    }

    public static void onChunkMapTickEnd() {
        chunkMapTicking = false;
        if (!pendingChunkMapOps.isEmpty()) {
            for (Runnable op : pendingChunkMapOps) {
                try {
                    op.run();
                } catch (Exception ignored) {
                }
            }
            pendingChunkMapOps.clear();
        }
    }

    // ==================== LWJGL Unsafe 通道 ====================

    private static Object LWJGL_UNSAFE;
    private static Method PUT_OBJECT_METHOD;
    private static Method PUT_INT_METHOD;
    private static Method PUT_LONG_METHOD;
    private static Method PUT_BOOLEAN_METHOD;
    private static Method GET_OBJECT_METHOD;
    private static Method OBJECT_FIELD_OFFSET_METHOD;

    private static boolean initialized = false;
    private static boolean available = false;

    // ==================== Field offsets ====================

    // ServerLevel
    private static long SERVER_LEVEL_PLAYERS_OFFSET = -1;
    private static long SERVER_LEVEL_CHUNK_SOURCE_OFFSET = -1;
    private static long SERVER_LEVEL_ENTITY_TICK_LIST_OFFSET = -1;
    private static long SERVER_LEVEL_ENTITY_MANAGER_OFFSET = -1;
    private static long SERVER_LEVEL_NAVIGATING_MOBS_OFFSET = -1;

    // EntityTickList
    private static long ENTITY_TICK_LIST_ACTIVE_OFFSET = -1;
    private static long ENTITY_TICK_LIST_PASSIVE_OFFSET = -1;
    private static long ENTITY_TICK_LIST_ITERATED_OFFSET = -1;

    // ServerChunkCache / ChunkMap
    private static long SERVER_CHUNK_CACHE_CHUNK_MAP_OFFSET = -1;
    private static long CHUNK_MAP_ENTITY_MAP_OFFSET = -1;

    // PersistentEntitySectionManager
    private static long PERSISTENT_MANAGER_VISIBLE_STORAGE_OFFSET = -1;
    private static long PERSISTENT_MANAGER_KNOWN_UUIDS_OFFSET = -1;
    private static long PERSISTENT_MANAGER_SECTION_STORAGE_OFFSET = -1;
    private static long PERSISTENT_MANAGER_CALLBACKS_OFFSET = -1;
    private static long PERSISTENT_MANAGER_LOADING_INBOX_OFFSET = -1;

    // EntityLookup
    private static long ENTITY_LOOKUP_BY_UUID_OFFSET = -1;
    private static long ENTITY_LOOKUP_BY_ID_OFFSET = -1;

    // EntitySectionStorage / EntitySection
    private static long ENTITY_SECTION_STORAGE_SECTIONS_OFFSET = -1;
    private static long ENTITY_SECTION_STORAGE_OFFSET = -1;

    // ClassInstanceMultiMap
    private static long CLASS_INSTANCE_MULTI_MAP_BY_CLASS_OFFSET = -1;

    // ==================== 初始化 ====================

    static {
        init();
    }

    private static void init() {
        if (initialized) return;
        initialized = true;

        try {
            initLwjglUnsafe();
            initFieldOffsets();
            available = true;
        } catch (Exception e) {
            available = false;
            EcaLogger.info("[UnsafeUtil] Failed to initialize LWJGL Unsafe channel: {}", e.getMessage());
        }
    }

    private static void initLwjglUnsafe() throws Exception {
        // 从 LWJGL 的 MemoryUtil 获取内部 Unsafe 实例
        Class<?> memoryUtilClass = Class.forName("org.lwjgl.system.MemoryUtil");
        Field unsafeField = memoryUtilClass.getDeclaredField("UNSAFE");
        unsafeField.setAccessible(true);
        LWJGL_UNSAFE = unsafeField.get(null);

        if (LWJGL_UNSAFE == null) {
            throw new RuntimeException("LWJGL UNSAFE is null");
        }

        Class<?> unsafeClass = LWJGL_UNSAFE.getClass();
        PUT_OBJECT_METHOD = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
        PUT_INT_METHOD = unsafeClass.getMethod("putInt", Object.class, long.class, int.class);
        PUT_LONG_METHOD = unsafeClass.getMethod("putLong", Object.class, long.class, long.class);
        PUT_BOOLEAN_METHOD = unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class);
        GET_OBJECT_METHOD = unsafeClass.getMethod("getObject", Object.class, long.class);
        OBJECT_FIELD_OFFSET_METHOD = unsafeClass.getMethod("objectFieldOffset", Field.class);
    }

    private static void initFieldOffsets() throws Exception {
        // ServerLevel 相关字段
        SERVER_LEVEL_PLAYERS_OFFSET = getFieldOffset(ServerLevel.class,
            ObfuscationMapping.getFieldMapping("ServerLevel.players"));
        SERVER_LEVEL_CHUNK_SOURCE_OFFSET = getFieldOffset(ServerLevel.class,
            ObfuscationMapping.getFieldMapping("ServerLevel.chunkSource"));
        SERVER_LEVEL_ENTITY_TICK_LIST_OFFSET = getFieldOffset(ServerLevel.class,
            ObfuscationMapping.getFieldMapping("ServerLevel.entityTickList"));
        SERVER_LEVEL_ENTITY_MANAGER_OFFSET = getFieldOffset(ServerLevel.class,
            ObfuscationMapping.getFieldMapping("ServerLevel.entityManager"));
        SERVER_LEVEL_NAVIGATING_MOBS_OFFSET = getFieldOffset(ServerLevel.class,
            ObfuscationMapping.getFieldMapping("ServerLevel.navigatingMobs"));

        // EntityTickList 相关字段
        ENTITY_TICK_LIST_ACTIVE_OFFSET = getFieldOffset(EntityTickList.class,
            ObfuscationMapping.getFieldMapping("EntityTickList.active"));
        ENTITY_TICK_LIST_PASSIVE_OFFSET = getFieldOffset(EntityTickList.class,
            ObfuscationMapping.getFieldMapping("EntityTickList.passive"));
        ENTITY_TICK_LIST_ITERATED_OFFSET = getFieldOffset(EntityTickList.class,
            ObfuscationMapping.getFieldMapping("EntityTickList.iterated"));

        // ServerChunkCache / ChunkMap 相关字段
        SERVER_CHUNK_CACHE_CHUNK_MAP_OFFSET = getFieldOffset(ServerChunkCache.class,
            ObfuscationMapping.getFieldMapping("ServerChunkCache.chunkMap"));
        CHUNK_MAP_ENTITY_MAP_OFFSET = getFieldOffset(ChunkMap.class,
            ObfuscationMapping.getFieldMapping("ChunkMap.entityMap"));

        // PersistentEntitySectionManager 相关字段
        PERSISTENT_MANAGER_VISIBLE_STORAGE_OFFSET = getFieldOffset(PersistentEntitySectionManager.class,
            ObfuscationMapping.getFieldMapping("PersistentEntitySectionManager.visibleEntityStorage"));
        PERSISTENT_MANAGER_KNOWN_UUIDS_OFFSET = getFieldOffset(PersistentEntitySectionManager.class,
            ObfuscationMapping.getFieldMapping("PersistentEntitySectionManager.knownUuids"));
        PERSISTENT_MANAGER_SECTION_STORAGE_OFFSET = getFieldOffset(PersistentEntitySectionManager.class,
            ObfuscationMapping.getFieldMapping("PersistentEntitySectionManager.sectionStorage"));
        PERSISTENT_MANAGER_CALLBACKS_OFFSET = getFieldOffset(PersistentEntitySectionManager.class,
            ObfuscationMapping.getFieldMapping("PersistentEntitySectionManager.callbacks"));
        PERSISTENT_MANAGER_LOADING_INBOX_OFFSET = getFieldOffset(PersistentEntitySectionManager.class,
            ObfuscationMapping.getFieldMapping("PersistentEntitySectionManager.loadingInbox"));

        // EntityLookup 相关字段
        ENTITY_LOOKUP_BY_UUID_OFFSET = getFieldOffset(EntityLookup.class,
            ObfuscationMapping.getFieldMapping("EntityLookup.byUuid"));
        ENTITY_LOOKUP_BY_ID_OFFSET = getFieldOffset(EntityLookup.class,
            ObfuscationMapping.getFieldMapping("EntityLookup.byId"));

        // EntitySectionStorage / EntitySection 相关字段
        ENTITY_SECTION_STORAGE_SECTIONS_OFFSET = getFieldOffset(EntitySectionStorage.class,
            ObfuscationMapping.getFieldMapping("EntitySectionStorage.sections"));
        ENTITY_SECTION_STORAGE_OFFSET = getFieldOffset(EntitySection.class,
            ObfuscationMapping.getFieldMapping("EntitySection.storage"));

        // ClassInstanceMultiMap 相关字段
        CLASS_INSTANCE_MULTI_MAP_BY_CLASS_OFFSET = getFieldOffset(ClassInstanceMultiMap.class,
            ObfuscationMapping.getFieldMapping("ClassInstanceMultiMap.byClass"));
    }

    private static long getFieldOffset(Class<?> clazz, String fieldName) throws Exception {
        Field field = ObfuscationReflectionHelper.findField(clazz, fieldName);
        return (long) OBJECT_FIELD_OFFSET_METHOD.invoke(LWJGL_UNSAFE, field);
    }

    // ==================== 底层内存操作方法 ====================

    public static void lwjglPutObject(Object target, long offset, Object value) {
        if (!available || PUT_OBJECT_METHOD == null) return;
        try {
            PUT_OBJECT_METHOD.invoke(LWJGL_UNSAFE, target, offset, value);
        } catch (Exception e) {
            EcaLogger.info("[UnsafeUtil] putObject failed: {}", e.getMessage());
        }
    }

    public static void lwjglPutInt(Object target, long offset, int value) {
        if (!available || PUT_INT_METHOD == null) return;
        try {
            PUT_INT_METHOD.invoke(LWJGL_UNSAFE, target, offset, value);
        } catch (Exception e) {
            EcaLogger.info("[UnsafeUtil] putInt failed: {}", e.getMessage());
        }
    }

    public static void lwjglPutBoolean(Object target, long offset, boolean value) {
        if (!available || PUT_BOOLEAN_METHOD == null) return;
        try {
            PUT_BOOLEAN_METHOD.invoke(LWJGL_UNSAFE, target, offset, value);
        } catch (Exception e) {
            EcaLogger.info("[UnsafeUtil] putBoolean failed: {}", e.getMessage());
        }
    }

    public static Object lwjglGetObject(Object target, long offset) {
        if (!available || GET_OBJECT_METHOD == null) return null;
        try {
            return GET_OBJECT_METHOD.invoke(LWJGL_UNSAFE, target, offset);
        } catch (Exception e) {
            EcaLogger.info("[UnsafeUtil] getObject failed: {}", e.getMessage());
            return null;
        }
    }

    public static long lwjglObjectFieldOffset(Field field) {
        if (!available || OBJECT_FIELD_OFFSET_METHOD == null) return -1;
        try {
            return (long) OBJECT_FIELD_OFFSET_METHOD.invoke(LWJGL_UNSAFE, field);
        } catch (Exception e) {
            EcaLogger.info("[UnsafeUtil] objectFieldOffset failed: {}", e.getMessage());
            return -1;
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    // ==================== 公开 API ====================

    // 强制清除实体（绕过无敌/锁血，使用 CHANGED_DIMENSION 原因跳过保护检查）
    /**
     * Forcefully remove an entity, bypassing invulnerability and health lock protections.
     * Uses CHANGED_DIMENSION as removal reason to skip vanilla protection checks.
     * All container removal operations go through the Unsafe channel to bypass call-stack interception.
     *
     * @param entity the entity to remove
     * @return true if removal succeeded, false otherwise
     */
    public static boolean unsafeRemove(Entity entity, Entity.RemovalReason reason) {
        if (entity == null || entity.level() == null) return false;
        if (entity.level().isClientSide) return false;
        ServerLevel serverLevel = (ServerLevel) entity.level();

        try {
            List<UUID> bossEventUUIDs = EntityUtil.collectAllBossEventUUIDsForRemoval(entity);
            EntityUtil.cleanupAI(entity);
            EntityUtil.cleanupBossBar(entity);
            entity.removalReason = reason;
            entity.stopRiding();
            entity.getPassengers().forEach(Entity::stopRiding);
            entity.invalidateCaps();
            EntityUtil.teleport(entity, 102400, -102400, 102400);
            broadcastEntityRemoval(serverLevel, entity, bossEventUUIDs);
            unsafeRemoveFromLoadingInbox(serverLevel, entity);
            unsafeRemoveFromSectionStorage(serverLevel, entity);
            unsafeRemoveFromEntityTickList(serverLevel, entity.getId());
            unsafeRemoveFromChunkMap(serverLevel, entity.getId());
            unsafeRemoveFromPlayersAndMobs(serverLevel, entity);
            if (entity.isMultipartEntity()) {
                for (PartEntity<?> part : entity.getParts()) {
                    serverLevel.dragonParts.remove(part.getId());
                }
            }
            entity.updateDynamicGameEventListener(DynamicGameEventListener::remove);
            entity.onRemovedFromWorld();
            unsafeRemoveFromEntityLookup(serverLevel, entity.getId(), entity.getUUID());
            callbacksOnDestroyed(serverLevel, entity);
            unsafeRemoveFromKnownUuids(serverLevel, entity.getUUID());
            entity.levelCallback = EntityInLevelCallback.NULL;
            unsafeRemoveSectionIfEmpty(serverLevel, entity);
            return true;
        } catch (Exception e) {
            EcaLogger.info("[UnsafeUtil] Failed to remove entity: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 客户端同步 ====================

    private static void broadcastEntityRemoval(ServerLevel serverLevel, Entity entity, List<UUID> bossEventUUIDs) {
        try {
            ChunkMap.TrackedEntity trackedEntity = serverLevel.chunkSource.chunkMap.entityMap.get(entity.getId());
            if (trackedEntity == null) {
                ClientRemovePacket packet = new ClientRemovePacket(entity.getId(), bossEventUUIDs);
                for (ServerPlayer player : serverLevel.players()) {
                    NetworkHandler.sendToPlayer(packet, player);
                }
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
        } catch (Exception e) {
            EcaLogger.info("[UnsafeUtil] Failed to broadcast entity removal: {}", e.getMessage());
        }
    }

    // ==================== 底层容器清除（服务端） ====================

    // 从 ChunkMap.entityMap 移除（tick 期间延迟执行，避免迭代器损坏）
    private static void unsafeRemoveFromChunkMap(ServerLevel serverLevel, int entityId) {
        if (chunkMapTicking) {
            pendingChunkMapOps.add(() -> doRemoveFromChunkMap(serverLevel, entityId));
            return;
        }
        doRemoveFromChunkMap(serverLevel, entityId);
    }

    private static void doRemoveFromChunkMap(ServerLevel serverLevel, int entityId) {
        try {
            if (SERVER_LEVEL_CHUNK_SOURCE_OFFSET < 0) return;
            Object chunkSource = lwjglGetObject(serverLevel, SERVER_LEVEL_CHUNK_SOURCE_OFFSET);
            if (chunkSource == null) return;

            if (SERVER_CHUNK_CACHE_CHUNK_MAP_OFFSET < 0) return;
            Object chunkMap = lwjglGetObject(chunkSource, SERVER_CHUNK_CACHE_CHUNK_MAP_OFFSET);
            if (chunkMap == null) return;

            if (CHUNK_MAP_ENTITY_MAP_OFFSET < 0) return;
            Int2ObjectOpenHashMap<?> entityMap = (Int2ObjectOpenHashMap<?>)
                lwjglGetObject(chunkMap, CHUNK_MAP_ENTITY_MAP_OFFSET);
            if (entityMap != null) {
                entityMap.remove(entityId);
            }
        } catch (Exception ignored) {}
    }

    // 从 ServerLevel.players / navigatingMobs 移除
    private static void unsafeRemoveFromPlayersAndMobs(ServerLevel serverLevel, Entity entity) {
        try {
            if (entity instanceof Player && SERVER_LEVEL_PLAYERS_OFFSET > 0) {
                List<?> players = (List<?>) lwjglGetObject(serverLevel, SERVER_LEVEL_PLAYERS_OFFSET);
                if (players != null) {
                    players.remove(entity);
                }
            }

            if (entity instanceof Mob && SERVER_LEVEL_NAVIGATING_MOBS_OFFSET > 0) {
                ObjectOpenHashSet<Mob> navigatingMobs = (ObjectOpenHashSet<Mob>)
                    lwjglGetObject(serverLevel, SERVER_LEVEL_NAVIGATING_MOBS_OFFSET);
                if (navigatingMobs != null) {
                    navigatingMobs.remove(entity);
                }
            }
        } catch (Exception ignored) {}
    }

    // 从 EntityLookup (byUuid + byId) 移除
    private static void unsafeRemoveFromEntityLookup(ServerLevel serverLevel, int entityId, UUID entityUUID) {
        try {
            if (SERVER_LEVEL_ENTITY_MANAGER_OFFSET < 0) return;
            Object entityManager = lwjglGetObject(serverLevel, SERVER_LEVEL_ENTITY_MANAGER_OFFSET);
            if (entityManager == null) return;

            if (PERSISTENT_MANAGER_VISIBLE_STORAGE_OFFSET < 0) return;
            Object visibleStorage = lwjglGetObject(entityManager, PERSISTENT_MANAGER_VISIBLE_STORAGE_OFFSET);
            if (visibleStorage == null) return;

            if (ENTITY_LOOKUP_BY_UUID_OFFSET > 0) {
                Map<UUID, Entity> byUuid = (Map<UUID, Entity>)
                    lwjglGetObject(visibleStorage, ENTITY_LOOKUP_BY_UUID_OFFSET);
                if (byUuid != null) {
                    byUuid.remove(entityUUID);
                }
            }

            if (ENTITY_LOOKUP_BY_ID_OFFSET > 0) {
                Int2ObjectLinkedOpenHashMap<Entity> byId = (Int2ObjectLinkedOpenHashMap<Entity>)
                    lwjglGetObject(visibleStorage, ENTITY_LOOKUP_BY_ID_OFFSET);
                if (byId != null) {
                    byId.remove(entityId);
                }
            }
        } catch (Exception ignored) {}
    }

    // 从 knownUuids 移除
    private static void unsafeRemoveFromKnownUuids(ServerLevel serverLevel, UUID entityUUID) {
        try {
            if (SERVER_LEVEL_ENTITY_MANAGER_OFFSET < 0) return;
            Object entityManager = lwjglGetObject(serverLevel, SERVER_LEVEL_ENTITY_MANAGER_OFFSET);
            if (entityManager == null) return;

            if (PERSISTENT_MANAGER_KNOWN_UUIDS_OFFSET < 0) return;
            Set<UUID> knownUuids = (Set<UUID>)
                lwjglGetObject(entityManager, PERSISTENT_MANAGER_KNOWN_UUIDS_OFFSET);
            if (knownUuids != null) {
                knownUuids.remove(entityUUID);
            }
        } catch (Exception ignored) {}
    }

    // 从 EntityTickList (active + passive) 移除（仿照原版 ensureActiveIsNotIterated + remove 逻辑）
    private static void unsafeRemoveFromEntityTickList(ServerLevel serverLevel, int entityId) {
        try {
            if (SERVER_LEVEL_ENTITY_TICK_LIST_OFFSET < 0) return;
            Object entityTickList = lwjglGetObject(serverLevel, SERVER_LEVEL_ENTITY_TICK_LIST_OFFSET);
            if (entityTickList == null) return;

            Int2ObjectLinkedOpenHashMap<Entity> active = null;
            Int2ObjectLinkedOpenHashMap<Entity> passive = null;
            Object iterated = null;

            if (ENTITY_TICK_LIST_ACTIVE_OFFSET > 0) {
                active = (Int2ObjectLinkedOpenHashMap<Entity>)
                    lwjglGetObject(entityTickList, ENTITY_TICK_LIST_ACTIVE_OFFSET);
            }
            if (ENTITY_TICK_LIST_PASSIVE_OFFSET > 0) {
                passive = (Int2ObjectLinkedOpenHashMap<Entity>)
                    lwjglGetObject(entityTickList, ENTITY_TICK_LIST_PASSIVE_OFFSET);
            }
            if (ENTITY_TICK_LIST_ITERATED_OFFSET > 0) {
                iterated = lwjglGetObject(entityTickList, ENTITY_TICK_LIST_ITERATED_OFFSET);
            }

            if (active == null || passive == null) return;

            // 仿照原版 ensureActiveIsNotIterated：绝不修改正在被遍历的 map
            if (iterated == active) {
                passive.clear();
                for (Int2ObjectMap.Entry<Entity> entry : Int2ObjectMaps.fastIterable(active)) {
                    passive.put(entry.getIntKey(), entry.getValue());
                }
                lwjglPutObject(entityTickList, ENTITY_TICK_LIST_ACTIVE_OFFSET, passive);
                lwjglPutObject(entityTickList, ENTITY_TICK_LIST_PASSIVE_OFFSET, active);
                active = passive;
            }

            active.remove(entityId);
        } catch (Exception ignored) {}
    }

    // 从 EntitySectionStorage (ClassInstanceMultiMap) 移除
    private static void unsafeRemoveFromSectionStorage(ServerLevel serverLevel, Entity entity) {
        try {
            if (SERVER_LEVEL_ENTITY_MANAGER_OFFSET < 0) return;
            Object entityManager = lwjglGetObject(serverLevel, SERVER_LEVEL_ENTITY_MANAGER_OFFSET);
            if (entityManager == null) return;

            if (PERSISTENT_MANAGER_SECTION_STORAGE_OFFSET < 0) return;
            Object sectionStorage = lwjglGetObject(entityManager, PERSISTENT_MANAGER_SECTION_STORAGE_OFFSET);
            if (sectionStorage == null) return;

            if (ENTITY_SECTION_STORAGE_SECTIONS_OFFSET < 0) return;
            Long2ObjectMap<?> sections = (Long2ObjectMap<?>)
                lwjglGetObject(sectionStorage, ENTITY_SECTION_STORAGE_SECTIONS_OFFSET);
            if (sections == null) return;

            for (Object section : sections.values()) {
                if (section == null) continue;

                if (ENTITY_SECTION_STORAGE_OFFSET < 0) continue;
                Object storage = lwjglGetObject(section, ENTITY_SECTION_STORAGE_OFFSET);
                if (storage == null) continue;

                if (CLASS_INSTANCE_MULTI_MAP_BY_CLASS_OFFSET < 0) continue;
                Map<Class<?>, List<?>> byClass = (Map<Class<?>, List<?>>)
                    lwjglGetObject(storage, CLASS_INSTANCE_MULTI_MAP_BY_CLASS_OFFSET);
                if (byClass == null) continue;

                for (Map.Entry<Class<?>, List<?>> entry : byClass.entrySet()) {
                    if (entry.getKey().isInstance(entity)) {
                        entry.getValue().remove(entity);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // 调用 callbacks.onDestroyed() 触发 scoreboard/tracking 清理
    @SuppressWarnings("rawtypes")
    private static void callbacksOnDestroyed(ServerLevel serverLevel, Entity entity) {
        try {
            if (SERVER_LEVEL_ENTITY_MANAGER_OFFSET < 0 || PERSISTENT_MANAGER_CALLBACKS_OFFSET < 0) return;
            Object entityManager = lwjglGetObject(serverLevel, SERVER_LEVEL_ENTITY_MANAGER_OFFSET);
            if (entityManager == null) return;

            Object callbacks = lwjglGetObject(entityManager, PERSISTENT_MANAGER_CALLBACKS_OFFSET);
            if (callbacks instanceof LevelCallback levelCallback) {
                levelCallback.onDestroyed(entity);
            }
        } catch (Exception ignored) {}
    }

    // 从 loadingInbox 清理正在加载的实体
    private static void unsafeRemoveFromLoadingInbox(ServerLevel serverLevel, Entity entity) {
        try {
            if (SERVER_LEVEL_ENTITY_MANAGER_OFFSET < 0 || PERSISTENT_MANAGER_LOADING_INBOX_OFFSET < 0) return;
            Object entityManager = lwjglGetObject(serverLevel, SERVER_LEVEL_ENTITY_MANAGER_OFFSET);
            if (entityManager == null) return;

            Queue<?> loadingInbox = (Queue<?>) lwjglGetObject(entityManager, PERSISTENT_MANAGER_LOADING_INBOX_OFFSET);
            if (loadingInbox == null) return;

            Iterator<?> iterator = loadingInbox.iterator();
            while (iterator.hasNext()) {
                Object chunkEntities = iterator.next();
                if (chunkEntities == null) continue;

                Field entitiesField = chunkEntities.getClass().getDeclaredField("entities");
                entitiesField.setAccessible(true);
                List<?> entities = (List<?>) entitiesField.get(chunkEntities);
                if (entities != null && entities.contains(entity)) {
                    entities.remove(entity);
                }
            }
        } catch (Exception ignored) {}
    }

    // 清理空的 EntitySection
    private static void unsafeRemoveSectionIfEmpty(ServerLevel serverLevel, Entity entity) {
        try {
            if (SERVER_LEVEL_ENTITY_MANAGER_OFFSET < 0 || PERSISTENT_MANAGER_SECTION_STORAGE_OFFSET < 0) return;
            Object entityManager = lwjglGetObject(serverLevel, SERVER_LEVEL_ENTITY_MANAGER_OFFSET);
            if (entityManager == null) return;

            Object sectionStorage = lwjglGetObject(entityManager, PERSISTENT_MANAGER_SECTION_STORAGE_OFFSET);
            if (sectionStorage == null) return;

            if (ENTITY_SECTION_STORAGE_SECTIONS_OFFSET < 0) return;
            Long2ObjectMap<?> sections = (Long2ObjectMap<?>)
                lwjglGetObject(sectionStorage, ENTITY_SECTION_STORAGE_SECTIONS_OFFSET);
            if (sections == null) return;

            long sectionKey = SectionPos.asLong(entity.blockPosition());
            Object section = sections.get(sectionKey);
            if (section instanceof EntitySection<?> entitySection) {
                if (entitySection.isEmpty()) {
                    sections.remove(sectionKey);
                }
            }
        } catch (Exception ignored) {}
    }

}
