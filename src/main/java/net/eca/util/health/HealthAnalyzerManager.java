package net.eca.util.health;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import net.eca.util.EcaLogger;
import net.eca.util.health.HealthAnalyzer.HealthFieldCache;
import net.eca.util.health.HealthAnalyzer.ContainerAccessPattern;
import net.eca.util.reflect.LwjglUtil;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

//Runtime manager for health analysis system
@SuppressWarnings("unchecked")
public class HealthAnalyzerManager {

    //Class cache: tracks which entity classes have been analyzed
    private static final Map<Class<?>, HealthFieldCache> cache = new ConcurrentHashMap<>();

    //Entry value 字段偏移量缓存: Entry class -> offset
    private static final Map<Class<?>, Long> ENTRY_VALUE_OFFSET_CACHE = new ConcurrentHashMap<>();

    //Hook entry point: called when entity's getHealth() is invoked
    public static void onGetHealthCalled(LivingEntity entity, String className) {
        Class<?> entityClass = entity.getClass();

        //Check cache
        HealthFieldCache fieldCache = cache.get(entityClass);

        if (fieldCache == null) {
            //First time encountering this entity class, start analysis
            analyzeAndCache(entity, className);
        }
    }

    //公共方法：直接触发分析（用于未被 Hook 拦截的类）
    public static void triggerAnalysis(LivingEntity entity) {
        Class<?> entityClass = entity.getClass();

        //Check if already cached
        if (cache.containsKey(entityClass)) {
            return;
        }

        String className = entityClass.getName().replace('.', '/');
        analyzeAndCache(entity, className);
    }

    //Analyze entity's getHealth() implementation and cache results
    private static void analyzeAndCache(LivingEntity entity, String className) {
        try {
            //1. Get entity class
            Class<?> entityClass = entity.getClass();

            //2. Analyze bytecode
            HealthAnalyzer.AnalysisResult analysisResult = HealthAnalyzer.analyze(entityClass);

            if (analysisResult == null || !analysisResult.foundMethod) {
                createDefaultCache(entityClass);
                return;
            }

            if (!analysisResult.foundMinimalUnit) {
                createDefaultCache(entityClass);
                return;
            }

            //3. Create cache from analysis results
            HealthFieldCache fieldCache = new HealthFieldCache();
            fieldCache.reverseTransform = analysisResult.reverseFormula;

            //4. Build accessPattern and writePath based on source type
            if (analysisResult.dataSource.sourceType == HealthAnalyzer.SourceType.DIRECT_FIELD) {
                buildFieldAccessPattern(fieldCache, analysisResult, entityClass);
            } else if (analysisResult.dataSource.sourceType == HealthAnalyzer.SourceType.METHOD_CALL) {
                buildContainerAccessPattern(fieldCache, analysisResult, entityClass);
            }

            cache.put(entityClass, fieldCache);

        } catch (Throwable t) {
            EcaLogger.error("[HealthAnalyzerManager] Failed to analyze {}", className, t);
            createDefaultCache(entity.getClass());
        }
    }

    //Build access pattern for direct field (支持嵌套字段路径)
    private static void buildFieldAccessPattern(HealthFieldCache cache, HealthAnalyzer.AnalysisResult analysis, Class<?> entityClass) {
        try {
            HealthAnalyzer.DataSourceInfo dataSource = analysis.dataSource;

            //检查是否有字段访问路径（嵌套字段）
            if (analysis.fieldAccessPath.isEmpty()) {
                EcaLogger.warn("[HealthAnalyzerManager] No field access path found");
                return;
            }

            //为每个字段步骤创建 VarHandle
            VarHandle[] fieldHandles = new VarHandle[analysis.fieldAccessPath.size()];

            for (int i = 0; i < analysis.fieldAccessPath.size(); i++) {
                HealthAnalyzer.FieldAccessStep step = analysis.fieldAccessPath.get(i);
                String ownerClass = step.ownerClass.replace('/', '.');
                String fieldName = step.fieldName;
                String descriptor = step.descriptor;

                //Get field type
                Class<?> fieldType = descriptorToClass(descriptor);
                if (fieldType == null) {
                    EcaLogger.warn("[HealthAnalyzerManager] Cannot convert descriptor: {}", descriptor);
                    return;
                }

                //Get owner class
                Class<?> ownerClazz = Class.forName(ownerClass);

                //Create VarHandle
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ownerClazz, MethodHandles.lookup());
                fieldHandles[i] = lookup.findVarHandle(ownerClazz, fieldName, fieldType);
            }

            //最后一个 VarHandle 用于修改最终字段
            VarHandle finalFieldHandle = fieldHandles[fieldHandles.length - 1];

            //Build accessPattern
            ContainerAccessPattern pattern = new ContainerAccessPattern();
            pattern.containerGetter = (entity) -> entity;
            pattern.keyBuilder = (entity) -> null;
            pattern.valueLocator = (container, key) -> {
                //沿着字段路径导航到最终对象
                Object current = container;
                for (int i = 0; i < fieldHandles.length - 1; i++) {
                    current = fieldHandles[i].get(current);
                    if (current == null) {
                        EcaLogger.warn("[HealthAnalyzerManager] Null value at field path step {}", i);
                        return null;
                    }
                }
                return current;  //返回最终字段的持有者对象
            };
            pattern.valueHandle = finalFieldHandle;
            pattern.isArrayElement = false;

            cache.accessPattern = pattern;

            //Build writePath
            cache.writePath = (entity, value) -> {
                try {
                    //导航到最终对象
                    Object target = pattern.valueLocator.locateValueHolder(entity, null);
                    if (target == null) {
                        EcaLogger.warn("[HealthAnalyzerManager] Cannot locate target object");
                        return false;
                    }

                    //修改最终字段
                    finalFieldHandle.set(target, value);
                    return Math.abs(entity.getHealth() - value) <= 2.0f;
                } catch (Exception e) {
                    EcaLogger.error("[HealthAnalyzerManager] Failed to write nested field", e);
                    return false;
                }
            };

        } catch (Exception e) {
            EcaLogger.error("[HealthAnalyzerManager] Failed to build field access pattern", e);
        }
    }

    //Build access pattern for container (Map/ArrayList/EntityData)
    private static void buildContainerAccessPattern(HealthFieldCache cache, HealthAnalyzer.AnalysisResult analysis, Class<?> entityClass) {
        HealthAnalyzer.DataSourceInfo dataSource = analysis.dataSource;
        String owner = dataSource.owner;

        if (owner.contains("SynchedEntityData")) {
            buildEntityDataAccessPattern(cache, analysis, entityClass);
        } else if (owner.contains("Map") || owner.contains("HashMap")) {
            buildMapAccessPattern(cache, analysis, entityClass);
        } else {
            EcaLogger.warn("[HealthAnalyzerManager] Unsupported container type: {}", owner);
        }
    }

    //通用 Map 容器修改 - 使用 entrySet() + Unsafe，支持任何 Map 实现
    private static void buildMapAccessPattern(HealthFieldCache cache, HealthAnalyzer.AnalysisResult analysis, Class<?> entityClass) {
        try {
            String getterMethod = analysis.hashMapContainerGetterMethod;
            String getterOwner = analysis.hashMapContainerGetterOwner;
            HealthAnalyzer.StackElement keySource = analysis.hashMapKeySource;

            if (getterMethod == null || getterOwner == null) {
                EcaLogger.warn("[HealthAnalyzerManager] Missing Map container getter information");
                return;
            }

            cache.containerDetected = true;
            cache.containerClass = getterOwner.replace('/', '.');
            cache.containerGetterMethod = getterMethod;

            Class<?> getterClass = Class.forName(getterOwner.replace('/', '.'));
            Method containerGetterMethod = null;

            for (Method m : getterClass.getDeclaredMethods()) {
                if (m.getName().equals(getterMethod) && m.getParameterCount() == 0) {
                    containerGetterMethod = m;
                    containerGetterMethod.setAccessible(true);
                    break;
                }
            }

            if (containerGetterMethod == null) {
                EcaLogger.warn("[HealthAnalyzerManager] Cannot find container getter method: {}.{}", getterOwner, getterMethod);
                return;
            }

            final Method finalGetterMethod = containerGetterMethod;

            ContainerAccessPattern.KeyBuilder keyBuilder = buildMapKeyBuilder(keySource);

            cache.writePath = (entity, value) -> {
                try {
                    Object mapInstance = finalGetterMethod.invoke(null);
                    if (!(mapInstance instanceof Map)) return false;

                    Map<?, ?> map = (Map<?, ?>) mapInstance;
                    Object key = keyBuilder.buildKey(entity);

                    //尝试多种 key 匹配
                    Object matchedKey = null;
                    if (key != null && map.containsKey(key)) {
                        matchedKey = key;
                    } else {
                        Object[] possibleKeys = {entity, entity.getUUID(), entity.getId()};
                        for (Object k : possibleKeys) {
                            if (k != null && map.containsKey(k)) {
                                matchedKey = k;
                                break;
                            }
                        }
                    }
                    if (matchedKey == null) return false;

                    return unsafeModifyMapEntry(map, matchedKey, value);
                } catch (Exception e) {
                    EcaLogger.error("[HealthAnalyzerManager] Failed to write Map value", e);
                    return false;
                }
            };

        } catch (Exception e) {
            EcaLogger.error("[HealthAnalyzerManager] Failed to build Map access pattern", e);
        }
    }

    //通用 Unsafe Map Entry 修改：遍历 entrySet，用字段名定位 value 字段，Unsafe 写入绕过 mod 拦截
    @SuppressWarnings("rawtypes")
    private static boolean unsafeModifyMapEntry(Map<?, ?> map, Object targetKey, float newValue) {
        try {
            for (Map.Entry entry : map.entrySet()) {
                Object entryKey = entry.getKey();
                if (entryKey == targetKey || (targetKey != null && targetKey.equals(entryKey))) {
                    Object currentValue = entry.getValue();
                    if (currentValue == null) return false;

                    //根据当前值类型转换新值
                    Object boxedValue;
                    if (currentValue instanceof Double) {
                        boxedValue = (double) newValue;
                    } else if (currentValue instanceof Integer) {
                        boxedValue = (int) newValue;
                    } else {
                        boxedValue = newValue;
                    }

                    //通过字段名定位 value 字段偏移量（缓存）
                    long offset = getEntryValueOffset(entry);
                    if (offset == -1) return false;

                    //Unsafe 直接写入
                    LwjglUtil.lwjglPutObject(entry, offset, boxedValue);
                    return true;
                }
            }
        } catch (Exception e) {
            EcaLogger.error("[HealthAnalyzerManager] Failed to modify Map entry via Unsafe", e);
        }
        return false;
    }

    //通过字段名定位 Entry 的 value 字段偏移量（按 Entry class 缓存）
    private static long getEntryValueOffset(Object entry) {
        Class<?> entryClass = entry.getClass();

        Long cached = ENTRY_VALUE_OFFSET_CACHE.get(entryClass);
        if (cached != null) return cached;

        //按字段名匹配：覆盖 HashMap$Node.value、WeakHashMap$Entry.value、ConcurrentHashMap$Node.val
        Class<?> cls = entryClass;
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                String name = f.getName();
                if (name.equals("value") || name.equals("val")) {
                    long offset = LwjglUtil.lwjglObjectFieldOffset(f);
                    if (offset != -1) {
                        ENTRY_VALUE_OFFSET_CACHE.put(entryClass, offset);
                        return offset;
                    }
                }
            }
            cls = cls.getSuperclass();
        }

        EcaLogger.warn("[HealthAnalyzerManager] Cannot find value/val field in Entry class: {}", entryClass.getName());
        return -1;
    }

    //Build Map key builder from stack element
    private static ContainerAccessPattern.KeyBuilder buildMapKeyBuilder(HealthAnalyzer.StackElement keySource) {
        if (keySource == null) {
            return (entity) -> entity;
        }

        switch (keySource.type) {
            case THIS_REF:
            case LOCAL_VAR:
                return (entity) -> entity;

            case CONSTANT:
                Object constantKey = keySource.value;
                return (entity) -> constantKey;

            case METHOD_RESULT:
                String methodName = (String) keySource.value;
                String ownerClassName = keySource.owner;

                try {
                    Class<?> ownerClass = Class.forName(ownerClassName.replace('/', '.'));
                    Method keyMethod = null;

                    for (Method m : ownerClass.getDeclaredMethods()) {
                        if (m.getName().equals(methodName) && m.getParameterCount() == 0) {
                            keyMethod = m;
                            keyMethod.setAccessible(true);
                            break;
                        }
                    }

                    if (keyMethod == null) {
                        for (Method m : ownerClass.getMethods()) {
                            if (m.getName().equals(methodName) && m.getParameterCount() == 0) {
                                keyMethod = m;
                                keyMethod.setAccessible(true);
                                break;
                            }
                        }
                    }

                    if (keyMethod != null) {
                        Method finalKeyMethod = keyMethod;
                        return (entity) -> {
                            try {
                                return finalKeyMethod.invoke(entity);
                            } catch (Exception e) {
                                return entity;
                            }
                        };
                    }
                } catch (Exception e) {
                    EcaLogger.error("[HealthAnalyzerManager] Failed to build METHOD_RESULT key", e);
                }
                return (entity) -> entity;

            default:
                return (entity) -> entity;
        }
    }

    //Build EntityData access pattern
    private static void buildEntityDataAccessPattern(HealthFieldCache cache, HealthAnalyzer.AnalysisResult analysis, Class<?> entityClass) {
        try {
            String accessorName = analysis.entityDataAccessorName;
            String accessorOwner = analysis.entityDataAccessorOwner;

            if (accessorName == null || accessorOwner == null) {
                EcaLogger.warn("[HealthAnalyzerManager] Missing EntityData accessor information");
                return;
            }

            Class<?> accessorClass = Class.forName(accessorOwner.replace('/', '.'));
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(accessorClass, MethodHandles.lookup());
            VarHandle accessorHandle = lookup.findStaticVarHandle(accessorClass, accessorName, EntityDataAccessor.class);

            ContainerAccessPattern pattern = new ContainerAccessPattern();
            pattern.containerGetter = (entity) -> entity.getEntityData();
            pattern.keyBuilder = (entity) -> (EntityDataAccessor<?>) accessorHandle.get();
            pattern.valueLocator = (container, key) -> {
                SynchedEntityData entityData = (SynchedEntityData) container;
                EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) key;
                return getDataItem(entityData, accessor.getId());
            };
            pattern.isArrayElement = false;

            cache.accessPattern = pattern;

            cache.writePath = (entity, value) -> {
                try {
                    SynchedEntityData entityData = entity.getEntityData();
                    EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) pattern.keyBuilder.buildKey(entity);
                    if (accessor == null) return false;

                    SynchedEntityData.DataItem dataItem = getDataItem(entityData, accessor.getId());
                    if (dataItem == null) return false;

                    dataItem.value = value;
                    dataItem.dirty = true;
                    entityData.isDirty = true;

                    return true;
                } catch (Exception e) {
                    EcaLogger.error("[HealthAnalyzerManager] Failed to write EntityData value", e);
                    return false;
                }
            };

        } catch (Exception e) {
            EcaLogger.error("[HealthAnalyzerManager] Failed to build EntityData access pattern", e);
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

    //Create default cache (used when analysis fails)
    private static void createDefaultCache(Class<?> entityClass) {
        EcaLogger.warn("[HealthAnalyzerManager] Creating default empty cache for {}", entityClass.getSimpleName());
        HealthFieldCache fieldCache = new HealthFieldCache();
        cache.put(entityClass, fieldCache);
    }

    //Get cache (used when modifying health externally)
    public static HealthFieldCache getCache(Class<?> entityClass) {
        return cache.get(entityClass);
    }

    //Check if entity class should trigger hook
    public static boolean shouldHook(Class<?> entityClass) {
        return !cache.containsKey(entityClass);
    }

    //Convert JVM type descriptor to Class
    private static Class<?> descriptorToClass(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) return null;

        switch (descriptor.charAt(0)) {
            case 'F': return float.class;
            case 'D': return double.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'S': return short.class;
            case 'B': return byte.class;
            case 'C': return char.class;
            case 'Z': return boolean.class;
            case 'L':
                String className = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
                try {
                    return Class.forName(className);
                } catch (ClassNotFoundException e) {
                    return null;
                }
            case '[':
                return null;
            default:
                return null;
        }
    }

    //通用 Unsafe Map 修改（供 EntityUtil 主动扫描使用）
    public static boolean modifyMapValue(Map<?, ?> map, Object key, float newValue) {
        return unsafeModifyMapEntry(map, key, newValue);
    }
}
