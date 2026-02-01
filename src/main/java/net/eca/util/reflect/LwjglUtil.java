package net.eca.util.reflect;

import net.eca.util.EcaLogger;
import net.eca.network.LwjglClientRemovePacket;
import net.eca.network.NetworkHandler;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.*;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * LWJGL底层内存操作工具类
 * 通过LWJGL内部的Unsafe通道进行内存操作，绕过字节码级别的拦截
 * 原理：
 * 1. LWJGL是Minecraft核心依赖，其内部类不会被第三方修改
 * 2. 通过反射获取LWJGL内部的Unsafe实例
 * 3. 通过Method.invoke()调用Unsafe方法，字节码中不会出现直接的Unsafe调用
 */
@SuppressWarnings("unchecked")
public class LwjglUtil {

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

    // ==================== 字段偏移量缓存 ====================

    private static long REMOVAL_REASON_OFFSET = -1;
    private static long LEVEL_CALLBACK_OFFSET = -1;

    // ServerLevel 相关
    private static long SERVER_LEVEL_PLAYERS_OFFSET = -1;
    private static long SERVER_LEVEL_CHUNK_SOURCE_OFFSET = -1;
    private static long SERVER_LEVEL_ENTITY_TICK_LIST_OFFSET = -1;
    private static long SERVER_LEVEL_ENTITY_MANAGER_OFFSET = -1;
    private static long SERVER_LEVEL_NAVIGATING_MOBS_OFFSET = -1;

    // EntityTickList 相关
    private static long ENTITY_TICK_LIST_ACTIVE_OFFSET = -1;
    private static long ENTITY_TICK_LIST_PASSIVE_OFFSET = -1;
    private static long ENTITY_TICK_LIST_ITERATED_OFFSET = -1;

    // ServerChunkCache / ChunkMap 相关
    private static long SERVER_CHUNK_CACHE_CHUNK_MAP_OFFSET = -1;
    private static long CHUNK_MAP_ENTITY_MAP_OFFSET = -1;

    // PersistentEntitySectionManager 相关
    private static long PERSISTENT_MANAGER_VISIBLE_STORAGE_OFFSET = -1;
    private static long PERSISTENT_MANAGER_KNOWN_UUIDS_OFFSET = -1;
    private static long PERSISTENT_MANAGER_SECTION_STORAGE_OFFSET = -1;
    private static long PERSISTENT_MANAGER_CALLBACKS_OFFSET = -1;
    private static long PERSISTENT_MANAGER_LOADING_INBOX_OFFSET = -1;

    // EntityLookup 相关
    private static long ENTITY_LOOKUP_BY_UUID_OFFSET = -1;
    private static long ENTITY_LOOKUP_BY_ID_OFFSET = -1;

    // EntitySectionStorage / EntitySection 相关
    private static long ENTITY_SECTION_STORAGE_SECTIONS_OFFSET = -1;
    private static long ENTITY_SECTION_STORAGE_OFFSET = -1;

    // ClassInstanceMultiMap 相关
    private static long CLASS_INSTANCE_MULTI_MAP_BY_CLASS_OFFSET = -1;

    // ==================== 客户端字段偏移量 ====================

    // ClientLevel 相关
    private static long CLIENT_LEVEL_TICKING_ENTITIES_OFFSET = -1;
    private static long CLIENT_LEVEL_ENTITY_STORAGE_OFFSET = -1;
    private static long CLIENT_LEVEL_PLAYERS_OFFSET = -1;

    // BossHealthOverlay 相关
    private static long BOSS_HEALTH_OVERLAY_EVENTS_OFFSET = -1;

    // TransientEntitySectionManager 相关
    private static long TRANSIENT_MANAGER_ENTITY_STORAGE_OFFSET = -1;
    private static long TRANSIENT_MANAGER_SECTION_STORAGE_OFFSET = -1;

    private static boolean clientOffsetsInitialized = false;

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
            EcaLogger.info("[LwjglUtil] LWJGL Unsafe channel initialized successfully");
        } catch (Exception e) {
            available = false;
            EcaLogger.info("[LwjglUtil] Failed to initialize LWJGL Unsafe channel: {}", e.getMessage());
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

        // 获取 Unsafe 类
        Class<?> unsafeClass = LWJGL_UNSAFE.getClass();

        // 获取方法引用
        PUT_OBJECT_METHOD = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
        PUT_INT_METHOD = unsafeClass.getMethod("putInt", Object.class, long.class, int.class);
        PUT_LONG_METHOD = unsafeClass.getMethod("putLong", Object.class, long.class, long.class);
        PUT_BOOLEAN_METHOD = unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class);
        GET_OBJECT_METHOD = unsafeClass.getMethod("getObject", Object.class, long.class);
        OBJECT_FIELD_OFFSET_METHOD = unsafeClass.getMethod("objectFieldOffset", Field.class);
    }

    private static void initFieldOffsets() throws Exception {
        // Entity 相关字段
        REMOVAL_REASON_OFFSET = getFieldOffset(Entity.class,
            ObfuscationMapping.getFieldMapping("Entity.removalReason"));
        LEVEL_CALLBACK_OFFSET = getFieldOffset(Entity.class,
            ObfuscationMapping.getFieldMapping("Entity.levelCallback"));

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

    private static void lwjglPutObject(Object target, long offset, Object value) {
        if (!available || PUT_OBJECT_METHOD == null) return;
        try {
            PUT_OBJECT_METHOD.invoke(LWJGL_UNSAFE, target, offset, value);
        } catch (Exception e) {
            EcaLogger.info("[LwjglUtil] putObject failed: {}", e.getMessage());
        }
    }

    private static void lwjglPutInt(Object target, long offset, int value) {
        if (!available || PUT_INT_METHOD == null) return;
        try {
            PUT_INT_METHOD.invoke(LWJGL_UNSAFE, target, offset, value);
        } catch (Exception e) {
            EcaLogger.info("[LwjglUtil] putInt failed: {}", e.getMessage());
        }
    }

    private static void lwjglPutBoolean(Object target, long offset, boolean value) {
        if (!available || PUT_BOOLEAN_METHOD == null) return;
        try {
            PUT_BOOLEAN_METHOD.invoke(LWJGL_UNSAFE, target, offset, value);
        } catch (Exception e) {
            EcaLogger.info("[LwjglUtil] putBoolean failed: {}", e.getMessage());
        }
    }

    private static Object lwjglGetObject(Object target, long offset) {
        if (!available || GET_OBJECT_METHOD == null) return null;
        try {
            return GET_OBJECT_METHOD.invoke(LWJGL_UNSAFE, target, offset);
        } catch (Exception e) {
            EcaLogger.info("[LwjglUtil] getObject failed: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 客户端偏移量初始化（懒加载） ====================

    private static void initClientOffsets() {
        if (clientOffsetsInitialized) return;
        clientOffsetsInitialized = true;

        try {
            // ClientLevel 相关字段
            CLIENT_LEVEL_TICKING_ENTITIES_OFFSET = getFieldOffset(ClientLevel.class,
                ObfuscationMapping.getFieldMapping("ClientLevel.tickingEntities"));
            CLIENT_LEVEL_ENTITY_STORAGE_OFFSET = getFieldOffset(ClientLevel.class,
                ObfuscationMapping.getFieldMapping("ClientLevel.entityStorage"));
            CLIENT_LEVEL_PLAYERS_OFFSET = getFieldOffset(ClientLevel.class,
                ObfuscationMapping.getFieldMapping("ClientLevel.players"));
            // BossHealthOverlay 相关字段
            Class<?> bossOverlayClass = Class.forName("net.minecraft.client.gui.components.BossHealthOverlay");
            BOSS_HEALTH_OVERLAY_EVENTS_OFFSET = getFieldOffset(bossOverlayClass,
                ObfuscationMapping.getFieldMapping("BossHealthOverlay.events"));

            // TransientEntitySectionManager 相关字段
            TRANSIENT_MANAGER_ENTITY_STORAGE_OFFSET = getFieldOffset(TransientEntitySectionManager.class,
                ObfuscationMapping.getFieldMapping("TransientEntitySectionManager.entityStorage"));
            TRANSIENT_MANAGER_SECTION_STORAGE_OFFSET = getFieldOffset(TransientEntitySectionManager.class,
                ObfuscationMapping.getFieldMapping("TransientEntitySectionManager.sectionStorage"));

            EcaLogger.info("[LwjglUtil] Client offsets initialized");
        } catch (Exception e) {
            EcaLogger.info("[LwjglUtil] Failed to init client offsets: {}", e.getMessage());
        }
    }

    // ==================== 公开 API ====================

    // 通过LWJGL底层通道移除实体
    /**
     * Remove entity using LWJGL low-level memory channel.
     * This method bypasses bytecode-level interception by using reflection to invoke Unsafe methods.
     *
     * Execution order:
     * 1. Set Entity.removalReason = CHANGED_DIMENSION
     * 2. Set Entity.levelCallback = EntityInLevelCallback.NULL
     * 3. Remove from low-level containers (following EntityUtil order):
     *    - ChunkMap.entityMap
     *    - ServerLevel.players / navigatingMobs
     *    - EntityLookup (byUuid + byId)
     *    - KnownUuids
     *    - EntityTickList (active + passive)
     *    - EntitySectionStorage (ClassInstanceMultiMap)
     *
     * @param entity the entity to remove
     * @return true if removal succeeded, false otherwise
     */
    public static boolean lwjglRemove(Entity entity) {
        if (entity == null) return false;
        if (!available) {
            EcaLogger.info("[LwjglUtil] LWJGL channel not available, cannot remove entity");
            return false;
        }

        try {
            // 1. 设置清除原因为 CHANGED_DIMENSION（维度切换）
            if (REMOVAL_REASON_OFFSET > 0) {
                lwjglPutObject(entity, REMOVAL_REASON_OFFSET, Entity.RemovalReason.CHANGED_DIMENSION);
            }

            // 2. 设置回调为 NULL
            if (LEVEL_CALLBACK_OFFSET > 0) {
                lwjglPutObject(entity, LEVEL_CALLBACK_OFFSET, EntityInLevelCallback.NULL);
            }

            // 3. 底层容器清除（仅服务端）
            if (!entity.level().isClientSide && entity.level() instanceof ServerLevel serverLevel) {
                // 先发送移除包给客户端
                broadcastEntityRemoval(serverLevel, entity);
                // 再清除服务端容器
                removeFromServerContainersViaLwjgl(serverLevel, entity);
            }

            EcaLogger.info("[LwjglUtil] Entity {} removed via LWJGL channel", entity.getId());
            return true;
        } catch (Exception e) {
            EcaLogger.info("[LwjglUtil] Failed to remove entity: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 客户端同步 ====================

    /**
     * 广播 LWJGL 移除包给所有玩（绕过原版包拦截）
     */
    private static void broadcastEntityRemoval(ServerLevel serverLevel, Entity entity) {
        try {
            LwjglClientRemovePacket packet = new LwjglClientRemovePacket(entity.getId());
            for (ServerPlayer player : serverLevel.players()) {
                NetworkHandler.sendToPlayer(packet, player);
            }
        } catch (Exception e) {
            EcaLogger.info("[LwjglUtil] Failed to broadcast entity removal: {}", e.getMessage());
        }
    }

    // 客户端 LWJGL 清除
    /**
     * Remove entity from client using LWJGL low-level memory channel.
     * Called by EcaClientRemovePacket on client side.
     *
     * @param clientLevel the client level
     * @param entity the entity to remove
     * @return true if removal succeeded
     */
    public static boolean lwjglClientRemove(ClientLevel clientLevel, Entity entity) {
        if (entity == null || clientLevel == null) return false;
        if (!available) {
            EcaLogger.info("[LwjglUtil] LWJGL channel not available for client removal");
            return false;
        }

        try {
            // 懒加载客户端偏移量
            initClientOffsets();

            int entityId = entity.getId();
            UUID entityUUID = entity.getUUID();

            // 1. 设置移除原因
            if (REMOVAL_REASON_OFFSET > 0) {
                lwjglPutObject(entity, REMOVAL_REASON_OFFSET, Entity.RemovalReason.CHANGED_DIMENSION);
            }

            // 2. 设置回调为 NULL
            if (LEVEL_CALLBACK_OFFSET > 0) {
                lwjglPutObject(entity, LEVEL_CALLBACK_OFFSET, EntityInLevelCallback.NULL);
            }

            // 3. 客户端容器清除（与 EntityUtil.removeFromClientContainers 保持一致的顺序）
            // 3.1 清理客户端 Boss Overlay
            clearClientBossOverlayViaLwjgl();

            // 3.2 从 players 列表移除
            removeFromClientPlayersViaLwjgl(clientLevel, entity);

            // 3.3 从 EntitySectionStorage 移除
            removeFromClientEntitySectionStorageViaLwjgl(clientLevel, entity);

            // 3.5 清理空的 EntitySection
            removeClientSectionIfEmptyViaLwjgl(clientLevel, entity);

            // 3.6 从 EntityTickList 移除
            removeFromClientTickListViaLwjgl(clientLevel, entityId);

            // 3.7 从 EntityLookup 移除
            removeFromClientEntityLookupViaLwjgl(clientLevel, entityId, entityUUID);

            EcaLogger.info("[LwjglUtil] Client entity {} removed via LWJGL channel", entityId);
            return true;
        } catch (Exception e) {
            EcaLogger.info("[LwjglUtil] Failed to remove client entity: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 底层容器清除（服务端） ====================

    private static void removeFromServerContainersViaLwjgl(ServerLevel serverLevel, Entity entity) {
        int entityId = entity.getId();
        UUID entityUUID = entity.getUUID();

        try {
            // 与 EntityUtil.removeFromServerContainers 保持一致的顺序
            // 1. 先触发回调（回调可能需要查找实体）
            callbacksOnDestroyedViaLwjgl(serverLevel, entity);

            // 2. 从 loadingInbox 清理
            removeFromLoadingInboxViaLwjgl(serverLevel, entity);

            // 3. ChunkMap.entityMap
            removeFromChunkMapViaLwjgl(serverLevel, entityId);

            // 4. ServerLevel.players / navigatingMobs
            removeFromPlayersOrMobsViaLwjgl(serverLevel, entity);

            // 5. EntitySectionStorage (ClassInstanceMultiMap)
            removeFromEntitySectionStorageViaLwjgl(serverLevel, entity);

            // 6. 清理空的 EntitySection
            removeSectionIfEmptyViaLwjgl(serverLevel, entity);

            // 7. EntityTickList (active + passive)
            removeFromEntityTickListViaLwjgl(serverLevel, entityId);

            // 8. EntityLookup (byUuid + byId)
            removeFromEntityLookupViaLwjgl(serverLevel, entityId, entityUUID);

            // 9. KnownUuids
            removeFromKnownUuidsViaLwjgl(serverLevel, entityUUID);

        } catch (Exception e) {
            EcaLogger.info("[LwjglUtil] Container cleanup failed: {}", e.getMessage());
        }
    }

    // 从 ChunkMap.entityMap 移除
    private static void removeFromChunkMapViaLwjgl(ServerLevel serverLevel, int entityId) {
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
    private static void removeFromPlayersOrMobsViaLwjgl(ServerLevel serverLevel, Entity entity) {
        try {
            // 如果是玩家，从 players 列表移除
            if (entity instanceof Player && SERVER_LEVEL_PLAYERS_OFFSET > 0) {
                List<?> players = (List<?>) lwjglGetObject(serverLevel, SERVER_LEVEL_PLAYERS_OFFSET);
                if (players != null) {
                    players.remove(entity);
                }
            }

            // 如果是 Mob，从 navigatingMobs 集合移除
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
    private static void removeFromEntityLookupViaLwjgl(ServerLevel serverLevel, int entityId, UUID entityUUID) {
        try {
            if (SERVER_LEVEL_ENTITY_MANAGER_OFFSET < 0) return;

            Object entityManager = lwjglGetObject(serverLevel, SERVER_LEVEL_ENTITY_MANAGER_OFFSET);
            if (entityManager == null) return;

            if (PERSISTENT_MANAGER_VISIBLE_STORAGE_OFFSET < 0) return;
            Object visibleStorage = lwjglGetObject(entityManager, PERSISTENT_MANAGER_VISIBLE_STORAGE_OFFSET);
            if (visibleStorage == null) return;

            // 移除 byUuid
            if (ENTITY_LOOKUP_BY_UUID_OFFSET > 0) {
                Map<UUID, Entity> byUuid = (Map<UUID, Entity>)
                    lwjglGetObject(visibleStorage, ENTITY_LOOKUP_BY_UUID_OFFSET);
                if (byUuid != null) {
                    byUuid.remove(entityUUID);
                }
            }

            // 移除 byId
            if (ENTITY_LOOKUP_BY_ID_OFFSET > 0) {
                Int2ObjectLinkedOpenHashMap<Entity> byId = (Int2ObjectLinkedOpenHashMap<Entity>)
                    lwjglGetObject(visibleStorage, ENTITY_LOOKUP_BY_ID_OFFSET);
                if (byId != null) {
                    byId.remove(entityId);
                }
            }
        } catch (Exception ignored) {}
    }

    // 从 KnownUuids 移除
    private static void removeFromKnownUuidsViaLwjgl(ServerLevel serverLevel, UUID entityUUID) {
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
    private static void removeFromEntityTickListViaLwjgl(ServerLevel serverLevel, int entityId) {
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
    private static void removeFromEntitySectionStorageViaLwjgl(ServerLevel serverLevel, Entity entity) {
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

            // 遍历所有 EntitySection
            for (Object section : sections.values()) {
                if (section == null) continue;

                if (ENTITY_SECTION_STORAGE_OFFSET < 0) continue;
                Object storage = lwjglGetObject(section, ENTITY_SECTION_STORAGE_OFFSET);
                if (storage == null) continue;

                // 获取 ClassInstanceMultiMap.byClass
                if (CLASS_INSTANCE_MULTI_MAP_BY_CLASS_OFFSET < 0) continue;
                Map<Class<?>, List<?>> byClass = (Map<Class<?>, List<?>>)
                    lwjglGetObject(storage, CLASS_INSTANCE_MULTI_MAP_BY_CLASS_OFFSET);
                if (byClass == null) continue;

                // 遍历所有类型的 List，移除匹配的实体
                for (Map.Entry<Class<?>, List<?>> entry : byClass.entrySet()) {
                    Class<?> clazz = entry.getKey();
                    List<?> list = entry.getValue();

                    if (clazz.isInstance(entity)) {
                        list.remove(entity);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // 调用 callbacks.onDestroyed() 触发 tracking 结束
    @SuppressWarnings("rawtypes")
    private static void callbacksOnDestroyedViaLwjgl(ServerLevel serverLevel, Entity entity) {
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
    private static void removeFromLoadingInboxViaLwjgl(ServerLevel serverLevel, Entity entity) {
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
    private static void removeSectionIfEmptyViaLwjgl(ServerLevel serverLevel, Entity entity) {
        try {
            if (SERVER_LEVEL_ENTITY_MANAGER_OFFSET < 0 || PERSISTENT_MANAGER_SECTION_STORAGE_OFFSET < 0) return;

            Object entityManager = lwjglGetObject(serverLevel, SERVER_LEVEL_ENTITY_MANAGER_OFFSET);
            if (entityManager == null) return;

            Object sectionStorage = lwjglGetObject(entityManager, PERSISTENT_MANAGER_SECTION_STORAGE_OFFSET);
            if (sectionStorage == null) return;

            if (ENTITY_SECTION_STORAGE_SECTIONS_OFFSET < 0) return;
            Long2ObjectMap<?> sections = (Long2ObjectMap<?>) lwjglGetObject(sectionStorage, ENTITY_SECTION_STORAGE_SECTIONS_OFFSET);
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

    // ==================== 客户端底层容器清除 ====================

    // 从客户端 EntityTickList 移除（仿照原版 ensureActiveIsNotIterated + remove 逻辑）
    private static void removeFromClientTickListViaLwjgl(ClientLevel clientLevel, int entityId) {
        try {
            if (CLIENT_LEVEL_TICKING_ENTITIES_OFFSET < 0) return;

            Object entityTickList = lwjglGetObject(clientLevel, CLIENT_LEVEL_TICKING_ENTITIES_OFFSET);
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

    // 从客户端 EntityLookup 移除
    private static void removeFromClientEntityLookupViaLwjgl(ClientLevel clientLevel, int entityId, UUID entityUUID) {
        try {
            if (CLIENT_LEVEL_ENTITY_STORAGE_OFFSET < 0) return;

            Object entityStorage = lwjglGetObject(clientLevel, CLIENT_LEVEL_ENTITY_STORAGE_OFFSET);
            if (entityStorage == null) return;

            if (TRANSIENT_MANAGER_ENTITY_STORAGE_OFFSET < 0) return;
            Object entityLookup = lwjglGetObject(entityStorage, TRANSIENT_MANAGER_ENTITY_STORAGE_OFFSET);
            if (entityLookup == null) return;

            // 移除 byUuid
            if (ENTITY_LOOKUP_BY_UUID_OFFSET > 0) {
                Map<UUID, Entity> byUuid = (Map<UUID, Entity>)
                    lwjglGetObject(entityLookup, ENTITY_LOOKUP_BY_UUID_OFFSET);
                if (byUuid != null) {
                    byUuid.remove(entityUUID);
                }
            }

            // 移除 byId
            if (ENTITY_LOOKUP_BY_ID_OFFSET > 0) {
                Int2ObjectLinkedOpenHashMap<Entity> byId = (Int2ObjectLinkedOpenHashMap<Entity>)
                    lwjglGetObject(entityLookup, ENTITY_LOOKUP_BY_ID_OFFSET);
                if (byId != null) {
                    byId.remove(entityId);
                }
            }
        } catch (Exception ignored) {}
    }

    // 从客户端 EntitySectionStorage 移除
    private static void removeFromClientEntitySectionStorageViaLwjgl(ClientLevel clientLevel, Entity entity) {
        try {
            if (CLIENT_LEVEL_ENTITY_STORAGE_OFFSET < 0) return;

            Object entityStorage = lwjglGetObject(clientLevel, CLIENT_LEVEL_ENTITY_STORAGE_OFFSET);
            if (entityStorage == null) return;

            if (TRANSIENT_MANAGER_SECTION_STORAGE_OFFSET < 0) return;
            Object sectionStorage = lwjglGetObject(entityStorage, TRANSIENT_MANAGER_SECTION_STORAGE_OFFSET);
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

    // 从客户端 players 列表移除（如果是玩家）
    private static void removeFromClientPlayersViaLwjgl(ClientLevel clientLevel, Entity entity) {
        try {
            if (!(entity instanceof Player)) return;
            if (CLIENT_LEVEL_PLAYERS_OFFSET < 0) return;

            List<?> players = (List<?>) lwjglGetObject(clientLevel, CLIENT_LEVEL_PLAYERS_OFFSET);
            if (players != null) {
                players.remove(entity);
            }
        } catch (Exception ignored) {}
    }

    // 清理客户端 Boss Overlay
    private static void clearClientBossOverlayViaLwjgl() {
        try {
            if (BOSS_HEALTH_OVERLAY_EVENTS_OFFSET < 0) return;

            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.gui == null) return;

            BossHealthOverlay bossOverlay = minecraft.gui.getBossOverlay();
            if (bossOverlay == null) return;

            Object events = lwjglGetObject(bossOverlay, BOSS_HEALTH_OVERLAY_EVENTS_OFFSET);
            if (events instanceof Map<?, ?> eventsMap) {
                eventsMap.clear();
            }
        } catch (Exception ignored) {}
    }

    // 清理空的客户端 EntitySection
    private static void removeClientSectionIfEmptyViaLwjgl(ClientLevel clientLevel, Entity entity) {
        try {
            if (CLIENT_LEVEL_ENTITY_STORAGE_OFFSET < 0 || TRANSIENT_MANAGER_SECTION_STORAGE_OFFSET < 0) return;

            Object entityStorage = lwjglGetObject(clientLevel, CLIENT_LEVEL_ENTITY_STORAGE_OFFSET);
            if (entityStorage == null) return;

            Object sectionStorage = lwjglGetObject(entityStorage, TRANSIENT_MANAGER_SECTION_STORAGE_OFFSET);
            if (sectionStorage == null) return;

            if (ENTITY_SECTION_STORAGE_SECTIONS_OFFSET < 0) return;
            Long2ObjectMap<?> sections = (Long2ObjectMap<?>) lwjglGetObject(sectionStorage, ENTITY_SECTION_STORAGE_SECTIONS_OFFSET);
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
