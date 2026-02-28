package net.eca.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

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
    }

    //移除无敌实体记录
    public static void removeInvulnerable(Entity entity) {
        if (entity == null) return;
        INVULNERABLE_ENTITIES.remove(entity.getUUID());
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
    }

    //检查无敌实体在各个底层容器中的存在情况（AT 直接访问）
    @SuppressWarnings("unchecked")
    public static Map<String, Boolean> checkEntityInContainers(ServerLevel level, UUID entityUUID) {
        Map<String, Boolean> result = new ConcurrentHashMap<>();

        Entity entity = level.getEntity(entityUUID);
        result.put("Level.getEntity(uuid)", entity != null);

        if (entity == null) {
            return result;
        }

        int entityId = entity.getId();
        PersistentEntitySectionManager<Entity> entityManager = level.entityManager;

        //检查 EntityLookup (byId + byUuid)
        try {
            result.put("EntityLookup.byUuid", entityManager.visibleEntityStorage.byUuid.containsKey(entityUUID));
            result.put("EntityLookup.byId", entityManager.visibleEntityStorage.byId.containsKey(entityId));
        } catch (Exception e) {
            result.put("EntityLookup", false);
        }

        //检查 EntityTickList
        try {
            result.put("EntityTickList", level.entityTickList.contains(entity));
        } catch (Exception e) {
            result.put("EntityTickList", false);
        }

        //检查 EntitySection (ClassInstanceMultiMap)
        try {
            boolean foundInSection = false;
            for (EntitySection<Entity> section : entityManager.sectionStorage.sections.values()) {
                if (section != null && section.getEntities().anyMatch(e -> e == entity)) {
                    foundInSection = true;
                    break;
                }
            }
            result.put("EntitySection", foundInSection);
        } catch (Exception e) {
            result.put("EntitySection", false);
        }

        //检查 KnownUuids
        try {
            result.put("KnownUuids", entityManager.knownUuids.contains(entityUUID));
        } catch (Exception e) {
            result.put("KnownUuids", false);
        }

        return result;
    }
}
