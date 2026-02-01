package net.eca.util;

import net.eca.config.EcaConfiguration;
import net.eca.network.EcaClientRemovePacket;
import net.eca.network.NetworkHandler;
import net.eca.util.health.HealthAnalyzer.HealthFieldCache;
import net.eca.util.health.HealthAnalyzerManager;
import net.eca.util.reflect.VarHandleUtil;
import net.minecraft.network.syncher.EntityDataSerializer;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.core.SectionPos;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.eca.util.reflect.VarHandleUtil.*;

@SuppressWarnings("unchecked")
//实体工具类
public class EntityUtil {

    //EntityDataAccessor 血量锁定（神秘文本血）+无敌状态
    public static EntityDataAccessor<Boolean> HEALTH_LOCK_ENABLED;
    public static EntityDataAccessor<String> HEALTH_LOCK_VALUE;
    public static EntityDataAccessor<Boolean> INVULNERABLE;

    // 正在切换维度的实体UUID集合（线程安全）
    private static final Set<UUID> DIMENSION_CHANGING_ENTITIES = ConcurrentHashMap.newKeySet();

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
            "age", "lifetime", "deathtime", "hurttime", "invulnerabletime", "hurt"
        ));
    }

    //VarHandle缓存
    private static final Map<String, VarHandle> VAR_HANDLE_CACHE = new ConcurrentHashMap<>();

    //EntityDataAccessor缓存
    private static final Map<Class<?>, List<EntityDataAccessor<?>>> NEARBY_ACCESSOR_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<EntityDataAccessor<?>>> KEYWORD_ACCESSOR_CACHE = new ConcurrentHashMap<>();

    static {
        //初始化所有实体相关的 VarHandle
        VarHandleUtil.init();
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
        EcaLogger.info("[EntityUtil] Marked entity {} (UUID: {}) as changing dimension", entity.getId(), uuid);
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
        boolean removed = DIMENSION_CHANGING_ENTITIES.remove(uuid);
        if (removed) {
            EcaLogger.info("[EntityUtil] Unmarked entity {} (UUID: {}) from changing dimension", entity.getId(), uuid);
        }
    }

    public static void clearRemovalReasonIfProtected(Entity entity) {
        if (entity == null) {
            return;
        }
        if (VH_ENTITY_REMOVAL_REASON == null) {
            return;
        }

        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason == null) {
            return;
        }
        if (reason == Entity.RemovalReason.CHANGED_DIMENSION) {
            return;
        }
        if (isChangingDimension(entity)) {
            return;
        }
        if (!reason.shouldSave()) {
            VH_ENTITY_REMOVAL_REASON.set(entity, null);
        }
    }

    //获取实体真实生命值
    public static float getHealth(LivingEntity entity) {
        if (entity == null) return 0.0f;
        try {
            SynchedEntityData.DataItem<?> dataItem = getDataItem(entity.getEntityData(), LivingEntity.DATA_HEALTH_ID.getId());
            if (dataItem == null || VH_DATA_ITEM_VALUE == null) {
                return entity.getHealth();
            }
            return (Float) VH_DATA_ITEM_VALUE.get(dataItem);
        } catch (Exception e) {
            return entity.getHealth();
        }
    }

    //获取DataItem
    private static SynchedEntityData.DataItem<?> getDataItem(SynchedEntityData entityData, int id) {
        try {
            if (FIELD_ITEMS_BY_ID == null) return null;
            Object itemsById = FIELD_ITEMS_BY_ID.get(entityData);
            if (itemsById instanceof Int2ObjectMap) {
                return (SynchedEntityData.DataItem<?>) ((Int2ObjectMap<?>) itemsById).get(id);
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
            boolean verify1 = verifyHealthChange(entity, expectedHealth);
            if (verify1) {
                return true;
            }

            //阶段3：字节码反向追踪
            setHealthViaPhase3(entity, expectedHealth);

            boolean verify2 = verifyHealthChange(entity, expectedHealth);
            return verify2;
        } catch (Exception e) {
            return false;
        }
    }

    //验证血量修改是否成功
    private static boolean verifyHealthChange(LivingEntity entity, float expectedHealth) {
        try {
            float actualHealth = entity.getHealth();  // 调用实体的 getHealth()
            boolean actualPass = Math.abs(actualHealth - expectedHealth) <= 10.0f;
            // 使用实体的 getHealth() 验证，因为这才是真正显示的血量
            return actualPass;
        } catch (Exception e) {
            return false;
        }
    }

    //阶段1：设置原版血量
    private static void setBasicHealth(LivingEntity entity, float expectedHealth) {
        try {
            if (VH_DATA_ITEM_VALUE == null || VH_DATA_ITEM_DIRTY == null) return;

            SynchedEntityData entityData = entity.getEntityData();
            SynchedEntityData.DataItem<?> dataItem = getDataItem(entityData, LivingEntity.DATA_HEALTH_ID.getId());
            if (dataItem == null) return;

            VH_DATA_ITEM_VALUE.set(dataItem, expectedHealth);
            entity.onSyncedDataUpdated(LivingEntity.DATA_HEALTH_ID);
            VH_DATA_ITEM_DIRTY.set(dataItem, true);
            setIsDirty(entityData, true);
        } catch (Exception ignored) {}
    }

    //同步原版DATA_HEALTH_ID
    private static void syncDataHealthId(LivingEntity entity, float expectedHealth) {
        try {
            if (VH_DATA_ITEM_VALUE == null || VH_DATA_ITEM_DIRTY == null) return;

            SynchedEntityData entityData = entity.getEntityData();
            SynchedEntityData.DataItem<?> dataItem = getDataItem(entityData, LivingEntity.DATA_HEALTH_ID.getId());
            if (dataItem == null) return;

            VH_DATA_ITEM_VALUE.set(dataItem, expectedHealth);
            entity.onSyncedDataUpdated(LivingEntity.DATA_HEALTH_ID);
            VH_DATA_ITEM_DIRTY.set(dataItem, true);
            setIsDirty(entityData, true);
        } catch (Exception ignored) {}
    }

    //设置isDirty标记
    private static void setIsDirty(SynchedEntityData entityData, boolean dirty) {
        try {
            if (FIELD_IS_DIRTY != null) {
                FIELD_IS_DIRTY.set(entityData, dirty);
            }
        } catch (Exception ignored) {}
    }

    //阶段2：修改符合条件的EntityDataAccessor和字段
    private static void setHealthViaPhase2(LivingEntity entity, float expectedHealth) {
        try {
            float currentHealth = getHealth(entity);

            //尝试调用实体自己的 setHealth 类方法（优先级最高）
            tryCallEntityHealthSetter(entity, expectedHealth);

            //修改数值近似的EntityDataAccessor
            List<EntityDataAccessor<?>> nearbyAccessors = findNearbyNumericAccessors(entity);
            for (EntityDataAccessor<?> acc : nearbyAccessors) {
                setAccessorValue(entity, acc, expectedHealth);
            }

            //修改关键词匹配的EntityDataAccessor
            List<EntityDataAccessor<?>> keywordAccessors = findHealthKeywordAccessors(entity);
            for (EntityDataAccessor<?> acc : keywordAccessors) {
                setAccessorValue(entity, acc, expectedHealth);
            }

            //扫描并修改符合条件的实例字段
            scanAndModifyIntelligentFields(entity, currentHealth, expectedHealth);
        } catch (Exception e) {
            // Silently fail
        }
    }

    //尝试调用实体自身的血量设置方法
    private static boolean tryCallEntityHealthSetter(LivingEntity entity, float expectedHealth) {
        Class<?> entityClass = entity.getClass();
        // 常见的血量设置方法名
        String[] methodNames = {"setHEALTS", "setHealth", "set_health", "setHP", "setHp"};

        for (Class<?> clazz = entityClass; clazz != null && clazz != LivingEntity.class; clazz = clazz.getSuperclass()) {
            for (String methodName : methodNames) {
                try {
                    Method method = clazz.getDeclaredMethod(methodName, float.class);
                    method.setAccessible(true);
                    method.invoke(entity, expectedHealth);
                    return true;
                } catch (NoSuchMethodException ignored) {
                    // 方法不存在，尝试下一个
                } catch (Exception ignored) {
                    // Silently fail
                }
            }
        }
        return false;
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
        float currentHealth = getHealth(entity);

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
        if (cached != null) {
            return cached;
        }

        List<EntityDataAccessor<?>> result = new ArrayList<>();
        float entityHealth = getHealth(entity);
        int vanillaHealthId = LivingEntity.DATA_HEALTH_ID.getId();

        for (Class<?> clazz = entityClass; clazz != null && clazz != Entity.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    if (!EntityDataAccessor.class.isAssignableFrom(field.getType())) continue;
                    if (!Modifier.isStatic(field.getModifiers())) continue;

                    EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) field.get(null);
                    if (accessor.getId() == vanillaHealthId) {
                        continue;
                    }

                    Object value = entity.getEntityData().get(accessor);
                    if (value instanceof Number) {
                        float numericValue = ((Number) value).floatValue();
                        float diff = Math.abs(numericValue - entityHealth);
                        if (diff <= 10.0f) {
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
        if (cached != null) {
            return cached;
        }

        List<EntityDataAccessor<?>> result = new ArrayList<>();
        int vanillaHealthId = LivingEntity.DATA_HEALTH_ID.getId();

        for (Class<?> clazz = entityClass; clazz != null && clazz != Entity.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    if (!EntityDataAccessor.class.isAssignableFrom(field.getType())) continue;
                    if (!Modifier.isStatic(field.getModifiers())) continue;

                    EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) field.get(null);
                    if (accessor == null) {
                        continue;
                    }
                    if (accessor.getId() == vanillaHealthId) {
                        continue;
                    }

                    boolean keywordMatch = matchesHealthWhitelist(field.getName());
                    Object value = entity.getEntityData().get(accessor);

                    if (keywordMatch && value instanceof Number) {
                        result.add(accessor);
                    }
                } catch (Exception ignored) {
                    // Silently fail
                }
            }
        }

        KEYWORD_ACCESSOR_CACHE.put(entityClass, result);
        return result;
    }

    //设置EntityDataAccessor的值
    private static void setAccessorValue(LivingEntity entity, EntityDataAccessor<?> accessor, float expectedHealth) {
        try {
            EntityDataSerializer<?> serializer = accessor.getSerializer();
            SynchedEntityData entityData = entity.getEntityData();
            boolean success = false;

            if (serializer == EntityDataSerializers.FLOAT) {
                entityData.set((EntityDataAccessor<Float>) accessor, expectedHealth);
                success = true;
            } else if (serializer == EntityDataSerializers.INT) {
                entityData.set((EntityDataAccessor<Integer>) accessor, (int) expectedHealth);
                success = true;
            }

            if (success) {
                // 强制标记dirty并触发同步回调
                SynchedEntityData.DataItem<?> dataItem = getDataItem(entityData, accessor.getId());
                if (dataItem != null && VH_DATA_ITEM_DIRTY != null) {
                    VH_DATA_ITEM_DIRTY.set(dataItem, true);
                }
                setIsDirty(entityData, true);
                entity.onSyncedDataUpdated(accessor);
            }
        } catch (Exception ignored) {
            // Silently fail
        }
    }

    //阶段3：字节码反向追踪修改血量
    private static void setHealthViaPhase3(LivingEntity entity, float expectedHealth) {
        try {
            Class<?> entityClass = entity.getClass();
            HealthFieldCache cache = HealthAnalyzerManager.getCache(entityClass);

            if (cache == null) {
                HealthAnalyzerManager.triggerAnalysis(entity);
                cache = HealthAnalyzerManager.getCache(entityClass);
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
            Method getterMethod = containerClass.getDeclaredMethod(cache.containerGetterMethod);
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
            HealthAnalyzerManager.initHashMapVarHandles(map.getClass());
            Object[] table = (Object[]) HealthAnalyzerManager.getHashMapTable(map);
            if (table == null) return false;

            for (Object node : table) {
                while (node != null) {
                    Object nodeKey = HealthAnalyzerManager.getHashMapNodeKey(node);
                    if (key.equals(nodeKey) || key == nodeKey) {
                        HealthAnalyzerManager.setHashMapNodeValue(node, newValue);
                        return true;
                    }
                    node = HealthAnalyzerManager.getHashMapNodeNext(node);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    //修改WeakHashMap的值
    private static boolean modifyWeakHashMapValue(WeakHashMap<?, ?> map, Object key, float newValue) {
        try {
            HealthAnalyzerManager.initHashMapVarHandles(map.getClass());
            Object[] table = (Object[]) HealthAnalyzerManager.getWeakHashMapTable(map);
            if (table == null) return false;

            for (Object entry : table) {
                while (entry != null) {
                    Object entryKey = HealthAnalyzerManager.getWeakHashMapEntryKey(entry);
                    if (key.equals(entryKey) || key == entryKey) {
                        HealthAnalyzerManager.setWeakHashMapEntryValue(entry, newValue);
                        return true;
                    }
                    entry = HealthAnalyzerManager.getWeakHashMapEntryNext(entry);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    //检查字段名是否匹配健康白名单
    private static boolean matchesHealthWhitelist(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) return false;
        String lowerName = fieldName.toLowerCase();
        for (String keyword : HEALTH_WHITELIST_KEYWORDS) {
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
            // 清除死亡标志
            if (VH_DEAD != null) {
                VH_DEAD.set(entity, false);
            }
            // 重置死亡时间
            if (VH_DEATH_TIME != null) {
                VH_DEATH_TIME.set(entity, 0);
            }
            // 安全清除移除原因（保护维度切换和区块卸载）
            clearRemovalReasonIfProtected(entity);
            // 恢复满血
            setHealth(entity, entity.getMaxHealth());
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to revive entity: {}", e.getMessage());
        }
    }

    //使用VarHandle设置死亡相关字段
    private static void setDeathFieldsViaVarHandle(LivingEntity entity) {
        try {
            if (VH_DEAD != null) {
                VH_DEAD.set(entity, true);
            }
            if (VH_DEATH_TIME != null) {
                VH_DEATH_TIME.set(entity, 0);
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
        if (entity == null || entity.level() == null) return;
        boolean isServerSide = !entity.level().isClientSide;

        try {

            cleanupAI(entity);
            cleanupBossBar(entity);
            entity.stopRiding();
            removeAllPassengers(entity);
            entity.invalidateCaps();
            if (isServerSide && entity.level() instanceof ServerLevel serverLevel) {
                serverLevel.getScoreboard().entityRemoved(entity);
                NetworkHandler.sendToTrackingClients(
                        new EcaClientRemovePacket(entity.getId()),
                        entity
                );
            }

            entity.setRemoved(reason);
            entity.onRemovedFromWorld();
            //调用 levelCallback.onRemove() 在设置 NULL 之前
            EntityInLevelCallback callback = entity.levelCallback;
            if (callback != EntityInLevelCallback.NULL) {
                callback.onRemove(reason);
            }
            entity.levelCallback = EntityInLevelCallback.NULL;
            if (entity instanceof LivingEntity livingEntity) {
                livingEntity.updateDynamicGameEventListener(DynamicGameEventListener::remove);
            }


            if (isServerSide && entity.level() instanceof ServerLevel serverLevel) {
                removeFromServerContainers(serverLevel, entity);
            } else if (entity.level().isClientSide && entity.level() instanceof ClientLevel clientLevel) {
                removeFromClientContainers(clientLevel, entity);
            }
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Entity removal failed: {}", e.getMessage());
        }
    }


    //AI清理
    private static void cleanupAI(Entity entity) {
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

        //收集所有 ServerBossEvent
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

        //发送移除网络包并清理
        if (!bossEvents.isEmpty() && !entity.level().isClientSide && entity.level() instanceof ServerLevel serverLevel) {
            for (ServerBossEvent bossEvent : bossEvents) {
                //发送移除网络包给所有玩家
                for (ServerPlayer player : serverLevel.players()) {
                    try {
                        player.connection.send(ClientboundBossEventPacket.createRemovePacket(bossEvent.getId()));
                    } catch (Exception ignored) {}
                }
                //服务端清理
                bossEvent.removeAllPlayers();
                bossEvent.setVisible(false);
            }
        } else {
            //客户端或无法获取 ServerLevel 时只做基本清理
            for (ServerBossEvent bossEvent : bossEvents) {
                bossEvent.removeAllPlayers();
                bossEvent.setVisible(false);
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

    //服务端底层容器清除
    private static void removeFromServerContainers(ServerLevel serverLevel, Entity entity) {
        int entityId = entity.getId();
        UUID entityUUID = entity.getUUID();

        try {
            callbacksOnDestroyed(serverLevel, entity);
            removeFromLoadingInbox(serverLevel, entity);
            removeFromChunkMapEntityMap(serverLevel, entityId);
            removeFromServerPlayers(serverLevel, entity);
            removeFromServerNavigatingMobs(serverLevel, entity);
            //原版顺序
            removeFromEntitySectionStorage(serverLevel, entity);  //1. EntitySection (ClassInstanceMultiMap)
            removeSectionIfEmpty(serverLevel, entity);
            removeFromEntityTickList(serverLevel, entityId);      //2. EntityTickList
            removeFromEntityLookup(serverLevel, entityId, entityUUID); //3. EntityLookup
            removeFromKnownUuids(serverLevel, entityUUID);        //4. KnownUuids
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to remove from server containers: {}", e.getMessage());
        }
    }

    //从 loadingInbox 清理正在加载的实体
    private static void removeFromLoadingInbox(ServerLevel serverLevel, Entity entity) throws Exception {
        if (VH_SERVER_LEVEL_ENTITY_MANAGER == null || VH_PERSISTENT_ENTITY_MANAGER_LOADING_INBOX == null) return;

        Object entityManager = VH_SERVER_LEVEL_ENTITY_MANAGER.get(serverLevel);
        if (entityManager == null) return;

        Queue<?> loadingInbox = (Queue<?>) VH_PERSISTENT_ENTITY_MANAGER_LOADING_INBOX.get(entityManager);
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
    }

    //从 ChunkMap.entityMap 移除
    private static void removeFromChunkMapEntityMap(ServerLevel serverLevel, int entityId) {
        if (VH_SERVER_LEVEL_CHUNK_SOURCE == null) return;

        Object chunkSource = VH_SERVER_LEVEL_CHUNK_SOURCE.get(serverLevel);
        if (chunkSource == null) return;

        Object chunkMap = VH_SERVER_CHUNK_CACHE_CHUNK_MAP.get(chunkSource);
        if (chunkMap == null) return;

        Int2ObjectOpenHashMap<?> entityMap = (Int2ObjectOpenHashMap<?>) VH_CHUNK_MAP_ENTITY_MAP.get(chunkMap);
        if (entityMap != null) {
            entityMap.remove(entityId);
        }
    }

    //从 ServerLevel.players 移除
    private static void removeFromServerPlayers(ServerLevel serverLevel, Entity entity) {
        if (entity instanceof ServerPlayer && VH_SERVER_LEVEL_PLAYERS != null) {
            List<ServerPlayer> players = (List<ServerPlayer>) VH_SERVER_LEVEL_PLAYERS.get(serverLevel);
            if (players != null) {
                players.remove(entity);
            }
        }
    }

    //从 ServerLevel.navigatingMobs 移除
    private static void removeFromServerNavigatingMobs(ServerLevel serverLevel, Entity entity) {
        if (entity instanceof Mob && VH_SERVER_LEVEL_NAVIGATING_MOBS != null) {
            ObjectOpenHashSet<Mob> navigatingMobs = (ObjectOpenHashSet<Mob>) VH_SERVER_LEVEL_NAVIGATING_MOBS.get(serverLevel);
            if (navigatingMobs != null) {
                navigatingMobs.remove(entity);
            }
        }
    }

    //从 EntitySectionStorage → EntitySection.storage 移除
    private static void removeFromEntitySectionStorage(ServerLevel serverLevel, Entity entity) {
        if (VH_SERVER_LEVEL_ENTITY_MANAGER == null) return;

        Object entityManager = VH_SERVER_LEVEL_ENTITY_MANAGER.get(serverLevel);
        if (entityManager == null) return;

        Object sectionStorage = VH_PERSISTENT_ENTITY_MANAGER_SECTION_STORAGE.get(entityManager);
        if (sectionStorage == null) return;

        Long2ObjectMap<?> sections = (Long2ObjectMap<?>) VH_ENTITY_SECTION_STORAGE_SECTIONS.get(sectionStorage);
        if (sections == null) return;

        for (Object section : sections.values()) {
            if (section == null) continue;

            Object storage = VH_ENTITY_SECTION_STORAGE.get(section);
            if (storage == null) continue;

            Map<Class<?>, List<?>> byClass = (Map<Class<?>, List<?>>) VH_CLASS_INSTANCE_MULTI_MAP_BY_CLASS.get(storage);
            if (byClass == null) continue;

            for (Map.Entry<Class<?>, List<?>> entry : byClass.entrySet()) {
                if (entry.getKey().isInstance(entity)) {
                    entry.getValue().remove(entity);
                }
            }
        }
    }

    //从 EntityTickList.active、passive、iterated 移除（仿照原版 ensureActiveIsNotIterated + remove 逻辑）
    private static void removeFromEntityTickList(ServerLevel serverLevel, int entityId) {
        if (VH_SERVER_LEVEL_ENTITY_TICK_LIST == null) return;

        Object entityTickList = VH_SERVER_LEVEL_ENTITY_TICK_LIST.get(serverLevel);
        if (entityTickList == null) return;

        Int2ObjectLinkedOpenHashMap<Entity> active = (Int2ObjectLinkedOpenHashMap<Entity>) VH_ENTITY_TICK_LIST_ACTIVE.get(entityTickList);
        Int2ObjectLinkedOpenHashMap<Entity> passive = (Int2ObjectLinkedOpenHashMap<Entity>) VH_ENTITY_TICK_LIST_PASSIVE.get(entityTickList);
        Int2ObjectLinkedOpenHashMap<Entity> iterated = (Int2ObjectLinkedOpenHashMap<Entity>) VH_ENTITY_TICK_LIST_ITERATED.get(entityTickList);

        if (active == null || passive == null) return;

        // 仿照原版 ensureActiveIsNotIterated：如果正在遍历 active，先完整复制到 passive 再交换
        // 绝不直接修改正在被遍历的 map
        if (iterated == active) {
            passive.clear();
            for (Int2ObjectMap.Entry<Entity> entry : Int2ObjectMaps.fastIterable(active)) {
                passive.put(entry.getIntKey(), entry.getValue());
            }
            VH_ENTITY_TICK_LIST_ACTIVE.set(entityTickList, passive);
            VH_ENTITY_TICK_LIST_PASSIVE.set(entityTickList, active);
            // 交换后 active 引用已变，重新读取新的 active
            active = passive;
        }

        // 在新的 active 上安全移除（此时 active 不是被遍历的 map）
        active.remove(entityId);
    }

    //从 EntityLookup (byUuid + byId) 移除
    private static void removeFromEntityLookup(ServerLevel serverLevel, int entityId, UUID entityUUID) {
        if (VH_SERVER_LEVEL_ENTITY_MANAGER == null) return;

        Object entityManager = VH_SERVER_LEVEL_ENTITY_MANAGER.get(serverLevel);
        if (entityManager == null) return;

        Object visibleEntityStorage = VH_PERSISTENT_ENTITY_MANAGER_VISIBLE_STORAGE.get(entityManager);
        if (visibleEntityStorage == null) return;

        Map<UUID, Entity> byUuid = (Map<UUID, Entity>) VH_ENTITY_LOOKUP_BY_UUID.get(visibleEntityStorage);
        if (byUuid != null) {
            byUuid.remove(entityUUID);
        }

        Int2ObjectLinkedOpenHashMap<Entity> byId = (Int2ObjectLinkedOpenHashMap<Entity>) VH_ENTITY_LOOKUP_BY_ID.get(visibleEntityStorage);
        if (byId != null) {
            byId.remove(entityId);
        }
    }

    //从 PersistentEntitySectionManager.knownUuids 移除
    private static void removeFromKnownUuids(ServerLevel serverLevel, UUID entityUUID) {
        if (VH_SERVER_LEVEL_ENTITY_MANAGER == null) return;

        Object entityManager = VH_SERVER_LEVEL_ENTITY_MANAGER.get(serverLevel);
        if (entityManager == null) return;

        Set<UUID> knownUuids = (Set<UUID>) VH_PERSISTENT_ENTITY_MANAGER_KNOWN_UUIDS.get(entityManager);
        if (knownUuids != null) {
            knownUuids.remove(entityUUID);
        }
    }

    //调用 PersistentEntitySectionManager.callbacks.onDestroyed() 触发 tracking 结束
    @SuppressWarnings("rawtypes")
    private static void callbacksOnDestroyed(ServerLevel serverLevel, Entity entity) {
        if (VH_SERVER_LEVEL_ENTITY_MANAGER == null || VH_PERSISTENT_ENTITY_MANAGER_CALLBACKS == null) return;

        Object entityManager = VH_SERVER_LEVEL_ENTITY_MANAGER.get(serverLevel);
        if (entityManager == null) return;

        Object callbacks = VH_PERSISTENT_ENTITY_MANAGER_CALLBACKS.get(entityManager);
        if (callbacks instanceof LevelCallback levelCallback) {
            levelCallback.onDestroyed(entity);
        }
    }

    //清理空的 EntitySection（内存优化）
    private static void removeSectionIfEmpty(ServerLevel serverLevel, Entity entity) {
        if (VH_SERVER_LEVEL_ENTITY_MANAGER == null || VH_PERSISTENT_ENTITY_MANAGER_SECTION_STORAGE == null) return;

        Object entityManager = VH_SERVER_LEVEL_ENTITY_MANAGER.get(serverLevel);
        if (entityManager == null) return;

        Object sectionStorage = VH_PERSISTENT_ENTITY_MANAGER_SECTION_STORAGE.get(entityManager);
        if (sectionStorage == null) return;

        long sectionKey = SectionPos.asLong(entity.blockPosition());
        Long2ObjectMap<?> sections = (Long2ObjectMap<?>) VH_ENTITY_SECTION_STORAGE_SECTIONS.get(sectionStorage);
        if (sections == null) return;

        Object section = sections.get(sectionKey);
        if (section instanceof EntitySection<?> entitySection) {
            if (entitySection.isEmpty()) {
                sections.remove(sectionKey);
            }
        }
    }

    //客户端底层容器清除
    public static void removeFromClientContainers(ClientLevel clientLevel, Entity entity) {
        VarHandleUtil.initClient();
        int entityId = entity.getId();
        UUID entityUUID = entity.getUUID();

        try {
            //清理客户端 Boss Overlay
            clearClientBossOverlay(entity);
            removeFromClientPlayers(clientLevel, entity);
            //原版顺序
            removeFromClientEntitySectionStorage(clientLevel, entity);       //1. EntitySection (ClassInstanceMultiMap)
            removeClientSectionIfEmpty(clientLevel, entity);
            removeFromClientEntityTickList(clientLevel, entityId);           //2. EntityTickList
            removeFromClientEntityLookup(clientLevel, entityId, entityUUID); //3. EntityLookup

        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to remove from client containers: {}", e.getMessage());
        }
    }

    //清理客户端 Boss Overlay（Boss血条 UI）
    private static void clearClientBossOverlay(Entity entity) {
        if (VH_BOSS_HEALTH_OVERLAY_EVENTS == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        BossHealthOverlay bossOverlay = minecraft.gui.getBossOverlay();
        //使用 VarHandle 获取 events 字段
        Object events = VH_BOSS_HEALTH_OVERLAY_EVENTS.get(bossOverlay);
        if (events instanceof Map<?, ?> eventsMap) {
            eventsMap.clear();
        }
    }

    //从客户端 EntityTickList 移除（仿照原版 ensureActiveIsNotIterated + remove 逻辑）
    private static void removeFromClientEntityTickList(ClientLevel clientLevel, int entityId) {
        if (VH_CLIENT_LEVEL_TICKING_ENTITIES == null) return;

        Object entityTickList = VH_CLIENT_LEVEL_TICKING_ENTITIES.get(clientLevel);
        if (entityTickList == null) return;

        Int2ObjectLinkedOpenHashMap<Entity> active = (Int2ObjectLinkedOpenHashMap<Entity>) VH_ENTITY_TICK_LIST_ACTIVE.get(entityTickList);
        Int2ObjectLinkedOpenHashMap<Entity> passive = (Int2ObjectLinkedOpenHashMap<Entity>) VH_ENTITY_TICK_LIST_PASSIVE.get(entityTickList);
        Int2ObjectLinkedOpenHashMap<Entity> iterated = (Int2ObjectLinkedOpenHashMap<Entity>) VH_ENTITY_TICK_LIST_ITERATED.get(entityTickList);

        if (active == null || passive == null) return;

        // 仿照原版 ensureActiveIsNotIterated：如果正在遍历 active，先完整复制到 passive 再交换
        // 绝不直接修改正在被遍历的 map
        if (iterated == active) {
            passive.clear();
            for (Int2ObjectMap.Entry<Entity> entry : Int2ObjectMaps.fastIterable(active)) {
                passive.put(entry.getIntKey(), entry.getValue());
            }
            VH_ENTITY_TICK_LIST_ACTIVE.set(entityTickList, passive);
            VH_ENTITY_TICK_LIST_PASSIVE.set(entityTickList, active);
            active = passive;
        }

        // 在新的 active 上安全移除
        active.remove(entityId);
    }

    //从客户端 EntityLookup (byUuid + byId) 移除
    private static void removeFromClientEntityLookup(ClientLevel clientLevel, int entityId, UUID entityUUID) {
        if (VH_CLIENT_LEVEL_ENTITY_STORAGE == null) return;

        Object entityStorage = VH_CLIENT_LEVEL_ENTITY_STORAGE.get(clientLevel);
        if (entityStorage == null) return;

        Object entityLookup = VH_TRANSIENT_ENTITY_MANAGER_ENTITY_STORAGE.get(entityStorage);
        if (entityLookup == null) return;

        Map<UUID, Entity> byUuid = (Map<UUID, Entity>) VH_ENTITY_LOOKUP_BY_UUID.get(entityLookup);
        if (byUuid != null) {
            byUuid.remove(entityUUID);
        }

        Int2ObjectLinkedOpenHashMap<Entity> byId = (Int2ObjectLinkedOpenHashMap<Entity>) VH_ENTITY_LOOKUP_BY_ID.get(entityLookup);
        if (byId != null) {
            byId.remove(entityId);
        }
    }

    //从客户端 EntitySectionStorage 移除
    private static void removeFromClientEntitySectionStorage(ClientLevel clientLevel, Entity entity) {
        if (VH_CLIENT_LEVEL_ENTITY_STORAGE == null) return;

        Object entityStorage = VH_CLIENT_LEVEL_ENTITY_STORAGE.get(clientLevel);
        if (entityStorage == null) return;

        Object sectionStorage = VH_TRANSIENT_ENTITY_MANAGER_SECTION_STORAGE.get(entityStorage);
        if (sectionStorage == null) return;

        Long2ObjectMap<?> sections = (Long2ObjectMap<?>) VH_ENTITY_SECTION_STORAGE_SECTIONS.get(sectionStorage);
        if (sections == null) return;

        for (Object section : sections.values()) {
            if (section == null) continue;

            Object storage = VH_ENTITY_SECTION_STORAGE.get(section);
            if (storage == null) continue;

            Map<Class<?>, List<?>> byClass = (Map<Class<?>, List<?>>) VH_CLASS_INSTANCE_MULTI_MAP_BY_CLASS.get(storage);
            if (byClass == null) continue;

            for (Map.Entry<Class<?>, List<?>> entry : byClass.entrySet()) {
                if (entry.getKey().isInstance(entity)) {
                    entry.getValue().remove(entity);
                }
            }
        }
    }

    //从客户端 players 列表移除
    private static void removeFromClientPlayers(ClientLevel clientLevel, Entity entity) {
        if (entity instanceof Player && VH_CLIENT_LEVEL_PLAYERS != null) {
            List<?> players = (List<?>) VH_CLIENT_LEVEL_PLAYERS.get(clientLevel);
            if (players != null) {
                players.remove(entity);
            }
        }
    }


    //清理空的客户端 EntitySection
    private static void removeClientSectionIfEmpty(ClientLevel clientLevel, Entity entity) {
        if (VH_CLIENT_LEVEL_ENTITY_STORAGE == null || VH_TRANSIENT_ENTITY_MANAGER_SECTION_STORAGE == null) return;

        Object entityStorage = VH_CLIENT_LEVEL_ENTITY_STORAGE.get(clientLevel);
        if (entityStorage == null) return;

        Object sectionStorage = VH_TRANSIENT_ENTITY_MANAGER_SECTION_STORAGE.get(entityStorage);
        if (sectionStorage == null) return;

        long sectionKey = SectionPos.asLong(entity.blockPosition());
        Long2ObjectMap<?> sections = (Long2ObjectMap<?>) VH_ENTITY_SECTION_STORAGE_SECTIONS.get(sectionStorage);
        if (sections == null) return;

        Object section = sections.get(sectionKey);
        if (section instanceof EntitySection<?> entitySection) {
            if (entitySection.isEmpty()) {
                sections.remove(sectionKey);
            }
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
    public static boolean teleportEntity(Entity entity, double x, double y, double z) {
        if (entity == null) return false;

        try {
            Vec3 newPosition = new Vec3(x, y, z);

            //修改核心位置字段(使用VarHandle)
            VH_ENTITY_POSITION.set(entity, newPosition);
            VH_ENTITY_X_OLD.set(entity, x);
            VH_ENTITY_Y_OLD.set(entity, y);
            VH_ENTITY_Z_OLD.set(entity, z);

            //更新碰撞箱
            AABB newBoundingBox = entity.getDimensions(entity.getPose()).makeBoundingBox(x, y, z);
            VH_ENTITY_BB.set(entity, newBoundingBox);

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
            serverLevel.getChunkSource().broadcast(entity, packet);

            //玩家特殊处理
            if (entity instanceof ServerPlayer player) {
                player.connection.teleport(
                        entity.getX(),
                        entity.getY(),
                        entity.getZ(),
                        entity.getYRot(),
                        entity.getXRot()
                );
            }
        } catch (Exception e) {
            EcaLogger.error("Failed to sync teleport to clients: {}", e.getMessage());
        }
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
