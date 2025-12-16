package net.eca.util.reflect;

import java.util.HashMap;
import java.util.Map;

// 混淆映射表 - 存储字段和方法的混淆名对应关系
/**
 * Obfuscation mapping registry for Minecraft field and method names.
 * This class only stores mappings, no reflection operations.
 */
public final class ObfuscationMapping {

    public static final String MINECRAFT_VERSION = "1.20.1";
    public static final String FORGE_VERSION = "47.2.0";

    private static final String CURRENT_VERSION = MINECRAFT_VERSION + "-" + FORGE_VERSION;

    // 字段映射表: 版本 -> 字段标识 -> 混淆名
    private static final Map<String, Map<String, String>> FIELD_MAPPINGS = new HashMap<>();

    // 方法映射表: 版本 -> 方法标识 -> 混淆名
    private static final Map<String, Map<String, String>> METHOD_MAPPINGS = new HashMap<>();

    static {
        initFieldMappings();
        initMethodMappings();
    }

    // 初始化字段映射
    private static void initFieldMappings() {
        Map<String, String> fields = new HashMap<>();

        // Entity
        fields.put("Entity.entityData", "f_19804_");
        fields.put("Entity.position", "f_19825_");
        fields.put("Entity.xOld", "f_19790_");
        fields.put("Entity.yOld", "f_19791_");
        fields.put("Entity.zOld", "f_19792_");
        fields.put("Entity.bb", "f_19828_");

        // LivingEntity
        fields.put("LivingEntity.hurtTime", "f_20915_");
        fields.put("LivingEntity.deathTime", "f_20919_");
        fields.put("LivingEntity.dead", "f_20890_");

        // SynchedEntityData
        fields.put("SynchedEntityData.itemsById", "f_135345_");
        fields.put("SynchedEntityData.isDirty", "f_135348_");

        // SynchedEntityData.DataItem
        fields.put("DataItem.value", "f_135391_");
        fields.put("DataItem.dirty", "f_135392_");

        // ServerLevel
        fields.put("ServerLevel.players", "f_8546_");
        fields.put("ServerLevel.chunkSource", "f_8547_");
        fields.put("ServerLevel.entityTickList", "f_143243_");
        fields.put("ServerLevel.entityManager", "f_143244_");
        fields.put("ServerLevel.navigatingMobs", "f_143246_");

        // EntityTickList
        fields.put("EntityTickList.active", "f_156903_");
        fields.put("EntityTickList.passive", "f_156904_");
        fields.put("EntityTickList.iterated", "f_156905_");

        // ServerChunkCache
        fields.put("ServerChunkCache.chunkMap", "f_8325_");

        // ChunkMap
        fields.put("ChunkMap.entityMap", "f_140150_");

        // PersistentEntitySectionManager
        fields.put("PersistentEntitySectionManager.visibleEntityStorage", "f_157494_");
        fields.put("PersistentEntitySectionManager.knownUuids", "f_157491_");
        fields.put("PersistentEntitySectionManager.sectionStorage", "f_157495_");

        // EntityLookup
        fields.put("EntityLookup.byUuid", "f_156808_");
        fields.put("EntityLookup.byId", "f_156807_");

        // EntitySectionStorage
        fields.put("EntitySectionStorage.sections", "f_156852_");

        // EntitySection
        fields.put("EntitySection.storage", "f_156827_");

        // ClassInstanceMultiMap
        fields.put("ClassInstanceMultiMap.byClass", "f_13527_");

        // ClientLevel
        fields.put("ClientLevel.tickingEntities", "f_171630_");
        fields.put("ClientLevel.entityStorage", "f_171631_");
        fields.put("ClientLevel.players", "f_104566_");

        // TransientEntitySectionManager
        fields.put("TransientEntitySectionManager.entityStorage", "f_157637_");
        fields.put("TransientEntitySectionManager.sectionStorage", "f_157638_");

        FIELD_MAPPINGS.put(CURRENT_VERSION, fields);
    }

    // 初始化方法映射
    private static void initMethodMappings() {
        Map<String, String> methods = new HashMap<>();

        // LivingEntity
        methods.put("LivingEntity.dropAllDeathLoot", "m_6668_");
        methods.put("LivingEntity.getRecordMaxHp", "m_21233_");
        methods.put("LivingEntity.actuallyHurt", "m_6475_");

        // Entity
        methods.put("Entity.setRemoved", "m_142467_");

        METHOD_MAPPINGS.put(CURRENT_VERSION, methods);
    }

    // 获取字段的混淆名
    /**
     * Get the obfuscated field name for the given key.
     * @param fieldKey the field mapping key like "Entity.entityData"
     * @return the obfuscated field name, or null if not found
     */
    public static String getFieldMapping(String fieldKey) {
        Map<String, String> mappings = FIELD_MAPPINGS.get(CURRENT_VERSION);
        return mappings != null ? mappings.get(fieldKey) : null;
    }

    // 获取方法的混淆名
    /**
     * Get the obfuscated method name for the given key.
     * @param methodKey the method mapping key like "LivingEntity.actuallyHurt"
     * @return the obfuscated method name, or null if not found
     */
    public static String getMethodMapping(String methodKey) {
        Map<String, String> mappings = METHOD_MAPPINGS.get(CURRENT_VERSION);
        return mappings != null ? mappings.get(methodKey) : null;
    }

    // 检查字段映射是否存在
    /**
     * Check if a field mapping exists for the given key.
     * @param fieldKey the field mapping key
     * @return true if mapping exists
     */
    public static boolean hasFieldMapping(String fieldKey) {
        return getFieldMapping(fieldKey) != null;
    }

    // 检查方法映射是否存在
    /**
     * Check if a method mapping exists for the given key.
     * @param methodKey the method mapping key
     * @return true if mapping exists
     */
    public static boolean hasMethodMapping(String methodKey) {
        return getMethodMapping(methodKey) != null;
    }

    // 获取当前版本标识
    /**
     * Get the current Minecraft-Forge version string.
     * @return version string like "1.20.1-47.2.0"
     */
    public static String getCurrentVersion() {
        return CURRENT_VERSION;
    }

    // 注册自定义字段映射
    /**
     * Register a custom field mapping for the current version.
     * @param fieldKey the field mapping key
     * @param obfuscatedName the obfuscated field name
     */
    public static void registerFieldMapping(String fieldKey, String obfuscatedName) {
        FIELD_MAPPINGS.computeIfAbsent(CURRENT_VERSION, k -> new HashMap<>())
                .put(fieldKey, obfuscatedName);
    }

    // 注册自定义方法映射
    /**
     * Register a custom method mapping for the current version.
     * @param methodKey the method mapping key
     * @param obfuscatedName the obfuscated method name
     */
    public static void registerMethodMapping(String methodKey, String obfuscatedName) {
        METHOD_MAPPINGS.computeIfAbsent(CURRENT_VERSION, k -> new HashMap<>())
                .put(methodKey, obfuscatedName);
    }

    private ObfuscationMapping() {}
}
