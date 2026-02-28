package net.eca.util;

import net.eca.agent.EcaContainers;
import net.eca.config.EcaConfiguration;
import net.eca.network.EcaClientRemovePacket;
import net.eca.network.EntityHealthSyncPacket;
import net.eca.network.NetworkHandler;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.health.HealthAnalyzer.HealthFieldCache;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.core.SectionPos;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings({"unchecked", "rawtypes"})
//实体工具类
public class EntityUtil {

    //EntityDataAccessor 血量锁定（神秘文本血）+ 禁疗 + 无敌状态 + 最大生命值锁定
    public static EntityDataAccessor<String> HEALTH_LOCK_VALUE;
    public static EntityDataAccessor<Float> HEAL_BAN_VALUE;
    public static EntityDataAccessor<Boolean> INVULNERABLE;
    public static EntityDataAccessor<Float> MAX_HEALTH_LOCK_VALUE;

    //标记当前调用来自同步包，防止重复发包
    private static final ThreadLocal<Boolean> IS_FROM_SYNC = ThreadLocal.withInitial(() -> false);

    //正在切换维度的实体UUID集合（线程安全）
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
    private static final Map<Class<?>, List<EntityDataAccessor<?>>> HEALTH_ACCESSOR_CACHE = new ConcurrentHashMap<>();

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
        if (reason == Entity.RemovalReason.CHANGED_DIMENSION) {
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

    //设置实体生命值（完整阶段流程）
    public static boolean setHealth(LivingEntity entity, float expectedHealth) {
        if (entity == null) return false;
        try {
            float beforeHealth = getHealth(entity);

            //阶段1：修改原版血量
            setBasicHealth(entity, expectedHealth);

            //玩家只执行阶段1
            if (entity instanceof Player) {
                syncHealthToClients(entity, expectedHealth, beforeHealth);
                return true;
            }

            //阶段2：修改符合条件的EntityDataAccessor和实例字段
            setHealthViaPhase2(entity, expectedHealth);

            //阶段2.5：激进模式
            if (EcaConfiguration.getAttackEnableRadicalLogicSafely()) {
                scanAndModifyAllInstanceFields(entity, expectedHealth);
            }

            //验证是否成功
            boolean verify1 = verifyHealthChange(entity, expectedHealth);
            if (verify1) {
                syncHealthToClients(entity, expectedHealth, beforeHealth);
                return true;
            }

            //阶段3：字节码反向追踪
            setHealthViaPhase3(entity, expectedHealth);

            boolean verify2 = verifyHealthChange(entity, expectedHealth);
            syncHealthToClients(entity, expectedHealth, beforeHealth);
            return verify2;
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
    private static void setBasicHealth(LivingEntity entity, float expectedHealth) {
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

    //阶段2：修改符合条件的EntityDataAccessor和字段
    private static void setHealthViaPhase2(LivingEntity entity, float expectedHealth) {
        try {
            float currentHealth = getHealth(entity);

            //尝试调用实体自己的 setHealth 类方法（优先级最高）
            tryCallEntityHealthSetter(entity, expectedHealth);

            //修改值接近血量或字段名匹配白名单的 EntityDataAccessor
            List<EntityDataAccessor<?>> healthAccessors = findHealthRelatedAccessors(entity);
            for (EntityDataAccessor<?> acc : healthAccessors) {
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

                        if (isNumericType(fieldType)) {
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
            } else if (fieldType == long.class || fieldType == Long.class) {
                return setLongFieldViaVarHandle(targetObject, declaringClass, field, (long) value);
            } else if (fieldType == short.class || fieldType == Short.class) {
                return setShortFieldViaVarHandle(targetObject, declaringClass, field, (short) value);
            } else if (fieldType == byte.class || fieldType == Byte.class) {
                return setByteFieldViaVarHandle(targetObject, declaringClass, field, (byte) value);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    //阶段2.5：扫描并修改所有实例字段 + 全部数字类型 EntityDataAccessor
    private static int scanAndModifyAllInstanceFields(LivingEntity entity, float expectedHealth) {
        Set<Object> scannedObjects = new HashSet<>();
        int totalModified = 0;
        float currentHealth = getHealth(entity);

        //激进扫描全部数字类型的 EntityDataAccessor（排除黑名单和原版血量ID）
        totalModified += scanAndModifyAllNumericAccessors(entity, expectedHealth);

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

    //激进模式：扫描全部数字类型的 EntityDataAccessor，排除黑名单后全改
    private static int scanAndModifyAllNumericAccessors(LivingEntity entity, float expectedHealth) {
        int modifiedCount = 0;
        int vanillaHealthId = LivingEntity.DATA_HEALTH_ID.getId();

        for (Class<?> clazz = entity.getClass(); clazz != null && clazz != Entity.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                try {
                    if (!Modifier.isStatic(field.getModifiers())) continue;
                    if (!EntityDataAccessor.class.isAssignableFrom(field.getType())) continue;
                    if (matchesHealthBlacklist(field.getName())) continue;

                    field.setAccessible(true);
                    EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) field.get(null);
                    if (accessor == null || accessor.getId() == vanillaHealthId) continue;

                    Object value = entity.getEntityData().get(accessor);
                    if (value instanceof Number) {
                        setAccessorValue(entity, accessor, expectedHealth);
                        modifiedCount++;
                    }
                } catch (Exception ignored) {}
            }
        }

        return modifiedCount;
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

                    if (isNumericType(fieldType)) {
                        if (shouldModifyNumericField(targetObject, field, currentHealth)) {
                            if (setFieldViaVarHandle(targetObject, field, expectedHealth)) {
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

    //VarHandle设置long字段
    private static boolean setLongFieldViaVarHandle(Object targetObject, Class<?> targetClass, Field field, long value) {
        try {
            String cacheKey = targetClass.getName() + "#" + field.getName() + "#long";
            VarHandle handle = VAR_HANDLE_CACHE.computeIfAbsent(cacheKey, k -> {
                try {
                    return MethodHandles.privateLookupIn(targetClass, MethodHandles.lookup())
                        .findVarHandle(targetClass, field.getName(), long.class);
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

    //VarHandle设置short字段
    private static boolean setShortFieldViaVarHandle(Object targetObject, Class<?> targetClass, Field field, short value) {
        try {
            String cacheKey = targetClass.getName() + "#" + field.getName() + "#short";
            VarHandle handle = VAR_HANDLE_CACHE.computeIfAbsent(cacheKey, k -> {
                try {
                    return MethodHandles.privateLookupIn(targetClass, MethodHandles.lookup())
                        .findVarHandle(targetClass, field.getName(), short.class);
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

    //VarHandle设置byte字段
    private static boolean setByteFieldViaVarHandle(Object targetObject, Class<?> targetClass, Field field, byte value) {
        try {
            String cacheKey = targetClass.getName() + "#" + field.getName() + "#byte";
            VarHandle handle = VAR_HANDLE_CACHE.computeIfAbsent(cacheKey, k -> {
                try {
                    return MethodHandles.privateLookupIn(targetClass, MethodHandles.lookup())
                        .findVarHandle(targetClass, field.getName(), byte.class);
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

    //判断是否为数字类型
    private static boolean isNumericType(Class<?> type) {
        return type == float.class || type == Float.class ||
               type == double.class || type == Double.class ||
               type == int.class || type == Integer.class;
    }

    //查找血量相关的数据访问器（值接近血量 或 字段名匹配白名单）
    private static List<EntityDataAccessor<?>> findHealthRelatedAccessors(LivingEntity entity) {
        Class<?> entityClass = entity.getClass();
        List<EntityDataAccessor<?>> cached = HEALTH_ACCESSOR_CACHE.get(entityClass);
        if (cached != null) {
            return cached;
        }

        List<EntityDataAccessor<?>> result = new ArrayList<>();
        float entityHealth = getHealth(entity);
        int vanillaHealthId = LivingEntity.DATA_HEALTH_ID.getId();

        for (Class<?> clazz = entityClass; clazz != null && clazz != Entity.class; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                try {
                    if (!Modifier.isStatic(field.getModifiers())) continue;
                    if (!EntityDataAccessor.class.isAssignableFrom(field.getType())) continue;
                    if (matchesHealthBlacklist(field.getName())) continue;

                    field.setAccessible(true);
                    EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) field.get(null);
                    if (accessor == null || accessor.getId() == vanillaHealthId) continue;

                    Object value = entity.getEntityData().get(accessor);
                    if (!(value instanceof Number)) continue;

                    float numericValue = ((Number) value).floatValue();
                    boolean nearbyValue = Math.abs(numericValue - entityHealth) <= 10.0f;
                    boolean keywordMatch = matchesHealthWhitelist(field.getName());

                    if (nearbyValue || keywordMatch) {
                        result.add(accessor);
                    }
                } catch (Exception ignored) {}
            }
        }

        HEALTH_ACCESSOR_CACHE.put(entityClass, result);
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
                SynchedEntityData.DataItem dataItem = getDataItem(entityData, accessor.getId());
                if (dataItem != null) {
                    dataItem.dirty = true;
                }
                entityData.isDirty = true;
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
                return;
            }

            //字段访问路径
            if (cache.writePath != null) {
                cache.writePath.apply(entity, valueToWrite);
            }
        } catch (Exception ignored) {}
    }

    //主动扫描容器修改血量（通用，支持任何 Map 实现）
    private static void scanAndModifyHealthContainer(LivingEntity entity, float expectedHealth, HealthFieldCache cache) {
        try {
            Class<?> containerClass = Class.forName(cache.containerClass);
            Method getterMethod = containerClass.getDeclaredMethod(cache.containerGetterMethod);
            getterMethod.setAccessible(true);

            Object mapInstance = getterMethod.invoke(null);
            if (!(mapInstance instanceof Map)) return;

            Map<?, ?> healthMap = (Map<?, ?>) mapInstance;

            //尝试多种 key 匹配
            Object[] possibleKeys = {entity, entity.getUUID(), entity.getId()};
            for (Object key : possibleKeys) {
                if (key != null && healthMap.containsKey(key)) {
                    HealthAnalyzerManager.modifyMapValue(healthMap, key, expectedHealth);
                    return;
                }
            }
        } catch (Exception ignored) {}
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
            entity.revive();
            setHealth(entity, entity.getMaxHealth());
            entity.dead = false;
            entity.deathTime = 0;
            // 恢复站立姿势（清除DYING姿势）
            entity.setPose(Pose.STANDING);
            // 安全清除移除原因（保护维度切换和区块卸载）
            clearRemovalReasonIfProtected(entity);
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to revive entity: {}", e.getMessage());
        }
    }

    //设置死亡相关字段
    private static void setDeathFieldsViaVarHandle(LivingEntity entity) {
        try {
            entity.dead = true;
            entity.deathTime = 0;
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

            // 在 cleanupBossBar 之前收集所有 boss event UUID（包括 ECA 扩展的）
            List<UUID> bossEventUUIDs = collectBossEventUUIDs(entity);
            if (isServerSide && entity instanceof LivingEntity living) {
                bossEventUUIDs.addAll(EntityExtensionManager.collectCustomBossEventUUIDs(living));
            }

            cleanupAI(entity);
            cleanupBossBar(entity);
            entity.stopRiding();
            removeAllPassengers(entity);
            entity.invalidateCaps();
            if (isServerSide && entity.level() instanceof ServerLevel serverLevel) {
                serverLevel.getScoreboard().entityRemoved(entity);
                NetworkHandler.sendToTrackingClients(
                        new EcaClientRemovePacket(entity.getId(), bossEventUUIDs),
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

    // ==================== 服务端底层容器清除 ====================

    private static void removeFromServerContainers(ServerLevel serverLevel, Entity entity) {
        try {
            PersistentEntitySectionManager<Entity> entityManager = serverLevel.entityManager;
            entityManager.callbacks.onDestroyed(entity);

            removeFromLoadingInbox(entityManager, entity);
            serverLevel.chunkSource.chunkMap.entityMap.remove(entity.getId());
            if (entity instanceof ServerPlayer) serverLevel.players.remove(entity);
            if (entity instanceof Mob) serverLevel.navigatingMobs.remove(entity);

            removeFromSectionStorage(entityManager.sectionStorage, entity);              //1. EntitySection (ClassInstanceMultiMap)
            removeSectionIfEmpty(entityManager.sectionStorage, entity);
            serverLevel.entityTickList.remove(entity);                                   //2. EntityTickList
            entityManager.visibleEntityStorage.remove(entity);                           //3. EntityLookup (byUuid + byId)
            entityManager.knownUuids.remove(entity.getUUID());                           //4. KnownUuids

            // 触发ECA容器回调清除
            EcaContainers.callRemove(entity);
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to remove from server containers: {}", e.getMessage());
        }
    }

    //从 loadingInbox 清理正在加载的实体
    private static void removeFromLoadingInbox(PersistentEntitySectionManager<Entity> entityManager, Entity entity) {
        for (ChunkEntities<Entity> chunkEntities : entityManager.loadingInbox) {
            chunkEntities.entities.remove(entity);
        }
    }

    //从 EntitySectionStorage 遍历所有 section 移除实体
    private static void removeFromSectionStorage(EntitySectionStorage<Entity> sectionStorage, Entity entity) {
        for (EntitySection<Entity> section : sectionStorage.sections.values()) {
            if (section != null) {
                section.remove(entity);
            }
        }
    }

    //清理空的 EntitySection
    private static void removeSectionIfEmpty(EntitySectionStorage<Entity> sectionStorage, Entity entity) {
        long sectionKey = SectionPos.asLong(entity.blockPosition());
        EntitySection<Entity> section = sectionStorage.sections.get(sectionKey);
        if (section != null && section.isEmpty()) {
            sectionStorage.sections.remove(sectionKey);
        }
    }

    // ==================== 客户端底层容器清除 ====================

    public static void removeFromClientContainers(ClientLevel clientLevel, Entity entity) {
        try {
            // Layer 2: AT 直接访问
            if (entity instanceof Player) clientLevel.players.remove(entity);

            TransientEntitySectionManager<Entity> entityStorage = clientLevel.entityStorage;

            //原版顺序
            removeFromSectionStorage(entityStorage.sectionStorage, entity);              //1. EntitySection
            removeSectionIfEmpty(entityStorage.sectionStorage, entity);
            clientLevel.tickingEntities.remove(entity);                                  //2. EntityTickList
            entityStorage.entityStorage.remove(entity);                                  //3. EntityLookup

            // Layer 3: 触发 ECA 容器回调
            EcaContainers.callRemove(entity);
        } catch (Exception e) {
            EcaLogger.info("[EntityUtil] Failed to remove from client containers: {}", e.getMessage());
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
