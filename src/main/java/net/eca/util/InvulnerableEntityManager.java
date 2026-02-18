package net.eca.util;

import net.eca.util.reflect.VarHandleUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

//无敌实体管理器（用于测试和验证）
public class InvulnerableEntityManager {

    //存储所有无敌实体的 UUID
    private static final Set<UUID> INVULNERABLE_ENTITIES = ConcurrentHashMap.newKeySet();

    //添加无敌实体记录
    public static void addInvulnerable(Entity entity) {
        if (entity == null) return;
        INVULNERABLE_ENTITIES.add(entity.getUUID());
        EcaLogger.info("[InvulnerableEntityManager] Added invulnerable entity: {} (UUID: {})",
            entity.getName().getString(), entity.getUUID());
    }

    //移除无敌实体记录
    public static void removeInvulnerable(Entity entity) {
        if (entity == null) return;
        boolean removed = INVULNERABLE_ENTITIES.remove(entity.getUUID());
        if (removed) {
            EcaLogger.info("[InvulnerableEntityManager] Removed invulnerable entity: {} (UUID: {})",
                entity.getName().getString(), entity.getUUID());
        }
    }

    //检查是否是无敌实体（通过UUID）
    public static boolean isInvulnerable(UUID uuid) {
        return INVULNERABLE_ENTITIES.contains(uuid);
    }

    //获取所有无敌实体的 UUID
    public static Set<UUID> getAllInvulnerableUUIDs() {
        return Set.copyOf(INVULNERABLE_ENTITIES);
    }

    //获取无敌实体数量
    public static int getInvulnerableCount() {
        return INVULNERABLE_ENTITIES.size();
    }

    //清空所有记录
    public static void clearAll() {
        INVULNERABLE_ENTITIES.clear();
        EcaLogger.info("[InvulnerableEntityManager] Cleared all invulnerable entity records");
    }

    //检查无敌实体在各个底层容器中的存在情况
    public static Map<String, Boolean> checkEntityInContainers(ServerLevel level, UUID entityUUID) {
        Map<String, Boolean> result = new ConcurrentHashMap<>();

        Entity entity = level.getEntity(entityUUID);
        result.put("Level.getEntity(uuid)", entity != null);

        if (entity == null) {
            return result;
        }

        int entityId = entity.getId();

        //检查 EntityLookup (byId + byUuid)
        try {
            Object entityManager = VarHandleUtil.VH_SERVER_LEVEL_ENTITY_MANAGER.get(level);
            Object visibleStorage = VarHandleUtil.VH_PERSISTENT_ENTITY_MANAGER_VISIBLE_STORAGE.get(entityManager);

            Map<UUID, Entity> byUuid = (Map<UUID, Entity>) VarHandleUtil.VH_ENTITY_LOOKUP_BY_UUID.get(visibleStorage);
            result.put("EntityLookup.byUuid", byUuid != null && byUuid.containsKey(entityUUID));

            Map<Integer, Entity> byId = (Map<Integer, Entity>) VarHandleUtil.VH_ENTITY_LOOKUP_BY_ID.get(visibleStorage);
            result.put("EntityLookup.byId", byId != null && byId.containsKey(entityId));
        } catch (Exception e) {
            result.put("EntityLookup", false);
        }

        //检查 EntityTickList
        try {
            Object entityTickList = VarHandleUtil.VH_SERVER_LEVEL_ENTITY_TICK_LIST.get(level);
            Map<Integer, Entity> active = (Map<Integer, Entity>) VarHandleUtil.VH_ENTITY_TICK_LIST_ACTIVE.get(entityTickList);
            Map<Integer, Entity> passive = (Map<Integer, Entity>) VarHandleUtil.VH_ENTITY_TICK_LIST_PASSIVE.get(entityTickList);

            boolean inActive = active != null && active.containsKey(entityId);
            boolean inPassive = passive != null && passive.containsKey(entityId);
            result.put("EntityTickList.active", inActive);
            result.put("EntityTickList.passive", inPassive);
            result.put("EntityTickList", inActive || inPassive);
        } catch (Exception e) {
            result.put("EntityTickList", false);
        }

        //检查 EntitySection (ClassInstanceMultiMap)
        try {
            Object entityManager = VarHandleUtil.VH_SERVER_LEVEL_ENTITY_MANAGER.get(level);
            Object sectionStorage = VarHandleUtil.VH_PERSISTENT_ENTITY_MANAGER_SECTION_STORAGE.get(entityManager);
            Object sections = VarHandleUtil.VH_ENTITY_SECTION_STORAGE_SECTIONS.get(sectionStorage);

            boolean foundInSection = false;
            if (sections instanceof Map) {
                for (Object section : ((Map<?, ?>) sections).values()) {
                    if (section == null) continue;
                    Object storage = VarHandleUtil.VH_ENTITY_SECTION_STORAGE.get(section);
                    if (storage == null) continue;

                    Map<Class<?>, ?> byClass = (Map<Class<?>, ?>) VarHandleUtil.VH_CLASS_INSTANCE_MULTI_MAP_BY_CLASS.get(storage);
                    if (byClass != null) {
                        for (Object list : byClass.values()) {
                            if (list instanceof Iterable) {
                                for (Object obj : (Iterable<?>) list) {
                                    if (obj == entity) {
                                        foundInSection = true;
                                        break;
                                    }
                                }
                            }
                            if (foundInSection) break;
                        }
                    }
                    if (foundInSection) break;
                }
            }
            result.put("EntitySection", foundInSection);
        } catch (Exception e) {
            result.put("EntitySection", false);
        }

        //检查 KnownUuids
        try {
            Object entityManager = VarHandleUtil.VH_SERVER_LEVEL_ENTITY_MANAGER.get(level);
            Set<UUID> knownUuids = (Set<UUID>) VarHandleUtil.VH_PERSISTENT_ENTITY_MANAGER_KNOWN_UUIDS.get(entityManager);
            result.put("KnownUuids", knownUuids != null && knownUuids.contains(entityUUID));
        } catch (Exception e) {
            result.put("KnownUuids", false);
        }

        return result;
    }
}
