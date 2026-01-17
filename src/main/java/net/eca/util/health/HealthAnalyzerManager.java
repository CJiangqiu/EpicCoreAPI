package net.eca.util.health;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import net.eca.util.EcaLogger;
import net.eca.util.reflect.ObfuscationMapping;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

//Runtime manager for health analysis system
@SuppressWarnings("unchecked")
public class HealthAnalyzerManager {

    //Class cache: tracks which entity classes have been analyzed
    private static final Map<Class<?>, HealthFieldCache> cache = new ConcurrentHashMap<>();

    //SynchedEntityData 字段（使用ObfuscationReflectionHelper处理混淆）
    private static Field ITEMS_BY_ID_FIELD;
    private static Field IS_DIRTY_FIELD;

    //DataItem VarHandles
    private static VarHandle DATA_ITEM_VALUE_HANDLE;
    private static VarHandle DATA_ITEM_DIRTY_HANDLE;

    //HashMap VarHandles (JDK 类，不会混淆)
    private static VarHandle HASH_MAP_TABLE;
    private static VarHandle NODE_HASH;
    private static VarHandle NODE_KEY;
    private static VarHandle NODE_VALUE;
    private static VarHandle NODE_NEXT;

    //WeakHashMap VarHandles
    private static VarHandle WEAK_HASH_MAP_TABLE;
    private static VarHandle WEAK_ENTRY_HASH;
    private static VarHandle WEAK_ENTRY_KEY;
    private static VarHandle WEAK_ENTRY_VALUE;
    private static VarHandle WEAK_ENTRY_NEXT;

    //静态初始化：获取SynchedEntityData和DataItem的反射字段
    static {
        try {
            //SynchedEntityData字段
            String itemsByIdObfName = ObfuscationMapping.getFieldMapping("SynchedEntityData.itemsById");
            String isDirtyObfName = ObfuscationMapping.getFieldMapping("SynchedEntityData.isDirty");
            ITEMS_BY_ID_FIELD = ObfuscationReflectionHelper.findField(SynchedEntityData.class, itemsByIdObfName);
            IS_DIRTY_FIELD = ObfuscationReflectionHelper.findField(SynchedEntityData.class, isDirtyObfName);

            //DataItem字段
            Class<?> dataItemClass = SynchedEntityData.DataItem.class;
            String valueObfName = ObfuscationMapping.getFieldMapping("DataItem.value");
            String dirtyObfName = ObfuscationMapping.getFieldMapping("DataItem.dirty");
            Field valueField = ObfuscationReflectionHelper.findField(dataItemClass, valueObfName);
            Field dirtyField = ObfuscationReflectionHelper.findField(dataItemClass, dirtyObfName);

            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(dataItemClass, MethodHandles.lookup());
            DATA_ITEM_VALUE_HANDLE = lookup.unreflectVarHandle(valueField);
            DATA_ITEM_DIRTY_HANDLE = lookup.unreflectVarHandle(dirtyField);

        } catch (Exception e) {
            EcaLogger.error("[HealthAnalyzerManager] Failed to initialize SynchedEntityData fields", e);
        }
    }

    //初始化 HashMap VarHandles（通用，支持 HashMap/LinkedHashMap/WeakHashMap）
    public static void initHashMapVarHandles(Class<?> mapClass) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            //优先检查 WeakHashMap（因为它不继承 HashMap）
            if (WeakHashMap.class.isAssignableFrom(mapClass)) {
                //WeakHashMap 分支
                if (WEAK_HASH_MAP_TABLE == null) {
                    Class<?> entryClass = Class.forName("java.util.WeakHashMap$Entry");
                    Class<?> entryArrayClass = Class.forName("[Ljava.util.WeakHashMap$Entry;");
                    Class<?> referenceClass = Class.forName("java.lang.ref.Reference");

                    MethodHandles.Lookup mapLookup = MethodHandles.privateLookupIn(WeakHashMap.class, lookup);
                    WEAK_HASH_MAP_TABLE = mapLookup.findVarHandle(WeakHashMap.class, "table", entryArrayClass);

                    MethodHandles.Lookup entryLookup = MethodHandles.privateLookupIn(entryClass, lookup);
                    WEAK_ENTRY_HASH = entryLookup.findVarHandle(entryClass, "hash", int.class);
                    WEAK_ENTRY_VALUE = entryLookup.findVarHandle(entryClass, "value", Object.class);
                    WEAK_ENTRY_NEXT = entryLookup.findVarHandle(entryClass, "next", entryClass);

                    //key 存储在父类 Reference.referent 字段中
                    MethodHandles.Lookup referenceLookup = MethodHandles.privateLookupIn(referenceClass, lookup);
                    WEAK_ENTRY_KEY = referenceLookup.findVarHandle(referenceClass, "referent", Object.class);
                }
            } else if (HashMap.class.isAssignableFrom(mapClass)) {
                //HashMap 分支（包括 LinkedHashMap）
                //LinkedHashMap 的 Entry 继承 HashMap.Node，所以可以兼容
                if (HASH_MAP_TABLE == null) {
                    MethodHandles.Lookup mapLookup = MethodHandles.privateLookupIn(HashMap.class, lookup);
                    HASH_MAP_TABLE = mapLookup.findVarHandle(HashMap.class, "table", Object[].class);

                    Class<?> nodeClass = Class.forName("java.util.HashMap$Node");
                    MethodHandles.Lookup nodeLookup = MethodHandles.privateLookupIn(nodeClass, lookup);
                    NODE_HASH = nodeLookup.findVarHandle(nodeClass, "hash", int.class);
                    NODE_KEY = nodeLookup.findVarHandle(nodeClass, "key", Object.class);
                    NODE_VALUE = nodeLookup.findVarHandle(nodeClass, "value", Object.class);
                    NODE_NEXT = nodeLookup.findVarHandle(nodeClass, "next", nodeClass);
                }
            } else {
                //不支持的 Map 类型
                EcaLogger.error("[HealthAnalyzerManager] Unsupported map type: {} - Only HashMap, LinkedHashMap, and WeakHashMap are supported", mapClass.getName());
                throw new IllegalArgumentException("Unsupported map type: " + mapClass.getName());
            }

        } catch (Exception e) {
            EcaLogger.error("[HealthAnalyzerManager] Failed to initialize HashMap VarHandles for " + mapClass.getName(), e);
            throw new RuntimeException("Failed to initialize HashMap VarHandles", e);
        }
    }

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
                EcaLogger.warn("[HealthAnalyzerManager] getHealth() method not found in {}", className);
                createDefaultCache(entityClass);
                return;
            }

            if (!analysisResult.foundMinimalUnit) {
                EcaLogger.warn("[HealthAnalyzerManager] No minimal writable unit found for {}", className);
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

    //Build access pattern for container (HashMap/ArrayList/EntityData)
    private static void buildContainerAccessPattern(HealthFieldCache cache, HealthAnalyzer.AnalysisResult analysis, Class<?> entityClass) {
        HealthAnalyzer.DataSourceInfo dataSource = analysis.dataSource;
        String owner = dataSource.owner;
        String name = dataSource.name;

        //Detect container type
        if (owner.contains("HashMap") || owner.contains("Map")) {
            buildHashMapAccessPattern(cache, analysis, entityClass);
        } else if (owner.contains("ArrayList")) {
            buildArrayListAccessPattern(cache, analysis, entityClass);
        } else if (owner.contains("SynchedEntityData")) {
            buildEntityDataAccessPattern(cache, analysis, entityClass);
        } else {
            EcaLogger.warn("[HealthAnalyzerManager] Unsupported container type: {}", owner);
        }
    }

    //Build HashMap access pattern
    private static void buildHashMapAccessPattern(HealthFieldCache cache, HealthAnalyzer.AnalysisResult analysis, Class<?> entityClass) {
        try {
            //提取容器 getter 方法信息
            String getterMethod = analysis.hashMapContainerGetterMethod;
            String getterOwner = analysis.hashMapContainerGetterOwner;
            HealthAnalyzer.StackElement keySource = analysis.hashMapKeySource;

            if (getterMethod == null || getterOwner == null) {
                EcaLogger.warn("[HealthAnalyzerManager] Missing HashMap container getter information");
                return;
            }

            //缓存容器信息用于主动扫描
            cache.containerDetected = true;
            cache.containerClass = getterOwner.replace('/', '.');
            cache.containerGetterMethod = getterMethod;

            //获取容器 getter 方法的 Class 和 Method
            Class<?> getterClass = Class.forName(getterOwner.replace('/', '.'));
            Method containerGetterMethod = null;

            //查找静态方法（无参数）
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

            //获取 HashMap 实例以确定类型
            Object mapInstance = finalGetterMethod.invoke(null);
            if (mapInstance == null) {
                EcaLogger.warn("[HealthAnalyzerManager] HashMap container is null");
                return;
            }

            Class<?> mapClass = mapInstance.getClass();

            //缓存容器类型
            cache.containerType = mapClass.getSimpleName();

            //初始化对应类型的 VarHandles
            initHashMapVarHandles(mapClass);

            //判断是 HashMap 还是 WeakHashMap
            boolean isWeakHashMap = WeakHashMap.class.isAssignableFrom(mapClass);

            ContainerAccessPattern pattern = new ContainerAccessPattern();

            //Container getter: 调用静态方法获取 HashMap
            pattern.containerGetter = (entity) -> {
                return finalGetterMethod.invoke(null);
            };

            //Key builder: 根据 keySource 类型构建
            pattern.keyBuilder = buildHashMapKeyBuilder(keySource);

            //Value locator: 在 HashMap 中查找 Node/Entry
            if (isWeakHashMap) {
                pattern.valueLocator = (container, key) -> {
                    return findWeakHashMapEntry(container, key);
                };
                pattern.valueHandle = WEAK_ENTRY_VALUE;
            } else {
                pattern.valueLocator = (container, key) -> {
                    return findHashMapNode(container, key);
                };
                pattern.valueHandle = NODE_VALUE;
            }

            pattern.isArrayElement = false;

            cache.accessPattern = pattern;

            //Build writePath
            cache.writePath = (entity, value) -> {
                try {
                    Object container = pattern.containerGetter.getContainer(entity);
                    if (container == null) return false;

                    Object key = pattern.keyBuilder.buildKey(entity);
                    if (key == null) return false;

                    Object node = pattern.valueLocator.locateValueHolder(container, key);
                    if (node == null) return false;

                    pattern.valueHandle.set(node, value);
                    return true;
                } catch (Exception e) {
                    EcaLogger.error("[HealthAnalyzerManager] Failed to write HashMap value", e);
                    return false;
                }
            };

        } catch (Exception e) {
            EcaLogger.error("[HealthAnalyzerManager] Failed to build HashMap access pattern", e);
        }
    }

    //Build HashMap key builder from stack element
    private static ContainerAccessPattern.KeyBuilder buildHashMapKeyBuilder(HealthAnalyzer.StackElement keySource) {
        if (keySource == null) {
            EcaLogger.warn("[HealthAnalyzerManager] No key source, using entity as key");
            return (entity) -> entity;
        }

        switch (keySource.type) {
            case THIS_REF:
                return (entity) -> entity;

            case LOCAL_VAR:
                //静态方法中的 ALOAD_0 是第一个参数（通常是 entity）
                return (entity) -> entity;

            case CONSTANT:
                Object constantKey = keySource.value;
                return (entity) -> constantKey;

            case FIELD_VALUE:
                //TODO: 实现字段访问
                EcaLogger.warn("[HealthAnalyzerManager] Field-based key not implemented yet");
                return (entity) -> entity;

            case METHOD_RESULT:
                //Key 通过调用方法获取（例如 getRecordMaxHp()）
                String methodName = (String) keySource.value;
                String ownerClassName = keySource.owner;

                try {
                    Class<?> ownerClass = Class.forName(ownerClassName.replace('/', '.'));
                    Method keyMethod = null;

                    //尝试查找方法（无参数）
                    for (Method m : ownerClass.getDeclaredMethods()) {
                        if (m.getName().equals(methodName) && m.getParameterCount() == 0) {
                            keyMethod = m;
                            keyMethod.setAccessible(true);
                            break;
                        }
                    }

                    if (keyMethod == null) {
                        //尝试在父类中查找
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
                                Object result = finalKeyMethod.invoke(entity);
                                //如果是基本类型，需要装箱
                                if (result instanceof Float) {
                                    return result;
                                }
                                return result;
                            } catch (Exception e) {
                                EcaLogger.error("[HealthAnalyzerManager] Failed to invoke key method", e);
                                return entity;
                            }
                        };
                    }
                } catch (Exception e) {
                    EcaLogger.error("[HealthAnalyzerManager] Failed to build METHOD_RESULT key", e);
                }

                EcaLogger.warn("[HealthAnalyzerManager] Failed to resolve METHOD_RESULT key, using entity as fallback");
                return (entity) -> entity;

            default:
                EcaLogger.warn("[HealthAnalyzerManager] Unknown key type: {}", keySource.type);
                return (entity) -> entity;
        }
    }

    //Find Node in HashMap by key
    private static Object findHashMapNode(Object map, Object key) {
        try {
            Object[] table = (Object[]) HASH_MAP_TABLE.get(map);
            if (table == null || table.length == 0) {
                return null;
            }

            int hash = hash(key);
            int index = (table.length - 1) & hash;

            Object node = table[index];
            while (node != null) {
                int nodeHash = (int) NODE_HASH.get(node);
                Object nodeKey = NODE_KEY.get(node);

                if (nodeHash == hash && (nodeKey == key || (key != null && key.equals(nodeKey)))) {
                    return node;
                }

                node = NODE_NEXT.get(node);
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    //Find Entry in WeakHashMap by key
    private static Object findWeakHashMapEntry(Object map, Object key) {
        try {
            Object[] table = (Object[]) WEAK_HASH_MAP_TABLE.get(map);
            if (table == null || table.length == 0) {
                return null;
            }

            int hash = hash(key);
            int index = (table.length - 1) & hash;

            Object entry = table[index];
            while (entry != null) {
                int entryHash = (int) WEAK_ENTRY_HASH.get(entry);
                Object entryKey = WEAK_ENTRY_KEY.get(entry);

                if (entryHash == hash && (entryKey == key || (key != null && key.equals(entryKey)))) {
                    return entry;
                }

                entry = WEAK_ENTRY_NEXT.get(entry);
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    //HashMap hash function
    private static int hash(Object key) {
        int h;
        return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    }

    //Build ArrayList access pattern
    private static void buildArrayListAccessPattern(HealthFieldCache cache, HealthAnalyzer.AnalysisResult analysis, Class<?> entityClass) {
        EcaLogger.warn("[HealthAnalyzerManager] ArrayList access pattern not implemented yet");
        //TODO: 实现 ArrayList 支持
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

            //使用静态初始化的VarHandles（已通过ObfuscationMapping处理混淆）
            if (DATA_ITEM_VALUE_HANDLE == null || DATA_ITEM_DIRTY_HANDLE == null) {
                EcaLogger.error("[HealthAnalyzerManager] DataItem VarHandles not initialized");
                return;
            }

            ContainerAccessPattern pattern = new ContainerAccessPattern();
            pattern.containerGetter = (entity) -> entity.getEntityData();
            pattern.keyBuilder = (entity) -> (EntityDataAccessor<?>) accessorHandle.get();
            pattern.valueLocator = (container, key) -> {
                SynchedEntityData entityData = (SynchedEntityData) container;
                EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) key;
                return getDataItem(entityData, accessor.getId());
            };
            pattern.valueHandle = DATA_ITEM_VALUE_HANDLE;
            pattern.isArrayElement = false;

            cache.accessPattern = pattern;

            cache.writePath = (entity, value) -> {
                try {
                    Object container = pattern.containerGetter.getContainer(entity);
                    Object key = pattern.keyBuilder.buildKey(entity);
                    if (key == null) return false;

                    Object dataItem = pattern.valueLocator.locateValueHolder(container, key);
                    if (dataItem == null) return false;

                    pattern.valueHandle.set(dataItem, value);
                    DATA_ITEM_DIRTY_HANDLE.set(dataItem, true);
                    setIsDirty((SynchedEntityData) container, true);

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

    //获取DataItem（通过反射访问itemsById）
    private static SynchedEntityData.DataItem<?> getDataItem(SynchedEntityData entityData, int id) {
        try {
            if (ITEMS_BY_ID_FIELD == null) return null;
            Object itemsById = ITEMS_BY_ID_FIELD.get(entityData);
            if (itemsById instanceof Int2ObjectMap) {
                return (SynchedEntityData.DataItem<?>) ((Int2ObjectMap<?>) itemsById).get(id);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    //设置isDirty标记（通过反射）
    private static void setIsDirty(SynchedEntityData entityData, boolean dirty) {
        try {
            if (IS_DIRTY_FIELD != null) {
                IS_DIRTY_FIELD.set(entityData, dirty);
            }
        } catch (Exception ignored) {}
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

    //公开 VarHandle 访问方法供 EntityUtil 使用

    //HashMap 访问方法
    public static Object getHashMapTable(Object map) {
        try {
            return HASH_MAP_TABLE.get(map);
        } catch (Exception e) {
            return null;
        }
    }

    public static Object getHashMapNodeKey(Object node) {
        try {
            return NODE_KEY.get(node);
        } catch (Exception e) {
            return null;
        }
    }

    public static void setHashMapNodeValue(Object node, Object value) {
        try {
            NODE_VALUE.set(node, value);
        } catch (Exception e) {
            EcaLogger.error("[HealthAnalyzerManager] Failed to set HashMap node value", e);
        }
    }

    public static Object getHashMapNodeNext(Object node) {
        try {
            return NODE_NEXT.get(node);
        } catch (Exception e) {
            return null;
        }
    }

    //WeakHashMap 访问方法
    public static Object getWeakHashMapTable(Object map) {
        try {
            return WEAK_HASH_MAP_TABLE.get(map);
        } catch (Exception e) {
            return null;
        }
    }

    public static Object getWeakHashMapEntryKey(Object entry) {
        try {
            return WEAK_ENTRY_KEY.get(entry);
        } catch (Exception e) {
            return null;
        }
    }

    public static void setWeakHashMapEntryValue(Object entry, Object value) {
        try {
            WEAK_ENTRY_VALUE.set(entry, value);
        } catch (Exception e) {
            EcaLogger.error("[HealthAnalyzerManager] Failed to set WeakHashMap entry value", e);
        }
    }

    public static Object getWeakHashMapEntryNext(Object entry) {
        try {
            return WEAK_ENTRY_NEXT.get(entry);
        } catch (Exception e) {
            return null;
        }
    }
}
