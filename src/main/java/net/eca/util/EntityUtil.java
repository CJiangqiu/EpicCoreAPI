package net.eca.util;

import net.eca.config.EcaConfiguration;
import net.eca.network.EcaClientRemovePacket;
import net.eca.network.NetworkHandler;
import net.eca.util.health.HealthFieldCache;
import net.eca.util.health.HealthGetterHook;
import net.eca.util.reflect.ObfuscationMapping;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.entity.*;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.advancements.CriteriaTriggers;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

//实体工具类
public class EntityUtil {

    //VarHandle - 生命值模块
    private static VarHandle DATA_ITEM_VALUE_HANDLE;
    private static VarHandle DATA_ITEM_DIRTY_HANDLE;
    private static Field ITEMS_BY_ID_FIELD;
    private static Field IS_DIRTY_FIELD;

    //VarHandle - 死亡模块
    private static VarHandle DEATH_TIME_HANDLE;
    private static VarHandle DEAD_HANDLE;

    //VarHandle - 实体清除模块（服务端）
    private static VarHandle SERVER_LEVEL_PLAYERS_HANDLE;
    private static VarHandle SERVER_LEVEL_CHUNK_SOURCE_HANDLE;
    private static VarHandle SERVER_LEVEL_ENTITY_TICK_LIST_HANDLE;
    private static VarHandle SERVER_LEVEL_ENTITY_MANAGER_HANDLE;
    private static VarHandle SERVER_LEVEL_NAVIGATING_MOBS_HANDLE;
    private static VarHandle ENTITY_TICK_LIST_ACTIVE_HANDLE;
    private static VarHandle ENTITY_TICK_LIST_PASSIVE_HANDLE;
    private static VarHandle ENTITY_TICK_LIST_ITERATED_HANDLE;
    private static VarHandle SERVER_CHUNK_CACHE_CHUNK_MAP_HANDLE;
    private static VarHandle CHUNK_MAP_ENTITY_MAP_HANDLE;
    private static VarHandle PERSISTENT_ENTITY_MANAGER_VISIBLE_STORAGE_HANDLE;
    private static VarHandle PERSISTENT_ENTITY_MANAGER_KNOWN_UUIDS_HANDLE;
    private static VarHandle PERSISTENT_ENTITY_MANAGER_SECTION_STORAGE_HANDLE;
    private static VarHandle ENTITY_LOOKUP_BY_UUID_HANDLE;
    private static VarHandle ENTITY_LOOKUP_BY_ID_HANDLE;
    private static VarHandle ENTITY_SECTION_STORAGE_SECTIONS_HANDLE;
    private static VarHandle ENTITY_SECTION_STORAGE_HANDLE;
    private static VarHandle CLASS_INSTANCE_MULTI_MAP_BY_CLASS_HANDLE;

    //VarHandle - 实体清除模块（客户端）- 懒加载
    private static volatile boolean clientVarHandlesInitialized = false;
    private static VarHandle CLIENT_LEVEL_TICKING_ENTITIES_HANDLE;
    private static VarHandle CLIENT_LEVEL_ENTITY_STORAGE_HANDLE;
    private static VarHandle CLIENT_LEVEL_PLAYERS_HANDLE;
    private static VarHandle TRANSIENT_ENTITY_MANAGER_ENTITY_STORAGE_HANDLE;
    private static VarHandle TRANSIENT_ENTITY_MANAGER_SECTION_STORAGE_HANDLE;

    //生命值关键词白名单
    private static final Set<String> HEALTH_KEYWORDS = Set.of("health", "heal", "hp", "life", "vital");

    //生命值修改黑名单
    private static final Set<String> HEALTH_BLACKLIST_KEYWORDS = Set.of(
        "ai", "goal", "target", "brain", "memory", "sensor", "skill", "ability", "spell", "cast",
        "animation", "swing", "cooldown", "duration", "delay", "timer", "tick", "time",
        "age", "lifetime", "deathtime", "hurttime", "invulnerabletime", "hurt",
        "level", "all_things_end", "is_spawned", "allow_moving", "shoot", "texture"
    );

    //VarHandle缓存
    private static final Map<String, VarHandle> VAR_HANDLE_CACHE = new ConcurrentHashMap<>();

    //EntityDataAccessor缓存
    private static final Map<Class<?>, List<EntityDataAccessor<?>>> NEARBY_ACCESSOR_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<EntityDataAccessor<?>>> KEYWORD_ACCESSOR_CACHE = new ConcurrentHashMap<>();

    static {
        try {
            //使用ObfuscationReflectionHelper获取Field，自动处理混淆
            Class<?> dataItemClass = SynchedEntityData.DataItem.class;
            String valueObfName = ObfuscationMapping.getFieldMapping("DataItem.value");
            String dirtyObfName = ObfuscationMapping.getFieldMapping("DataItem.dirty");

            Field valueField = ObfuscationReflectionHelper.findField(dataItemClass, valueObfName);
            Field dirtyField = ObfuscationReflectionHelper.findField(dataItemClass, dirtyObfName);

            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(dataItemClass, MethodHandles.lookup());
            DATA_ITEM_VALUE_HANDLE = lookup.unreflectVarHandle(valueField);
            DATA_ITEM_DIRTY_HANDLE = lookup.unreflectVarHandle(dirtyField);

            //获取itemsById字段
            String itemsByIdObfName = ObfuscationMapping.getFieldMapping("SynchedEntityData.itemsById");
            ITEMS_BY_ID_FIELD = ObfuscationReflectionHelper.findField(SynchedEntityData.class, itemsByIdObfName);

            //获取isDirty字段
            String isDirtyObfName = ObfuscationMapping.getFieldMapping("SynchedEntityData.isDirty");
            IS_DIRTY_FIELD = ObfuscationReflectionHelper.findField(SynchedEntityData.class, isDirtyObfName);

            //死亡模块 VarHandle 初始化
            String deathTimeObfName = ObfuscationMapping.getFieldMapping("LivingEntity.deathTime");
            String deadObfName = ObfuscationMapping.getFieldMapping("LivingEntity.dead");

            Field deathTimeField = ObfuscationReflectionHelper.findField(LivingEntity.class, deathTimeObfName);
            Field deadField = ObfuscationReflectionHelper.findField(LivingEntity.class, deadObfName);

            MethodHandles.Lookup livingEntityLookup = MethodHandles.privateLookupIn(LivingEntity.class, MethodHandles.lookup());
            DEATH_TIME_HANDLE = livingEntityLookup.unreflectVarHandle(deathTimeField);
            DEAD_HANDLE = livingEntityLookup.unreflectVarHandle(deadField);

            //实体清除模块 - 服务端 VarHandle 初始化
            initServerRemovalVarHandles();

        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to initialize VarHandle: {}", e.getMessage());
        }
    }

    //初始化服务端清除模块的 VarHandle
    private static void initServerRemovalVarHandles() throws Exception {
        //ServerLevel
        Field serverLevelPlayersField = ObfuscationReflectionHelper.findField(ServerLevel.class, ObfuscationMapping.getFieldMapping("ServerLevel.players"));
        Field serverLevelChunkSourceField = ObfuscationReflectionHelper.findField(ServerLevel.class, ObfuscationMapping.getFieldMapping("ServerLevel.chunkSource"));
        Field serverLevelEntityTickListField = ObfuscationReflectionHelper.findField(ServerLevel.class, ObfuscationMapping.getFieldMapping("ServerLevel.entityTickList"));
        Field serverLevelEntityManagerField = ObfuscationReflectionHelper.findField(ServerLevel.class, ObfuscationMapping.getFieldMapping("ServerLevel.entityManager"));
        Field serverLevelNavigatingMobsField = ObfuscationReflectionHelper.findField(ServerLevel.class, ObfuscationMapping.getFieldMapping("ServerLevel.navigatingMobs"));

        MethodHandles.Lookup serverLevelLookup = MethodHandles.privateLookupIn(ServerLevel.class, MethodHandles.lookup());
        SERVER_LEVEL_PLAYERS_HANDLE = serverLevelLookup.unreflectVarHandle(serverLevelPlayersField);
        SERVER_LEVEL_CHUNK_SOURCE_HANDLE = serverLevelLookup.unreflectVarHandle(serverLevelChunkSourceField);
        SERVER_LEVEL_ENTITY_TICK_LIST_HANDLE = serverLevelLookup.unreflectVarHandle(serverLevelEntityTickListField);
        SERVER_LEVEL_ENTITY_MANAGER_HANDLE = serverLevelLookup.unreflectVarHandle(serverLevelEntityManagerField);
        SERVER_LEVEL_NAVIGATING_MOBS_HANDLE = serverLevelLookup.unreflectVarHandle(serverLevelNavigatingMobsField);

        //EntityTickList
        Field entityTickListActiveField = ObfuscationReflectionHelper.findField(EntityTickList.class, ObfuscationMapping.getFieldMapping("EntityTickList.active"));
        Field entityTickListPassiveField = ObfuscationReflectionHelper.findField(EntityTickList.class, ObfuscationMapping.getFieldMapping("EntityTickList.passive"));
        Field entityTickListIteratedField = ObfuscationReflectionHelper.findField(EntityTickList.class, ObfuscationMapping.getFieldMapping("EntityTickList.iterated"));

        MethodHandles.Lookup entityTickListLookup = MethodHandles.privateLookupIn(EntityTickList.class, MethodHandles.lookup());
        ENTITY_TICK_LIST_ACTIVE_HANDLE = entityTickListLookup.unreflectVarHandle(entityTickListActiveField);
        ENTITY_TICK_LIST_PASSIVE_HANDLE = entityTickListLookup.unreflectVarHandle(entityTickListPassiveField);
        ENTITY_TICK_LIST_ITERATED_HANDLE = entityTickListLookup.unreflectVarHandle(entityTickListIteratedField);

        //ServerChunkCache
        Field serverChunkCacheChunkMapField = ObfuscationReflectionHelper.findField(ServerChunkCache.class, ObfuscationMapping.getFieldMapping("ServerChunkCache.chunkMap"));
        MethodHandles.Lookup serverChunkCacheLookup = MethodHandles.privateLookupIn(ServerChunkCache.class, MethodHandles.lookup());
        SERVER_CHUNK_CACHE_CHUNK_MAP_HANDLE = serverChunkCacheLookup.unreflectVarHandle(serverChunkCacheChunkMapField);

        //ChunkMap
        Field chunkMapEntityMapField = ObfuscationReflectionHelper.findField(ChunkMap.class, ObfuscationMapping.getFieldMapping("ChunkMap.entityMap"));
        MethodHandles.Lookup chunkMapLookup = MethodHandles.privateLookupIn(ChunkMap.class, MethodHandles.lookup());
        CHUNK_MAP_ENTITY_MAP_HANDLE = chunkMapLookup.unreflectVarHandle(chunkMapEntityMapField);

        //PersistentEntitySectionManager
        Field persistentEntityManagerVisibleStorageField = ObfuscationReflectionHelper.findField(PersistentEntitySectionManager.class, ObfuscationMapping.getFieldMapping("PersistentEntitySectionManager.visibleEntityStorage"));
        Field persistentEntityManagerKnownUuidsField = ObfuscationReflectionHelper.findField(PersistentEntitySectionManager.class, ObfuscationMapping.getFieldMapping("PersistentEntitySectionManager.knownUuids"));
        Field persistentEntityManagerSectionStorageField = ObfuscationReflectionHelper.findField(PersistentEntitySectionManager.class, ObfuscationMapping.getFieldMapping("PersistentEntitySectionManager.sectionStorage"));

        MethodHandles.Lookup persistentEntityManagerLookup = MethodHandles.privateLookupIn(PersistentEntitySectionManager.class, MethodHandles.lookup());
        PERSISTENT_ENTITY_MANAGER_VISIBLE_STORAGE_HANDLE = persistentEntityManagerLookup.unreflectVarHandle(persistentEntityManagerVisibleStorageField);
        PERSISTENT_ENTITY_MANAGER_KNOWN_UUIDS_HANDLE = persistentEntityManagerLookup.unreflectVarHandle(persistentEntityManagerKnownUuidsField);
        PERSISTENT_ENTITY_MANAGER_SECTION_STORAGE_HANDLE = persistentEntityManagerLookup.unreflectVarHandle(persistentEntityManagerSectionStorageField);

        //EntityLookup
        Field entityLookupByUuidField = ObfuscationReflectionHelper.findField(EntityLookup.class, ObfuscationMapping.getFieldMapping("EntityLookup.byUuid"));
        Field entityLookupByIdField = ObfuscationReflectionHelper.findField(EntityLookup.class, ObfuscationMapping.getFieldMapping("EntityLookup.byId"));

        MethodHandles.Lookup entityLookupLookup = MethodHandles.privateLookupIn(EntityLookup.class, MethodHandles.lookup());
        ENTITY_LOOKUP_BY_UUID_HANDLE = entityLookupLookup.unreflectVarHandle(entityLookupByUuidField);
        ENTITY_LOOKUP_BY_ID_HANDLE = entityLookupLookup.unreflectVarHandle(entityLookupByIdField);

        //EntitySectionStorage
        Field entitySectionStorageSectionsField = ObfuscationReflectionHelper.findField(EntitySectionStorage.class, ObfuscationMapping.getFieldMapping("EntitySectionStorage.sections"));
        MethodHandles.Lookup entitySectionStorageLookup = MethodHandles.privateLookupIn(EntitySectionStorage.class, MethodHandles.lookup());
        ENTITY_SECTION_STORAGE_SECTIONS_HANDLE = entitySectionStorageLookup.unreflectVarHandle(entitySectionStorageSectionsField);

        //EntitySection
        Field entitySectionStorageField = ObfuscationReflectionHelper.findField(EntitySection.class, ObfuscationMapping.getFieldMapping("EntitySection.storage"));
        MethodHandles.Lookup entitySectionLookup = MethodHandles.privateLookupIn(EntitySection.class, MethodHandles.lookup());
        ENTITY_SECTION_STORAGE_HANDLE = entitySectionLookup.unreflectVarHandle(entitySectionStorageField);

        //ClassInstanceMultiMap
        Field classInstanceMultiMapByClassField = ObfuscationReflectionHelper.findField(ClassInstanceMultiMap.class, ObfuscationMapping.getFieldMapping("ClassInstanceMultiMap.byClass"));
        MethodHandles.Lookup classInstanceMultiMapLookup = MethodHandles.privateLookupIn(ClassInstanceMultiMap.class, MethodHandles.lookup());
        CLASS_INSTANCE_MULTI_MAP_BY_CLASS_HANDLE = classInstanceMultiMapLookup.unreflectVarHandle(classInstanceMultiMapByClassField);
    }

    //初始化客户端清除模块的 VarHandle（懒加载，只在客户端环境调用）
    private static void initClientRemovalVarHandles() {
        if (clientVarHandlesInitialized) return;
        synchronized (EntityUtil.class) {
            if (clientVarHandlesInitialized) return;
            try {
                //ClientLevel
                Field clientLevelTickingEntitiesField = ObfuscationReflectionHelper.findField(ClientLevel.class, ObfuscationMapping.getFieldMapping("ClientLevel.tickingEntities"));
                Field clientLevelEntityStorageField = ObfuscationReflectionHelper.findField(ClientLevel.class, ObfuscationMapping.getFieldMapping("ClientLevel.entityStorage"));
                Field clientLevelPlayersField = ObfuscationReflectionHelper.findField(ClientLevel.class, ObfuscationMapping.getFieldMapping("ClientLevel.players"));

                MethodHandles.Lookup clientLevelLookup = MethodHandles.privateLookupIn(ClientLevel.class, MethodHandles.lookup());
                CLIENT_LEVEL_TICKING_ENTITIES_HANDLE = clientLevelLookup.unreflectVarHandle(clientLevelTickingEntitiesField);
                CLIENT_LEVEL_ENTITY_STORAGE_HANDLE = clientLevelLookup.unreflectVarHandle(clientLevelEntityStorageField);
                CLIENT_LEVEL_PLAYERS_HANDLE = clientLevelLookup.unreflectVarHandle(clientLevelPlayersField);

                //TransientEntitySectionManager
                Field transientEntityManagerEntityStorageField = ObfuscationReflectionHelper.findField(TransientEntitySectionManager.class, ObfuscationMapping.getFieldMapping("TransientEntitySectionManager.entityStorage"));
                Field transientEntityManagerSectionStorageField = ObfuscationReflectionHelper.findField(TransientEntitySectionManager.class, ObfuscationMapping.getFieldMapping("TransientEntitySectionManager.sectionStorage"));

                MethodHandles.Lookup transientEntityManagerLookup = MethodHandles.privateLookupIn(TransientEntitySectionManager.class, MethodHandles.lookup());
                TRANSIENT_ENTITY_MANAGER_ENTITY_STORAGE_HANDLE = transientEntityManagerLookup.unreflectVarHandle(transientEntityManagerEntityStorageField);
                TRANSIENT_ENTITY_MANAGER_SECTION_STORAGE_HANDLE = transientEntityManagerLookup.unreflectVarHandle(transientEntityManagerSectionStorageField);

                clientVarHandlesInitialized = true;
                EcaLogger.info("[EntityUtil] Client VarHandles initialized successfully");
            } catch (Exception e) {
                EcaLogger.info("[EntityUtil] Failed to initialize client VarHandles: {}", e.getMessage());
            }
        }
    }

    //获取实体真实生命值
    public static float getHealth(LivingEntity entity) {
        if (entity == null) return 0.0f;
        try {
            SynchedEntityData.DataItem<?> dataItem = getDataItem(entity.getEntityData(), LivingEntity.DATA_HEALTH_ID.getId());
            if (dataItem == null || DATA_ITEM_VALUE_HANDLE == null) {
                return entity.getHealth();
            }
            return (Float) DATA_ITEM_VALUE_HANDLE.get(dataItem);
        } catch (Exception e) {
            return entity.getHealth();
        }
    }

    //获取DataItem
    @SuppressWarnings("unchecked")
    private static SynchedEntityData.DataItem<?> getDataItem(SynchedEntityData entityData, int id) {
        try {
            if (ITEMS_BY_ID_FIELD == null) return null;
            Object itemsById = ITEMS_BY_ID_FIELD.get(entityData);
            if (itemsById instanceof it.unimi.dsi.fastutil.ints.Int2ObjectMap) {
                return (SynchedEntityData.DataItem<?>) ((it.unimi.dsi.fastutil.ints.Int2ObjectMap<?>) itemsById).get(id);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    //设置实体生命值（完整阶段流程）
    public static boolean setHealth(LivingEntity entity, float expectedHealth) {
        if (entity == null) return false;
        try {
            //阶段1：修改原版血量
            setBasicHealth(entity, expectedHealth);

            //玩家只执行阶段1
            if (entity instanceof Player) return true;

            //阶段2：修改符合条件的EntityDataAccessor和实例字段
            setHealthViaPhase2(entity, expectedHealth);

            //阶段2.5：激进模式
            if (EcaConfiguration.getAttackEnableRadicalLogicSafely()) {
                scanAndModifyAllInstanceFields(entity, expectedHealth);
            }

            //验证是否成功
            if (verifyHealthChange(entity, expectedHealth)) return true;

            //阶段3：字节码反向追踪
            setHealthViaPhase3(entity, expectedHealth);

            return verifyHealthChange(entity, expectedHealth);
        } catch (Exception e) {
            return false;
        }
    }

    //验证血量修改是否成功
    private static boolean verifyHealthChange(LivingEntity entity, float expectedHealth) {
        try {
            float actualHealth = entity.getHealth();
            return Math.abs(actualHealth - expectedHealth) <= 10.0f;
        } catch (Exception e) {
            return false;
        }
    }

    //阶段1：设置原版血量
    private static void setBasicHealth(LivingEntity entity, float expectedHealth) {
        try {
            if (DATA_ITEM_VALUE_HANDLE == null || DATA_ITEM_DIRTY_HANDLE == null) return;

            SynchedEntityData entityData = entity.getEntityData();
            SynchedEntityData.DataItem<?> dataItem = getDataItem(entityData, LivingEntity.DATA_HEALTH_ID.getId());
            if (dataItem == null) return;

            DATA_ITEM_VALUE_HANDLE.set(dataItem, expectedHealth);
            entity.onSyncedDataUpdated(LivingEntity.DATA_HEALTH_ID);
            DATA_ITEM_DIRTY_HANDLE.set(dataItem, true);
            setIsDirty(entityData, true);
        } catch (Exception ignored) {}
    }

    //同步原版DATA_HEALTH_ID
    private static void syncDataHealthId(LivingEntity entity, float expectedHealth) {
        try {
            if (DATA_ITEM_VALUE_HANDLE == null || DATA_ITEM_DIRTY_HANDLE == null) return;

            SynchedEntityData entityData = entity.getEntityData();
            SynchedEntityData.DataItem<?> dataItem = getDataItem(entityData, LivingEntity.DATA_HEALTH_ID.getId());
            if (dataItem == null) return;

            DATA_ITEM_VALUE_HANDLE.set(dataItem, expectedHealth);
            entity.onSyncedDataUpdated(LivingEntity.DATA_HEALTH_ID);
            DATA_ITEM_DIRTY_HANDLE.set(dataItem, true);
            setIsDirty(entityData, true);
        } catch (Exception ignored) {}
    }

    //设置isDirty标记
    private static void setIsDirty(SynchedEntityData entityData, boolean dirty) {
        try {
            if (IS_DIRTY_FIELD != null) {
                IS_DIRTY_FIELD.set(entityData, dirty);
            }
        } catch (Exception ignored) {}
    }

    //阶段2：修改符合条件的EntityDataAccessor和字段
    private static void setHealthViaPhase2(LivingEntity entity, float expectedHealth) {
        try {
            float currentHealth = entity.getHealth();

            //修改数值近似的EntityDataAccessor
            for (EntityDataAccessor<?> acc : findNearbyNumericAccessors(entity)) {
                setAccessorValue(entity, acc, expectedHealth);
            }

            //修改关键词匹配的EntityDataAccessor
            for (EntityDataAccessor<?> acc : findHealthKeywordAccessors(entity)) {
                setAccessorValue(entity, acc, expectedHealth);
            }

            //扫描并修改符合条件的实例字段
            scanAndModifyIntelligentFields(entity, currentHealth, expectedHealth);
        } catch (Exception ignored) {}
    }

    //扫描并修改符合智能条件的字段
    private static void scanAndModifyIntelligentFields(LivingEntity entity, float currentHealth, float expectedHealth) {
        Set<Object> scannedObjects = new HashSet<>();
        scanAndModifyIntelligentFieldsRecursive(entity, entity.getClass(), currentHealth, expectedHealth, scannedObjects, 0);
    }

    //递归扫描并修改符合条件的字段
    private static void scanAndModifyIntelligentFieldsRecursive(Object targetObject, Class<?> targetClass,
                                                                float currentHealth, float expectedHealth,
                                                                Set<Object> scannedObjects, int nestingLevel) {
        if (nestingLevel > 3) return;
        if (nestingLevel > 0 && scannedObjects.contains(targetObject)) return;
        if (nestingLevel > 0) scannedObjects.add(targetObject);

        try {
            Class<?> currentClass = targetClass;
            int inheritanceLevel = 0;

            while (currentClass != null && inheritanceLevel < 5) {
                for (Field field : currentClass.getDeclaredFields()) {
                    try {
                        if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
                        if (matchesHealthBlacklist(field.getName())) continue;

                        field.setAccessible(true);
                        Class<?> fieldType = field.getType();

                        if (fieldType == float.class || fieldType == Float.class ||
                            fieldType == double.class || fieldType == Double.class ||
                            fieldType == int.class || fieldType == Integer.class) {

                            Object value = field.get(targetObject);
                            if (value instanceof Number) {
                                float fieldValue = ((Number) value).floatValue();
                                if (Math.abs(fieldValue - currentHealth) <= 10.0f || matchesHealthWhitelist(field.getName())) {
                                    setFieldViaVarHandle(targetObject, field, expectedHealth);
                                }
                            }
                        } else if (!fieldType.isPrimitive() && !fieldType.getName().startsWith("java.") &&
                                   !fieldType.getName().startsWith("net.minecraft.")) {
                            Object nestedObject = field.get(targetObject);
                            if (nestedObject != null && !scannedObjects.contains(nestedObject)) {
                                scanAndModifyIntelligentFieldsRecursive(nestedObject, nestedObject.getClass(),
                                    currentHealth, expectedHealth, scannedObjects, nestingLevel + 1);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                currentClass = currentClass.getSuperclass();
                inheritanceLevel++;
                if (currentClass == LivingEntity.class) break;
            }
        } catch (Exception ignored) {}
    }

    //使用VarHandle设置字段值
    private static boolean setFieldViaVarHandle(Object targetObject, Field field, float value) {
        try {
            Class<?> fieldType = field.getType();
            Class<?> declaringClass = field.getDeclaringClass();

            if (fieldType == float.class || fieldType == Float.class) {
                return setFloatFieldViaVarHandle(targetObject, declaringClass, field, value);
            } else if (fieldType == double.class || fieldType == Double.class) {
                return setDoubleFieldViaVarHandle(targetObject, declaringClass, field, (double) value);
            } else if (fieldType == int.class || fieldType == Integer.class) {
                return setIntFieldViaVarHandle(targetObject, declaringClass, field, (int) value);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    //阶段2.5：扫描并修改所有实例字段
    private static int scanAndModifyAllInstanceFields(LivingEntity entity, float expectedHealth) {
        Set<Object> scannedObjects = new HashSet<>();
        int totalModified = 0;
        float currentHealth = entity.getHealth();

        Class<?> currentClass = entity.getClass();
        int inheritanceLevel = 0;

        while (currentClass != null && inheritanceLevel < 5) {
            totalModified += scanAndModifyFieldsInClass(entity, currentClass, currentHealth, expectedHealth, scannedObjects, 0);
            currentClass = currentClass.getSuperclass();
            inheritanceLevel++;
            if (currentClass == LivingEntity.class) break;
        }

        return totalModified;
    }

    //扫描并修改指定类的所有数值字段
    private static int scanAndModifyFieldsInClass(Object targetObject, Class<?> targetClass,
                                                  float currentHealth, float expectedHealth,
                                                  Set<Object> scannedObjects, int nestingLevel) {
        if (nestingLevel > 3) return 0;
        if (nestingLevel > 0 && scannedObjects.contains(targetObject)) return 0;
        if (nestingLevel > 0) scannedObjects.add(targetObject);

        int modifiedCount = 0;

        try {
            for (Field field : targetClass.getDeclaredFields()) {
                try {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;

                    Class<?> fieldType = field.getType();
                    field.setAccessible(true);

                    if (fieldType == double.class || fieldType == Double.class) {
                        if (shouldModifyNumericField(targetObject, field, currentHealth)) {
                            if (setDoubleFieldViaVarHandle(targetObject, field.getDeclaringClass(), field, (double) expectedHealth)) {
                                modifiedCount++;
                            }
                        }
                    } else if (fieldType == float.class || fieldType == Float.class) {
                        if (shouldModifyNumericField(targetObject, field, currentHealth)) {
                            if (setFloatFieldViaVarHandle(targetObject, field.getDeclaringClass(), field, expectedHealth)) {
                                modifiedCount++;
                            }
                        }
                    } else if (fieldType == int.class || fieldType == Integer.class) {
                        if (shouldModifyNumericField(targetObject, field, currentHealth)) {
                            if (setIntFieldViaVarHandle(targetObject, field.getDeclaringClass(), field, (int) expectedHealth)) {
                                modifiedCount++;
                            }
                        }
                    } else if (!fieldType.isPrimitive() && !fieldType.getName().startsWith("java.") &&
                               !fieldType.getName().startsWith("net.minecraft.")) {
                        Object nestedObject = field.get(targetObject);
                        if (nestedObject != null && !scannedObjects.contains(nestedObject)) {
                            modifiedCount += scanAndModifyFieldsInClass(nestedObject, nestedObject.getClass(),
                                currentHealth, expectedHealth, scannedObjects, nestingLevel + 1);
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        return modifiedCount;
    }

    //判断是否应该修改数值字段
    private static boolean shouldModifyNumericField(Object targetObject, Field field, float currentHealth) {
        try {
            if (matchesHealthBlacklist(field.getName())) return false;
            Object currentValue = field.get(targetObject);
            return currentValue instanceof Number;
        } catch (Exception e) {
            return false;
        }
    }

    //VarHandle设置float字段
    private static boolean setFloatFieldViaVarHandle(Object targetObject, Class<?> targetClass, Field field, float value) {
        try {
            String cacheKey = targetClass.getName() + "#" + field.getName() + "#float";
            VarHandle handle = VAR_HANDLE_CACHE.computeIfAbsent(cacheKey, k -> {
                try {
                    return MethodHandles.privateLookupIn(targetClass, MethodHandles.lookup())
                        .findVarHandle(targetClass, field.getName(), float.class);
                } catch (Exception e) {
                    return null;
                }
            });
            if (handle != null) {
                handle.set(targetObject, value);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    //VarHandle设置double字段
    private static boolean setDoubleFieldViaVarHandle(Object targetObject, Class<?> targetClass, Field field, double value) {
        try {
            String cacheKey = targetClass.getName() + "#" + field.getName() + "#double";
            VarHandle handle = VAR_HANDLE_CACHE.computeIfAbsent(cacheKey, k -> {
                try {
                    return MethodHandles.privateLookupIn(targetClass, MethodHandles.lookup())
                        .findVarHandle(targetClass, field.getName(), double.class);
                } catch (Exception e) {
                    return null;
                }
            });
            if (handle != null) {
                handle.set(targetObject, value);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    //VarHandle设置int字段
    private static boolean setIntFieldViaVarHandle(Object targetObject, Class<?> targetClass, Field field, int value) {
        try {
            String cacheKey = targetClass.getName() + "#" + field.getName() + "#int";
            VarHandle handle = VAR_HANDLE_CACHE.computeIfAbsent(cacheKey, k -> {
                try {
                    return MethodHandles.privateLookupIn(targetClass, MethodHandles.lookup())
                        .findVarHandle(targetClass, field.getName(), int.class);
                } catch (Exception e) {
                    return null;
                }
            });
            if (handle != null) {
                handle.set(targetObject, value);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    //查找数值接近生命值的数据访问器
    private static List<EntityDataAccessor<?>> findNearbyNumericAccessors(LivingEntity entity) {
        Class<?> entityClass = entity.getClass();
        List<EntityDataAccessor<?>> cached = NEARBY_ACCESSOR_CACHE.get(entityClass);
        if (cached != null) return cached;

        List<EntityDataAccessor<?>> result = new ArrayList<>();
        float entityHealth = entity.getHealth();
        int vanillaHealthId = LivingEntity.DATA_HEALTH_ID.getId();

        for (Class<?> clazz = entityClass; clazz != null && clazz != Entity.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    if (!EntityDataAccessor.class.isAssignableFrom(field.getType())) continue;
                    if (!Modifier.isStatic(field.getModifiers())) continue;

                    EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) field.get(null);
                    if (accessor.getId() == vanillaHealthId) continue;

                    Object value = entity.getEntityData().get(accessor);
                    if (value instanceof Number) {
                        float numericValue = ((Number) value).floatValue();
                        if (Math.abs(numericValue - entityHealth) <= 10.0f) {
                            result.add(accessor);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        NEARBY_ACCESSOR_CACHE.put(entityClass, result);
        return result;
    }

    //查找包含生命值关键词的数据访问器
    private static List<EntityDataAccessor<?>> findHealthKeywordAccessors(LivingEntity entity) {
        Class<?> entityClass = entity.getClass();
        List<EntityDataAccessor<?>> cached = KEYWORD_ACCESSOR_CACHE.get(entityClass);
        if (cached != null) return cached;

        List<EntityDataAccessor<?>> result = new ArrayList<>();
        int vanillaHealthId = LivingEntity.DATA_HEALTH_ID.getId();

        for (Class<?> clazz = entityClass; clazz != null && clazz != Entity.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    if (!EntityDataAccessor.class.isAssignableFrom(field.getType())) continue;
                    if (!Modifier.isStatic(field.getModifiers())) continue;

                    EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) field.get(null);
                    if (accessor.getId() == vanillaHealthId) continue;

                    if (matchesHealthWhitelist(field.getName())) {
                        Object value = entity.getEntityData().get(accessor);
                        if (value instanceof Number) {
                            result.add(accessor);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        KEYWORD_ACCESSOR_CACHE.put(entityClass, result);
        return result;
    }

    //设置EntityDataAccessor的值
    @SuppressWarnings("unchecked")
    private static void setAccessorValue(LivingEntity entity, EntityDataAccessor<?> accessor, float expectedHealth) {
        try {
            if (accessor.getSerializer() == EntityDataSerializers.FLOAT) {
                entity.getEntityData().set((EntityDataAccessor<Float>) accessor, expectedHealth);
            } else if (accessor.getSerializer() == EntityDataSerializers.INT) {
                entity.getEntityData().set((EntityDataAccessor<Integer>) accessor, (int) expectedHealth);
            }
        } catch (Exception ignored) {}
    }

    //阶段3：字节码反向追踪修改血量
    private static void setHealthViaPhase3(LivingEntity entity, float expectedHealth) {
        try {
            Class<?> entityClass = entity.getClass();
            HealthFieldCache cache = HealthGetterHook.getCache(entityClass);

            if (cache == null) {
                HealthGetterHook.triggerAnalysis(entity);
                cache = HealthGetterHook.getCache(entityClass);
                if (cache == null) return;
            }

            //应用逆向公式
            float valueToWrite = expectedHealth;
            if (cache.reverseTransform != null) {
                valueToWrite = cache.reverseTransform.apply(Math.max(0.0f, expectedHealth));
            }

            //容器检测
            if (cache.containerDetected) {
                scanAndModifyHealthContainer(entity, valueToWrite, cache);
                syncDataHealthId(entity, expectedHealth);
                return;
            }

            //字段访问路径
            if (cache.writePath != null) {
                cache.writePath.apply(entity, valueToWrite);
                syncDataHealthId(entity, expectedHealth);
            }
        } catch (Exception ignored) {}
    }

    //主动扫描容器修改血量
    private static void scanAndModifyHealthContainer(LivingEntity entity, float expectedHealth, HealthFieldCache cache) {
        try {
            Class<?> containerClass = Class.forName(cache.containerClass);
            java.lang.reflect.Method getterMethod = containerClass.getDeclaredMethod(cache.containerGetterMethod);
            getterMethod.setAccessible(true);

            Object mapInstance = getterMethod.invoke(null);
            if (!(mapInstance instanceof Map)) return;

            Map<?, ?> healthMap = (Map<?, ?>) mapInstance;

            if (healthMap.containsKey(entity)) {
                modifyMapValueViaVarHandle(healthMap, entity, expectedHealth);
                return;
            }

            Object[] possibleKeys = new Object[]{entity, entity.getUUID(), entity.getId()};
            for (Object key : possibleKeys) {
                if (key != null && healthMap.containsKey(key)) {
                    modifyMapValueViaVarHandle(healthMap, key, expectedHealth);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    //使用VarHandle修改Map中的值
    private static boolean modifyMapValueViaVarHandle(Map<?, ?> map, Object key, float newValue) {
        try {
            if (map instanceof WeakHashMap) {
                return modifyWeakHashMapValue((WeakHashMap<?, ?>) map, key, newValue);
            } else if (map instanceof HashMap) {
                return modifyHashMapValue((HashMap<?, ?>) map, key, newValue);
            }
        } catch (Exception ignored) {}
        return false;
    }

    //修改HashMap的值
    private static boolean modifyHashMapValue(HashMap<?, ?> map, Object key, float newValue) {
        try {
            HealthGetterHook.initHashMapVarHandles(map.getClass());
            Object[] table = (Object[]) HealthGetterHook.getHashMapTable(map);
            if (table == null) return false;

            for (Object node : table) {
                while (node != null) {
                    Object nodeKey = HealthGetterHook.getHashMapNodeKey(node);
                    if (key.equals(nodeKey) || key == nodeKey) {
                        HealthGetterHook.setHashMapNodeValue(node, newValue);
                        return true;
                    }
                    node = HealthGetterHook.getHashMapNodeNext(node);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    //修改WeakHashMap的值
    private static boolean modifyWeakHashMapValue(WeakHashMap<?, ?> map, Object key, float newValue) {
        try {
            HealthGetterHook.initHashMapVarHandles(map.getClass());
            Object[] table = (Object[]) HealthGetterHook.getWeakHashMapTable(map);
            if (table == null) return false;

            for (Object entry : table) {
                while (entry != null) {
                    Object entryKey = HealthGetterHook.getWeakHashMapEntryKey(entry);
                    if (key.equals(entryKey) || key == entryKey) {
                        HealthGetterHook.setWeakHashMapEntryValue(entry, newValue);
                        return true;
                    }
                    entry = HealthGetterHook.getWeakHashMapEntryNext(entry);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    //检查字段名是否匹配健康白名单
    private static boolean matchesHealthWhitelist(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) return false;
        String lowerName = fieldName.toLowerCase();
        for (String keyword : HEALTH_KEYWORDS) {
            if (lowerName.contains(keyword)) return true;
        }
        return false;
    }

    //检查字段名是否匹配黑名单
    private static boolean matchesHealthBlacklist(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) return false;
        String lowerName = fieldName.toLowerCase();
        for (String keyword : HEALTH_BLACKLIST_KEYWORDS) {
            if (lowerName.contains(keyword)) return true;
        }
        return false;
    }

    // ==================== 实体死亡模块 ====================

    //设置实体死亡状态
    public static void setDead(LivingEntity entity, DamageSource damageSource) {
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

            //调用原版die方法
            entity.die(damageSource);
            entity.setPose(Pose.DYING);

            //触发击杀成就
            triggerKillAdvancement(entity, damageSource);

            //掉落物品
            entity.dropAllDeathLoot(damageSource);

            //设置死亡字段
            setDeathFieldsViaVarHandle(entity);

            //清除实体
            removeEntity(entity, Entity.RemovalReason.KILLED);
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to set entity dead: {}", e.getMessage());
        }
    }

    //复活实体（清除死亡状态）
    public static void reviveEntity(LivingEntity entity) {
        if (entity == null) return;

        try {
            if (DEAD_HANDLE != null) {
                DEAD_HANDLE.set(entity, false);
            }
            if (DEATH_TIME_HANDLE != null) {
                DEATH_TIME_HANDLE.set(entity, 0);
            }
            setHealth(entity, entity.getMaxHealth());
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to revive entity: {}", e.getMessage());
        }
    }

    //使用VarHandle设置死亡相关字段
    private static void setDeathFieldsViaVarHandle(LivingEntity entity) {
        try {
            if (DEAD_HANDLE != null) {
                DEAD_HANDLE.set(entity, true);
            }
            if (DEATH_TIME_HANDLE != null) {
                DEATH_TIME_HANDLE.set(entity, 0);
            }
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to set death fields: {}", e.getMessage());
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
    public static void removeEntity(Entity entity, Entity.RemovalReason reason) {
        if (entity == null) return;

        try {
            cleanupAI(entity);
            cleanupBossBar(entity);

            if (!entity.level().isClientSide) {
                entity.onRemovedFromWorld();
            }

            entity.setRemoved(reason);
            entity.stopRiding();
            removeAllPassengers(entity);

            if (!entity.level().isClientSide) {
                entity.levelCallback = EntityInLevelCallback.NULL;
            }

            if (!entity.level().isClientSide && entity.level() instanceof ServerLevel serverLevel) {
                // Send removal packet to tracking clients
                NetworkHandler.sendToTrackingClients(
                        new EcaClientRemovePacket(entity.getId()),
                        entity
                );

                removeFromServerContainers(serverLevel, entity);
            } else if (entity.level().isClientSide && entity.level() instanceof ClientLevel clientLevel) {
                removeFromClientContainers(clientLevel, entity);
            }
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Entity removal failed: {}", e.getMessage());
        }
    }

    //AI系统清理
    private static void cleanupAI(Entity entity) {
        if (entity instanceof Mob mob) {
            if (mob.goalSelector != null) {
                mob.goalSelector.removeAllGoals(goal -> true);
            }
            if (mob.targetSelector != null) {
                mob.targetSelector.removeAllGoals(goal -> true);
            }
            mob.setTarget(null);
            if (mob.getNavigation() != null) {
                mob.getNavigation().stop();
            }
        }
    }

    //Boss血条清理
    public static void cleanupBossBar(Entity entity) {
        if (entity == null) return;

        for (Class<?> clazz = entity.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;

                field.setAccessible(true);
                try {
                    Object fieldValue = field.get(entity);
                    if (fieldValue instanceof ServerBossEvent serverBossEvent) {
                        serverBossEvent.removeAllPlayers();
                        serverBossEvent.setVisible(false);
                    }
                } catch (IllegalAccessException ignored) {}
            }
        }
    }

    //清除所有乘客的骑乘关系
    private static void removeAllPassengers(Entity entity) {
        List<Entity> passengers = entity.getPassengers();
        for (int i = passengers.size() - 1; i >= 0; i--) {
            Entity passenger = passengers.get(i);
            if (passenger != null) {
                passenger.stopRiding();
            }
        }
    }

    //底层容器清除 - 服务端（顺序很重要）
    private static void removeFromServerContainers(ServerLevel serverLevel, Entity entity) {
        int entityId = entity.getId();
        UUID entityUUID = entity.getUUID();

        try {
            //1. ChunkMap
            removeFromChunkMapEntityMap(serverLevel, entityId);
            //2. ServerLevel Lists (players/navigatingMobs)
            removeFromPlayersOrMobs(serverLevel, entity);
            //3. EntityLookup (byUuid + byId)
            removeFromEntityLookup(serverLevel, entityId, entityUUID);
            //4. KnownUuids
            removeFromKnownUuids(serverLevel, entityUUID);
            //5. EntityTickList (active + passive)
            removeFromEntityTickList(serverLevel, entityId);
            //6. EntitySectionStorage (ClassInstanceMultiMap)
            removeFromEntitySectionStorage(serverLevel, entity);
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to remove from server containers: {}", e.getMessage());
        }
    }

    //从 EntityTickList.active 和 passive 移除（使用双缓冲机制）
    @SuppressWarnings("unchecked")
    private static void removeFromEntityTickList(ServerLevel serverLevel, int entityId) {
        try {
            if (SERVER_LEVEL_ENTITY_TICK_LIST_HANDLE == null) return;

            Object entityTickList = SERVER_LEVEL_ENTITY_TICK_LIST_HANDLE.get(serverLevel);
            if (entityTickList == null) return;

            //获取 active、passive、iterated 字段
            Int2ObjectLinkedOpenHashMap<Entity> active = (Int2ObjectLinkedOpenHashMap<Entity>) ENTITY_TICK_LIST_ACTIVE_HANDLE.get(entityTickList);
            Int2ObjectLinkedOpenHashMap<Entity> passive = (Int2ObjectLinkedOpenHashMap<Entity>) ENTITY_TICK_LIST_PASSIVE_HANDLE.get(entityTickList);

            if (active == null || passive == null) return;

            //检查是否正在迭代 active
            Object iterated = ENTITY_TICK_LIST_ITERATED_HANDLE.get(entityTickList);

            if (iterated == active) {
                //正在迭代 active，使用双缓冲切换
                passive.clear();

                //复制 active 到 passive（排除要删除的实体）
                for (Int2ObjectMap.Entry<Entity> entry : active.int2ObjectEntrySet()) {
                    int id = entry.getIntKey();
                    if (id != entityId) {
                        passive.put(id, entry.getValue());
                    }
                }

                //交换 active 和 passive
                ENTITY_TICK_LIST_ACTIVE_HANDLE.set(entityTickList, passive);
                ENTITY_TICK_LIST_PASSIVE_HANDLE.set(entityTickList, active);
            } else {
                //未在迭代，直接删除
                active.remove(entityId);
            }
        } catch (Exception ignored) {}
    }

    //从 EntityLookup (byUuid + byId) 移除
    @SuppressWarnings("unchecked")
    private static void removeFromEntityLookup(ServerLevel serverLevel, int entityId, UUID entityUUID) {
        try {
            if (SERVER_LEVEL_ENTITY_MANAGER_HANDLE == null) return;

            Object entityManager = SERVER_LEVEL_ENTITY_MANAGER_HANDLE.get(serverLevel);
            if (entityManager == null) return;

            Object visibleEntityStorage = PERSISTENT_ENTITY_MANAGER_VISIBLE_STORAGE_HANDLE.get(entityManager);
            if (visibleEntityStorage == null) return;

            //移除 byUuid
            Map<UUID, Entity> byUuid = (Map<UUID, Entity>) ENTITY_LOOKUP_BY_UUID_HANDLE.get(visibleEntityStorage);
            if (byUuid != null) {
                byUuid.remove(entityUUID);
            }

            //移除 byId
            Int2ObjectLinkedOpenHashMap<Entity> byId = (Int2ObjectLinkedOpenHashMap<Entity>) ENTITY_LOOKUP_BY_ID_HANDLE.get(visibleEntityStorage);
            if (byId != null) {
                byId.remove(entityId);
            }
        } catch (Exception ignored) {}
    }

    //从 EntitySectionStorage → EntitySection.storage 移除
    @SuppressWarnings("unchecked")
    private static void removeFromEntitySectionStorage(ServerLevel serverLevel, Entity entity) {
        try {
            if (SERVER_LEVEL_ENTITY_MANAGER_HANDLE == null) return;

            Object entityManager = SERVER_LEVEL_ENTITY_MANAGER_HANDLE.get(serverLevel);
            if (entityManager == null) return;

            Object sectionStorage = PERSISTENT_ENTITY_MANAGER_SECTION_STORAGE_HANDLE.get(entityManager);
            if (sectionStorage == null) return;

            Long2ObjectMap<?> sections = (Long2ObjectMap<?>) ENTITY_SECTION_STORAGE_SECTIONS_HANDLE.get(sectionStorage);
            if (sections == null) return;

            //遍历所有 EntitySection
            for (Object section : sections.values()) {
                if (section == null) continue;

                Object storage = ENTITY_SECTION_STORAGE_HANDLE.get(section);
                if (storage == null) continue;

                //获取 ClassInstanceMultiMap.byClass
                Map<Class<?>, List<?>> byClass = (Map<Class<?>, List<?>>) CLASS_INSTANCE_MULTI_MAP_BY_CLASS_HANDLE.get(storage);
                if (byClass == null) continue;

                //遍历所有类型的 List，移除匹配的实体
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

    //从 ChunkMap.entityMap 移除
    @SuppressWarnings("unchecked")
    private static void removeFromChunkMapEntityMap(ServerLevel serverLevel, int entityId) {
        try {
            if (SERVER_LEVEL_CHUNK_SOURCE_HANDLE == null) return;

            Object chunkSource = SERVER_LEVEL_CHUNK_SOURCE_HANDLE.get(serverLevel);
            if (chunkSource == null) return;

            Object chunkMap = SERVER_CHUNK_CACHE_CHUNK_MAP_HANDLE.get(chunkSource);
            if (chunkMap == null) return;

            Int2ObjectOpenHashMap<?> entityMap = (Int2ObjectOpenHashMap<?>) CHUNK_MAP_ENTITY_MAP_HANDLE.get(chunkMap);
            if (entityMap != null) {
                entityMap.remove(entityId);
            }
        } catch (Exception ignored) {}
    }

    //从 PersistentEntitySectionManager.knownUuids 移除
    @SuppressWarnings("unchecked")
    private static void removeFromKnownUuids(ServerLevel serverLevel, UUID entityUUID) {
        try {
            if (SERVER_LEVEL_ENTITY_MANAGER_HANDLE == null) return;

            Object entityManager = SERVER_LEVEL_ENTITY_MANAGER_HANDLE.get(serverLevel);
            if (entityManager == null) return;

            Set<UUID> knownUuids = (Set<UUID>) PERSISTENT_ENTITY_MANAGER_KNOWN_UUIDS_HANDLE.get(entityManager);
            if (knownUuids != null) {
                knownUuids.remove(entityUUID);
            }
        } catch (Exception ignored) {}
    }

    //从 ServerLevel.players 或 navigatingMobs 移除
    @SuppressWarnings("unchecked")
    private static void removeFromPlayersOrMobs(ServerLevel serverLevel, Entity entity) {
        try {
            //如果是玩家，从 players 列表移除
            if (entity instanceof ServerPlayer) {
                List<ServerPlayer> players = (List<ServerPlayer>) SERVER_LEVEL_PLAYERS_HANDLE.get(serverLevel);
                if (players != null) {
                    players.remove(entity);
                }
            }

            //如果是 Mob，从 navigatingMobs 集合移除
            if (entity instanceof Mob) {
                ObjectOpenHashSet<Mob> navigatingMobs = (ObjectOpenHashSet<Mob>) SERVER_LEVEL_NAVIGATING_MOBS_HANDLE.get(serverLevel);
                if (navigatingMobs != null) {
                    navigatingMobs.remove(entity);
                }
            }
        } catch (Exception ignored) {}
    }

    //底层容器清除 - 客户端
    public static void removeFromClientContainers(ClientLevel clientLevel, Entity entity) {
        //懒加载初始化客户端VarHandle
        initClientRemovalVarHandles();

        int entityId = entity.getId();
        UUID entityUUID = entity.getUUID();

        try {
            //高优先级容器
            removeFromClientEntityTickList(clientLevel, entityId);
            removeFromClientEntityLookup(clientLevel, entityId, entityUUID);
            removeFromClientEntitySectionStorage(clientLevel, entity);

            //中优先级容器
            removeFromClientPlayers(clientLevel, entity);
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to remove from client containers: {}", e.getMessage());
        }
    }

    //从客户端 EntityTickList.active 和 passive 移除
    @SuppressWarnings("unchecked")
    private static void removeFromClientEntityTickList(ClientLevel clientLevel, int entityId) {
        try {
            if (CLIENT_LEVEL_TICKING_ENTITIES_HANDLE == null) return;

            Object entityTickList = CLIENT_LEVEL_TICKING_ENTITIES_HANDLE.get(clientLevel);
            if (entityTickList == null) return;

            Int2ObjectLinkedOpenHashMap<Entity> active = (Int2ObjectLinkedOpenHashMap<Entity>) ENTITY_TICK_LIST_ACTIVE_HANDLE.get(entityTickList);
            if (active != null) {
                active.remove(entityId);
            }

            Int2ObjectLinkedOpenHashMap<Entity> passive = (Int2ObjectLinkedOpenHashMap<Entity>) ENTITY_TICK_LIST_PASSIVE_HANDLE.get(entityTickList);
            if (passive != null) {
                passive.remove(entityId);
            }
        } catch (Exception ignored) {}
    }

    //从客户端 EntityLookup (byUuid + byId) 移除
    @SuppressWarnings("unchecked")
    private static void removeFromClientEntityLookup(ClientLevel clientLevel, int entityId, UUID entityUUID) {
        try {
            if (CLIENT_LEVEL_ENTITY_STORAGE_HANDLE == null) return;

            Object entityStorage = CLIENT_LEVEL_ENTITY_STORAGE_HANDLE.get(clientLevel);
            if (entityStorage == null) return;

            Object entityLookup = TRANSIENT_ENTITY_MANAGER_ENTITY_STORAGE_HANDLE.get(entityStorage);
            if (entityLookup == null) return;

            //移除 byUuid
            Map<UUID, Entity> byUuid = (Map<UUID, Entity>) ENTITY_LOOKUP_BY_UUID_HANDLE.get(entityLookup);
            if (byUuid != null) {
                byUuid.remove(entityUUID);
            }

            //移除 byId
            Int2ObjectLinkedOpenHashMap<Entity> byId = (Int2ObjectLinkedOpenHashMap<Entity>) ENTITY_LOOKUP_BY_ID_HANDLE.get(entityLookup);
            if (byId != null) {
                byId.remove(entityId);
            }
        } catch (Exception ignored) {}
    }

    //从客户端 EntitySectionStorage 移除
    @SuppressWarnings("unchecked")
    private static void removeFromClientEntitySectionStorage(ClientLevel clientLevel, Entity entity) {
        try {
            if (CLIENT_LEVEL_ENTITY_STORAGE_HANDLE == null) return;

            Object entityStorage = CLIENT_LEVEL_ENTITY_STORAGE_HANDLE.get(clientLevel);
            if (entityStorage == null) return;

            Object sectionStorage = TRANSIENT_ENTITY_MANAGER_SECTION_STORAGE_HANDLE.get(entityStorage);
            if (sectionStorage == null) return;

            Long2ObjectMap<?> sections = (Long2ObjectMap<?>) ENTITY_SECTION_STORAGE_SECTIONS_HANDLE.get(sectionStorage);
            if (sections == null) return;

            //遍历所有 EntitySection
            for (Object section : sections.values()) {
                if (section == null) continue;

                Object storage = ENTITY_SECTION_STORAGE_HANDLE.get(section);
                if (storage == null) continue;

                //获取 ClassInstanceMultiMap.byClass
                Map<Class<?>, List<?>> byClass = (Map<Class<?>, List<?>>) CLASS_INSTANCE_MULTI_MAP_BY_CLASS_HANDLE.get(storage);
                if (byClass == null) continue;

                //遍历所有类型的 List，移除匹配的实体
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

    //从客户端 players 列表移除
    @SuppressWarnings("unchecked")
    private static void removeFromClientPlayers(ClientLevel clientLevel, Entity entity) {
        try {
            if (entity instanceof Player && CLIENT_LEVEL_PLAYERS_HANDLE != null) {
                List<?> players = (List<?>) CLIENT_LEVEL_PLAYERS_HANDLE.get(clientLevel);
                if (players != null) {
                    players.remove(entity);
                }
            }
        } catch (Exception ignored) {}
    }
}
