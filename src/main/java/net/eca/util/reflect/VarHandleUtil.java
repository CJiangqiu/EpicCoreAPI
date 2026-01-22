package net.eca.util.reflect;

import net.eca.util.EcaLogger;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.*;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// VarHandle工具类 - 高性能字段访问
/**
 * VarHandle utility class for high-performance field access.
 * VarHandle provides better performance than traditional reflection for frequent access.
 */
@SuppressWarnings("unchecked")
public final class VarHandleUtil {

    private static final Map<String, VarHandle> VARHANDLE_CACHE = new ConcurrentHashMap<>();

    // VarHandle初始化
    public static VarHandle VH_DATA_ITEM_VALUE;
    public static VarHandle VH_DATA_ITEM_DIRTY;
    public static Field FIELD_ITEMS_BY_ID;
    public static Field FIELD_IS_DIRTY;
    public static VarHandle VH_DEATH_TIME;
    public static VarHandle VH_DEAD;
    public static VarHandle VH_ENTITY_POSITION;
    public static VarHandle VH_ENTITY_X_OLD;
    public static VarHandle VH_ENTITY_Y_OLD;
    public static VarHandle VH_ENTITY_Z_OLD;
    public static VarHandle VH_ENTITY_BB;
    public static VarHandle VH_ENTITY_REMOVAL_REASON;
    public static VarHandle VH_SERVER_LEVEL_PLAYERS;
    public static VarHandle VH_SERVER_LEVEL_CHUNK_SOURCE;
    public static VarHandle VH_SERVER_LEVEL_ENTITY_TICK_LIST;
    public static VarHandle VH_SERVER_LEVEL_ENTITY_MANAGER;
    public static VarHandle VH_SERVER_LEVEL_NAVIGATING_MOBS;
    public static VarHandle VH_ENTITY_TICK_LIST_ACTIVE;
    public static VarHandle VH_ENTITY_TICK_LIST_PASSIVE;
    public static VarHandle VH_ENTITY_TICK_LIST_ITERATED;
    public static VarHandle VH_SERVER_CHUNK_CACHE_CHUNK_MAP;
    public static VarHandle VH_CHUNK_MAP_ENTITY_MAP;
    public static VarHandle VH_PERSISTENT_ENTITY_MANAGER_VISIBLE_STORAGE;
    public static VarHandle VH_PERSISTENT_ENTITY_MANAGER_KNOWN_UUIDS;
    public static VarHandle VH_PERSISTENT_ENTITY_MANAGER_SECTION_STORAGE;
    public static VarHandle VH_PERSISTENT_ENTITY_MANAGER_CALLBACKS;
    public static VarHandle VH_PERSISTENT_ENTITY_MANAGER_LOADING_INBOX;
    public static VarHandle VH_ENTITY_LOOKUP_BY_UUID;
    public static VarHandle VH_ENTITY_LOOKUP_BY_ID;
    public static VarHandle VH_ENTITY_SECTION_STORAGE_SECTIONS;
    public static VarHandle VH_ENTITY_SECTION_STORAGE;
    public static VarHandle VH_CLASS_INSTANCE_MULTI_MAP_BY_CLASS;
    public static VarHandle VH_CLIENT_LEVEL_TICKING_ENTITIES;
    public static VarHandle VH_CLIENT_LEVEL_ENTITY_STORAGE;
    public static VarHandle VH_CLIENT_LEVEL_PLAYERS;
    public static VarHandle VH_CLIENT_LEVEL_PART_ENTITIES;
    public static VarHandle VH_TRANSIENT_ENTITY_MANAGER_ENTITY_STORAGE;
    public static VarHandle VH_TRANSIENT_ENTITY_MANAGER_SECTION_STORAGE;
    public static VarHandle VH_BOSS_HEALTH_OVERLAY_EVENTS;

    // 初始化状态
    private static volatile boolean initialized = false;
    private static volatile boolean clientInitialized = false;

    // ==================== 初始化方法 ====================

    // 初始化所有服务端 VarHandle
    public static void init() {
        if (initialized) return;
        synchronized (VarHandleUtil.class) {
            if (initialized) return;
            try {
                initHealthHandles();
                initDeathHandles();
                initTeleportHandles();
                initServerRemovalHandles();
                initialized = true;
                EcaLogger.info("[VarHandleUtil] Initialized successfully");
            } catch (Exception e) {
                EcaLogger.info("[VarHandleUtil] Failed to initialize: {}", e.getMessage());
            }
        }
    }

    // 初始化客户端 VarHandle（懒加载）
    public static void initClient() {
        if (clientInitialized) return;
        synchronized (VarHandleUtil.class) {
            if (clientInitialized) return;
            try {
                initClientRemovalHandles();
                clientInitialized = true;
                EcaLogger.info("[VarHandleUtil] Client handles initialized successfully");
            } catch (Exception e) {
                EcaLogger.info("[VarHandleUtil] Failed to initialize client handles: {}", e.getMessage());
            }
        }
    }

    private static void initHealthHandles() throws Exception {
        Class<?> dataItemClass = SynchedEntityData.DataItem.class;
        String valueObfName = ObfuscationMapping.getFieldMapping("DataItem.value");
        String dirtyObfName = ObfuscationMapping.getFieldMapping("DataItem.dirty");

        Field valueField = ObfuscationReflectionHelper.findField(dataItemClass, valueObfName);
        Field dirtyField = ObfuscationReflectionHelper.findField(dataItemClass, dirtyObfName);

        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(dataItemClass, MethodHandles.lookup());
        VH_DATA_ITEM_VALUE = lookup.unreflectVarHandle(valueField);
        VH_DATA_ITEM_DIRTY = lookup.unreflectVarHandle(dirtyField);

        String itemsByIdObfName = ObfuscationMapping.getFieldMapping("SynchedEntityData.itemsById");
        FIELD_ITEMS_BY_ID = ObfuscationReflectionHelper.findField(SynchedEntityData.class, itemsByIdObfName);

        String isDirtyObfName = ObfuscationMapping.getFieldMapping("SynchedEntityData.isDirty");
        FIELD_IS_DIRTY = ObfuscationReflectionHelper.findField(SynchedEntityData.class, isDirtyObfName);
    }

    private static void initDeathHandles() throws Exception {
        String deathTimeObfName = ObfuscationMapping.getFieldMapping("LivingEntity.deathTime");
        String deadObfName = ObfuscationMapping.getFieldMapping("LivingEntity.dead");

        Field deathTimeField = ObfuscationReflectionHelper.findField(LivingEntity.class, deathTimeObfName);
        Field deadField = ObfuscationReflectionHelper.findField(LivingEntity.class, deadObfName);

        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(LivingEntity.class, MethodHandles.lookup());
        VH_DEATH_TIME = lookup.unreflectVarHandle(deathTimeField);
        VH_DEAD = lookup.unreflectVarHandle(deadField);
    }

    private static void initTeleportHandles() throws Exception {
        Field positionField = ObfuscationReflectionHelper.findField(Entity.class, ObfuscationMapping.getFieldMapping("Entity.position"));
        Field xOldField = ObfuscationReflectionHelper.findField(Entity.class, ObfuscationMapping.getFieldMapping("Entity.xOld"));
        Field yOldField = ObfuscationReflectionHelper.findField(Entity.class, ObfuscationMapping.getFieldMapping("Entity.yOld"));
        Field zOldField = ObfuscationReflectionHelper.findField(Entity.class, ObfuscationMapping.getFieldMapping("Entity.zOld"));
        Field bbField = ObfuscationReflectionHelper.findField(Entity.class, ObfuscationMapping.getFieldMapping("Entity.bb"));
        Field removalReasonField = ObfuscationReflectionHelper.findField(Entity.class, ObfuscationMapping.getFieldMapping("Entity.removalReason"));

        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Entity.class, MethodHandles.lookup());
        VH_ENTITY_POSITION = lookup.unreflectVarHandle(positionField);
        VH_ENTITY_X_OLD = lookup.unreflectVarHandle(xOldField);
        VH_ENTITY_Y_OLD = lookup.unreflectVarHandle(yOldField);
        VH_ENTITY_Z_OLD = lookup.unreflectVarHandle(zOldField);
        VH_ENTITY_BB = lookup.unreflectVarHandle(bbField);
        VH_ENTITY_REMOVAL_REASON = lookup.unreflectVarHandle(removalReasonField);
    }

    private static void initServerRemovalHandles() throws Exception {
        // ServerLevel
        Field playersField = ObfuscationReflectionHelper.findField(ServerLevel.class, ObfuscationMapping.getFieldMapping("ServerLevel.players"));
        Field chunkSourceField = ObfuscationReflectionHelper.findField(ServerLevel.class, ObfuscationMapping.getFieldMapping("ServerLevel.chunkSource"));
        Field entityTickListField = ObfuscationReflectionHelper.findField(ServerLevel.class, ObfuscationMapping.getFieldMapping("ServerLevel.entityTickList"));
        Field entityManagerField = ObfuscationReflectionHelper.findField(ServerLevel.class, ObfuscationMapping.getFieldMapping("ServerLevel.entityManager"));
        Field navigatingMobsField = ObfuscationReflectionHelper.findField(ServerLevel.class, ObfuscationMapping.getFieldMapping("ServerLevel.navigatingMobs"));

        MethodHandles.Lookup serverLevelLookup = MethodHandles.privateLookupIn(ServerLevel.class, MethodHandles.lookup());
        VH_SERVER_LEVEL_PLAYERS = serverLevelLookup.unreflectVarHandle(playersField);
        VH_SERVER_LEVEL_CHUNK_SOURCE = serverLevelLookup.unreflectVarHandle(chunkSourceField);
        VH_SERVER_LEVEL_ENTITY_TICK_LIST = serverLevelLookup.unreflectVarHandle(entityTickListField);
        VH_SERVER_LEVEL_ENTITY_MANAGER = serverLevelLookup.unreflectVarHandle(entityManagerField);
        VH_SERVER_LEVEL_NAVIGATING_MOBS = serverLevelLookup.unreflectVarHandle(navigatingMobsField);

        // EntityTickList
        Field activeField = ObfuscationReflectionHelper.findField(EntityTickList.class, ObfuscationMapping.getFieldMapping("EntityTickList.active"));
        Field passiveField = ObfuscationReflectionHelper.findField(EntityTickList.class, ObfuscationMapping.getFieldMapping("EntityTickList.passive"));
        Field iteratedField = ObfuscationReflectionHelper.findField(EntityTickList.class, ObfuscationMapping.getFieldMapping("EntityTickList.iterated"));

        MethodHandles.Lookup tickListLookup = MethodHandles.privateLookupIn(EntityTickList.class, MethodHandles.lookup());
        VH_ENTITY_TICK_LIST_ACTIVE = tickListLookup.unreflectVarHandle(activeField);
        VH_ENTITY_TICK_LIST_PASSIVE = tickListLookup.unreflectVarHandle(passiveField);
        VH_ENTITY_TICK_LIST_ITERATED = tickListLookup.unreflectVarHandle(iteratedField);

        // ServerChunkCache
        Field chunkMapField = ObfuscationReflectionHelper.findField(ServerChunkCache.class, ObfuscationMapping.getFieldMapping("ServerChunkCache.chunkMap"));
        MethodHandles.Lookup chunkCacheLookup = MethodHandles.privateLookupIn(ServerChunkCache.class, MethodHandles.lookup());
        VH_SERVER_CHUNK_CACHE_CHUNK_MAP = chunkCacheLookup.unreflectVarHandle(chunkMapField);

        // ChunkMap
        Field entityMapField = ObfuscationReflectionHelper.findField(ChunkMap.class, ObfuscationMapping.getFieldMapping("ChunkMap.entityMap"));
        MethodHandles.Lookup chunkMapLookup = MethodHandles.privateLookupIn(ChunkMap.class, MethodHandles.lookup());
        VH_CHUNK_MAP_ENTITY_MAP = chunkMapLookup.unreflectVarHandle(entityMapField);

        // PersistentEntitySectionManager
        Field visibleStorageField = ObfuscationReflectionHelper.findField(PersistentEntitySectionManager.class, ObfuscationMapping.getFieldMapping("PersistentEntitySectionManager.visibleEntityStorage"));
        Field knownUuidsField = ObfuscationReflectionHelper.findField(PersistentEntitySectionManager.class, ObfuscationMapping.getFieldMapping("PersistentEntitySectionManager.knownUuids"));
        Field sectionStorageField = ObfuscationReflectionHelper.findField(PersistentEntitySectionManager.class, ObfuscationMapping.getFieldMapping("PersistentEntitySectionManager.sectionStorage"));
        Field callbacksField = ObfuscationReflectionHelper.findField(PersistentEntitySectionManager.class, ObfuscationMapping.getFieldMapping("PersistentEntitySectionManager.callbacks"));
        Field loadingInboxField = ObfuscationReflectionHelper.findField(PersistentEntitySectionManager.class, ObfuscationMapping.getFieldMapping("PersistentEntitySectionManager.loadingInbox"));

        MethodHandles.Lookup persistentLookup = MethodHandles.privateLookupIn(PersistentEntitySectionManager.class, MethodHandles.lookup());
        VH_PERSISTENT_ENTITY_MANAGER_VISIBLE_STORAGE = persistentLookup.unreflectVarHandle(visibleStorageField);
        VH_PERSISTENT_ENTITY_MANAGER_KNOWN_UUIDS = persistentLookup.unreflectVarHandle(knownUuidsField);
        VH_PERSISTENT_ENTITY_MANAGER_SECTION_STORAGE = persistentLookup.unreflectVarHandle(sectionStorageField);
        VH_PERSISTENT_ENTITY_MANAGER_CALLBACKS = persistentLookup.unreflectVarHandle(callbacksField);
        VH_PERSISTENT_ENTITY_MANAGER_LOADING_INBOX = persistentLookup.unreflectVarHandle(loadingInboxField);

        // EntityLookup
        Field byUuidField = ObfuscationReflectionHelper.findField(EntityLookup.class, ObfuscationMapping.getFieldMapping("EntityLookup.byUuid"));
        Field byIdField = ObfuscationReflectionHelper.findField(EntityLookup.class, ObfuscationMapping.getFieldMapping("EntityLookup.byId"));

        MethodHandles.Lookup lookupLookup = MethodHandles.privateLookupIn(EntityLookup.class, MethodHandles.lookup());
        VH_ENTITY_LOOKUP_BY_UUID = lookupLookup.unreflectVarHandle(byUuidField);
        VH_ENTITY_LOOKUP_BY_ID = lookupLookup.unreflectVarHandle(byIdField);

        // EntitySectionStorage
        Field sectionsField = ObfuscationReflectionHelper.findField(EntitySectionStorage.class, ObfuscationMapping.getFieldMapping("EntitySectionStorage.sections"));
        MethodHandles.Lookup sectionStorageLookup = MethodHandles.privateLookupIn(EntitySectionStorage.class, MethodHandles.lookup());
        VH_ENTITY_SECTION_STORAGE_SECTIONS = sectionStorageLookup.unreflectVarHandle(sectionsField);

        // EntitySection
        Field storageField = ObfuscationReflectionHelper.findField(EntitySection.class, ObfuscationMapping.getFieldMapping("EntitySection.storage"));
        MethodHandles.Lookup sectionLookup = MethodHandles.privateLookupIn(EntitySection.class, MethodHandles.lookup());
        VH_ENTITY_SECTION_STORAGE = sectionLookup.unreflectVarHandle(storageField);

        // ClassInstanceMultiMap
        Field byClassField = ObfuscationReflectionHelper.findField(ClassInstanceMultiMap.class, ObfuscationMapping.getFieldMapping("ClassInstanceMultiMap.byClass"));
        MethodHandles.Lookup multiMapLookup = MethodHandles.privateLookupIn(ClassInstanceMultiMap.class, MethodHandles.lookup());
        VH_CLASS_INSTANCE_MULTI_MAP_BY_CLASS = multiMapLookup.unreflectVarHandle(byClassField);
    }

    private static void initClientRemovalHandles() throws Exception {
        // ClientLevel
        Field tickingEntitiesField = ObfuscationReflectionHelper.findField(ClientLevel.class, ObfuscationMapping.getFieldMapping("ClientLevel.tickingEntities"));
        Field entityStorageField = ObfuscationReflectionHelper.findField(ClientLevel.class, ObfuscationMapping.getFieldMapping("ClientLevel.entityStorage"));
        Field playersField = ObfuscationReflectionHelper.findField(ClientLevel.class, ObfuscationMapping.getFieldMapping("ClientLevel.players"));
        Field partEntitiesField = ObfuscationReflectionHelper.findField(ClientLevel.class, ObfuscationMapping.getFieldMapping("ClientLevel.partEntities"));

        MethodHandles.Lookup clientLevelLookup = MethodHandles.privateLookupIn(ClientLevel.class, MethodHandles.lookup());
        VH_CLIENT_LEVEL_TICKING_ENTITIES = clientLevelLookup.unreflectVarHandle(tickingEntitiesField);
        VH_CLIENT_LEVEL_ENTITY_STORAGE = clientLevelLookup.unreflectVarHandle(entityStorageField);
        VH_CLIENT_LEVEL_PLAYERS = clientLevelLookup.unreflectVarHandle(playersField);
        VH_CLIENT_LEVEL_PART_ENTITIES = clientLevelLookup.unreflectVarHandle(partEntitiesField);

        // TransientEntitySectionManager
        Field transientEntityStorageField = ObfuscationReflectionHelper.findField(TransientEntitySectionManager.class, ObfuscationMapping.getFieldMapping("TransientEntitySectionManager.entityStorage"));
        Field transientSectionStorageField = ObfuscationReflectionHelper.findField(TransientEntitySectionManager.class, ObfuscationMapping.getFieldMapping("TransientEntitySectionManager.sectionStorage"));

        MethodHandles.Lookup transientLookup = MethodHandles.privateLookupIn(TransientEntitySectionManager.class, MethodHandles.lookup());
        VH_TRANSIENT_ENTITY_MANAGER_ENTITY_STORAGE = transientLookup.unreflectVarHandle(transientEntityStorageField);
        VH_TRANSIENT_ENTITY_MANAGER_SECTION_STORAGE = transientLookup.unreflectVarHandle(transientSectionStorageField);

        // BossHealthOverlay
        Field bossEventsField = ObfuscationReflectionHelper.findField(BossHealthOverlay.class, ObfuscationMapping.getFieldMapping("BossHealthOverlay.events"));
        MethodHandles.Lookup bossOverlayLookup = MethodHandles.privateLookupIn(BossHealthOverlay.class, MethodHandles.lookup());
        VH_BOSS_HEALTH_OVERLAY_EVENTS = bossOverlayLookup.unreflectVarHandle(bossEventsField);
    }

    // ==================== 通用工具方法 ====================

    // 通过映射key获取VarHandle
    /**
     * Get a VarHandle using the obfuscation mapping key.
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key like "Entity.entityData"
     * @return the VarHandle for the field, or null if not found
     */
    public static VarHandle getVarHandle(Class<?> clazz, String fieldKey) {
        String cacheKey = clazz.getName() + "#" + fieldKey;

        return VARHANDLE_CACHE.computeIfAbsent(cacheKey, k -> {
            try {
                Field field = ReflectUtil.getField(clazz, fieldKey);
                if (field == null) {
                    return null;
                }
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
                return lookup.unreflectVarHandle(field);
            } catch (IllegalAccessException e) {
                EcaLogger.info("[VarHandleUtil] Failed to create VarHandle for: {} in {}", fieldKey, clazz.getName(), e);
                return null;
            }
        });
    }

    // 通过字段名直接获取VarHandle
    /**
     * Get a VarHandle directly by field name (for non-obfuscated fields).
     * @param clazz the class containing the field
     * @param fieldName the actual field name
     * @return the VarHandle for the field, or null if not found
     */
    public static VarHandle getVarHandleByName(Class<?> clazz, String fieldName) {
        String cacheKey = clazz.getName() + "#direct#" + fieldName;

        return VARHANDLE_CACHE.computeIfAbsent(cacheKey, k -> {
            try {
                Field field = ReflectUtil.getFieldByName(clazz, fieldName);
                if (field == null) {
                    return null;
                }
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
                return lookup.unreflectVarHandle(field);
            } catch (IllegalAccessException e) {
                EcaLogger.info("[VarHandleUtil] Failed to create VarHandle for field: {} in {}", fieldName, clazz.getName(), e);
                return null;
            }
        });
    }

    // 获取字段值
    /**
     * Get field value using VarHandle.
     * @param target the target object (null for static fields)
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param <T> the expected return type
     * @return the field value, or null if failed
     */
    public static <T> T get(Object target, Class<?> clazz, String fieldKey) {
        VarHandle handle = getVarHandle(clazz, fieldKey);
        if (handle == null) {
            return null;
        }
        return (T) handle.get(target);
    }

    // 设置字段值
    /**
     * Set field value using VarHandle.
     * @param target the target object (null for static fields)
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param value the value to set
     * @return true if successful, false if failed
     */
    public static boolean set(Object target, Class<?> clazz, String fieldKey, Object value) {
        VarHandle handle = getVarHandle(clazz, fieldKey);
        if (handle == null) {
            return false;
        }
        handle.set(target, value);
        return true;
    }

    // 原子性获取并设置
    /**
     * Atomically get and set field value.
     * @param target the target object
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param newValue the new value to set
     * @param <T> the value type
     * @return the previous value, or null if failed
     */
    public static <T> T getAndSet(Object target, Class<?> clazz, String fieldKey, T newValue) {
        VarHandle handle = getVarHandle(clazz, fieldKey);
        if (handle == null) {
            return null;
        }
        return (T) handle.getAndSet(target, newValue);
    }

    // 比较并设置
    /**
     * Atomically compare and set field value.
     * @param target the target object
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param expectedValue the expected current value
     * @param newValue the new value to set
     * @return true if successful, false if failed or value mismatch
     */
    public static boolean compareAndSet(Object target, Class<?> clazz, String fieldKey, Object expectedValue, Object newValue) {
        VarHandle handle = getVarHandle(clazz, fieldKey);
        if (handle == null) {
            return false;
        }
        return handle.compareAndSet(target, expectedValue, newValue);
    }

    // volatile读取
    /**
     * Get field value with volatile semantics.
     * @param target the target object
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param <T> the expected return type
     * @return the field value, or null if failed
     */
    public static <T> T getVolatile(Object target, Class<?> clazz, String fieldKey) {
        VarHandle handle = getVarHandle(clazz, fieldKey);
        if (handle == null) {
            return null;
        }
        return (T) handle.getVolatile(target);
    }

    // volatile写入
    /**
     * Set field value with volatile semantics.
     * @param target the target object
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param value the value to set
     * @return true if successful, false if failed
     */
    public static boolean setVolatile(Object target, Class<?> clazz, String fieldKey, Object value) {
        VarHandle handle = getVarHandle(clazz, fieldKey);
        if (handle == null) {
            return false;
        }
        handle.setVolatile(target, value);
        return true;
    }

    // 清空缓存
    /**
     * Clear all cached VarHandles.
     */
    public static void clearCache() {
        VARHANDLE_CACHE.clear();
    }

    // ==================== 运行时动态字段访问（支持Field对象）====================

    // 通过Field对象获取VarHandle
    /**
     * Get a VarHandle from a Field object (for runtime-discovered fields).
     * This is useful when you have a Field object from reflection and want high-performance access.
     * @param field the field object
     * @return the VarHandle for the field, or null if failed
     */
    public static VarHandle getVarHandleFromField(Field field) {
        if (field == null) {
            EcaLogger.info("[VarHandleUtil] Field cannot be null");
            return null;
        }

        Class<?> declaringClass = field.getDeclaringClass();
        String fieldName = field.getName();
        Class<?> fieldType = field.getType();

        // 使用更精确的缓存key：类名#字段名#类型
        String cacheKey = declaringClass.getName() + "#field#" + fieldName + "#" + fieldType.getName();

        return VARHANDLE_CACHE.computeIfAbsent(cacheKey, k -> {
            try {
                field.setAccessible(true);
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(declaringClass, MethodHandles.lookup());
                return lookup.unreflectVarHandle(field);
            } catch (IllegalAccessException e) {
                EcaLogger.info("[VarHandleUtil] Failed to create VarHandle from Field: {} in {}", fieldName, declaringClass.getName(), e);
                return null;
            }
        });
    }

    // 通过Field对象获取字段值
    /**
     * Get field value using a Field object.
     * @param target the target object (null for static fields)
     * @param field the field object
     * @param <T> the expected return type
     * @return the field value, or null if failed
     */
    public static <T> T getFieldValue(Object target, Field field) {
        VarHandle handle = getVarHandleFromField(field);
        if (handle == null) {
            return null;
        }
        return (T) handle.get(target);
    }

    // 通过Field对象设置字段值
    /**
     * Set field value using a Field object.
     * This method automatically handles type conversion and provides better error messages.
     * @param target the target object (null for static fields)
     * @param field the field object
     * @param value the value to set
     * @return true if successful, false if failed
     */
    public static boolean setFieldValue(Object target, Field field, Object value) {
        try {
            VarHandle handle = getVarHandleFromField(field);
            if (handle == null) {
                return false;
            }

            // 自动类型转换
            Class<?> fieldType = field.getType();
            Object convertedValue = convertValue(value, fieldType);

            handle.set(target, convertedValue);
            return true;
        } catch (Exception e) {
            // 静默失败，返回false
            return false;
        }
    }

    // 类型转换辅助方法
    /**
     * Convert value to the target type.
     * Handles primitive types and their wrapper classes.
     * @param value the value to convert
     * @param targetType the target type
     * @return the converted value
     */
    private static Object convertValue(Object value, Class<?> targetType) {
        if (value == null) return null;

        // 如果已经是目标类型，直接返回
        if (targetType.isInstance(value)) {
            return value;
        }

        // Number类型的转换
        if (value instanceof Number) {
            Number number = (Number) value;

            if (targetType == float.class || targetType == Float.class) {
                return number.floatValue();
            } else if (targetType == double.class || targetType == Double.class) {
                return number.doubleValue();
            } else if (targetType == int.class || targetType == Integer.class) {
                return number.intValue();
            } else if (targetType == long.class || targetType == Long.class) {
                return number.longValue();
            } else if (targetType == short.class || targetType == Short.class) {
                return number.shortValue();
            } else if (targetType == byte.class || targetType == Byte.class) {
                return number.byteValue();
            }
        }

        // 其他情况直接返回原值
        return value;
    }

    // 批量设置字段值（带类型转换）
    /**
     * Set field value with automatic type conversion.
     * This is a convenience method that combines Field lookup and value setting.
     * @param target the target object
     * @param clazz the class containing the field
     * @param fieldName the field name
     * @param value the value to set
     * @return true if successful, false if failed
     */
    public static boolean setFieldValueByName(Object target, Class<?> clazz, String fieldName, Object value) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            return setFieldValue(target, field, value);
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private VarHandleUtil() {}
}
