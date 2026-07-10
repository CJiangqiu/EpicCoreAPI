package net.eca.client;

import net.eca.api.EcaAPI;
import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.eca.util.selector.EcaEntitySelector;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.entity.PartEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

// 客户端专属实体工具：所有触碰 ClientLevel 的逻辑集中于此，使公共类不会在专用服务端触发 ClientLevel 类加载
@OnlyIn(Dist.CLIENT)
public final class ClientEntityUtil {

    private ClientEntityUtil() {
    }

    // 客户端按 ID 查找实体
    public static Entity getEntityById(Level level, int entityId) {
        if (!(level instanceof ClientLevel clientLevel)) {
            return null;
        }
        Entity entity = clientLevel.entityStorage.entityStorage.getEntity(entityId);
        if (entity != null) {
            return entity;
        }
        entity = findEntityInClientSectionsById(clientLevel, entityId);
        if (entity != null) {
            return entity;
        }
        return clientLevel.tickingEntities.active.get(entityId);
    }

    // 客户端按 UUID 查找实体
    public static Entity getEntityByUuid(Level level, UUID uuid) {
        if (uuid == null || !(level instanceof ClientLevel clientLevel)) {
            return null;
        }
        Entity entity = clientLevel.entityStorage.entityStorage.getEntity(uuid);
        if (entity != null) {
            return entity;
        }
        return findEntityInClientSectionsByUuid(clientLevel, uuid);
    }

    // 客户端按条件收集实体
    public static List<Entity> getEntities(Level level, Predicate<Entity> filter) {
        if (filter == null || !(level instanceof ClientLevel clientLevel)) {
            return Collections.emptyList();
        }

        Map<UUID, Entity> unique = new LinkedHashMap<>();
        for (Entity entity : clientLevel.entityStorage.entityStorage.getAllEntities()) {
            if (entity != null && (!entity.isRemoved() || EcaAPI.isInvulnerable(entity)) && filter.test(entity)) {
                unique.put(entity.getUUID(), entity);
            }
        }

        for (EntitySection<Entity> section : clientLevel.entityStorage.sectionStorage.sections.values()) {
            if (section == null) {
                continue;
            }
            section.getEntities().forEach(entity -> {
                if (entity != null && (!entity.isRemoved() || EcaAPI.isInvulnerable(entity)) && filter.test(entity)) {
                    unique.putIfAbsent(entity.getUUID(), entity);
                }
            });
        }
        return new ArrayList<>(unique.values());
    }

    private static Entity findEntityInClientSectionsById(ClientLevel level, int entityId) {
        for (EntitySection<Entity> section : level.entityStorage.sectionStorage.sections.values()) {
            if (section == null) {
                continue;
            }
            Entity entity = section.getEntities().filter(e -> e.getId() == entityId).findFirst().orElse(null);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static Entity findEntityInClientSectionsByUuid(ClientLevel level, UUID uuid) {
        for (EntitySection<Entity> section : level.entityStorage.sectionStorage.sections.values()) {
            if (section == null) {
                continue;
            }
            Entity entity = section.getEntities().filter(e -> uuid.equals(e.getUUID())).findFirst().orElse(null);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    // 检查实体在客户端关键容器中的存在情况
    public static Map<String, Boolean> checkEntityInClientContainers(ClientLevel clientLevel, UUID entityUUID) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (clientLevel == null || entityUUID == null) {
            return result;
        }

        Entity entity = null;
        try {
            entity = EcaEntitySelector.getEntity(clientLevel, entityUUID);
        } catch (Exception ignored) {
        }
        result.put("ClientLevel.getEntity(uuid)", entity != null);

        try {
            result.put("ClientEntityStorage.entityLookup.byUuid", clientLevel.entityStorage.entityStorage.byUuid.containsKey(entityUUID));
        } catch (Exception e) {
            result.put("ClientEntityStorage.entityLookup.byUuid", false);
        }

        try {
            boolean byId = entity != null && clientLevel.entityStorage.entityStorage.byId.containsKey(entity.getId());
            result.put("ClientEntityStorage.entityLookup.byId", byId);
        } catch (Exception e) {
            result.put("ClientEntityStorage.entityLookup.byId", false);
        }

        try {
            result.put("ClientLevel.tickingEntities", entity != null && clientLevel.tickingEntities.contains(entity));
        } catch (Exception e) {
            result.put("ClientLevel.tickingEntities", false);
        }

        try {
            boolean inSection = false;
            if (entity != null) {
                Entity targetEntity = entity;
                long sectionKey = SectionPos.asLong(entity.blockPosition());
                EntitySection<Entity> section = clientLevel.entityStorage.sectionStorage.sections.get(sectionKey);
                inSection = section != null && section.getEntities().anyMatch(e -> e == targetEntity);
            }
            result.put("ClientEntityStorage.sectionStorage", inSection);
        } catch (Exception e) {
            result.put("ClientEntityStorage.sectionStorage", false);
        }

        try {
            result.put("ClientEntity.levelCallback", entity != null && entity.levelCallback != EntityInLevelCallback.NULL);
        } catch (Exception e) {
            result.put("ClientEntity.levelCallback", false);
        }

        try {
            boolean inClientPlayers = !(entity instanceof Player) || clientLevel.players.contains(entity);
            result.put("ClientLevel.players", inClientPlayers);
        } catch (Exception e) {
            result.put("ClientLevel.players", false);
        }

        return result;
    }

    // 客户端底层容器清除
    public static void removeFromClientContainers(ClientLevel clientLevel, Entity entity) {
        try {
            clientLevel.players.remove(entity);

            if (entity.isMultipartEntity()) {
                for (PartEntity<?> part : entity.getParts()) {
                    clientLevel.partEntities.remove(part.getId());
                }
            }

            TransientEntitySectionManager<Entity> entityStorage = clientLevel.entityStorage;
            EntityUtil.removeFromSectionStorage(entityStorage.sectionStorage, entity);  // ClassInstanceMultiMap 直接操作
            EntityUtil.removeSectionIfEmpty(entityStorage.sectionStorage, entity);
            EntityUtil.removeFromEntityTickList(clientLevel.tickingEntities, entity);   // EntityTickList.active 直接操作
            EntityUtil.removeFromEntityLookup(entityStorage.entityStorage, entity);     // EntityLookup byId/byUuid 直接操作

        } catch (Exception e) {
            EcaLogger.error("[ClientEntityUtil] Failed to remove from client containers, entityId={}, type={}, uuid={}",
                    entity.getId(), entity.getType(), entity.getUUID());
            EcaLogger.error("[ClientEntityUtil] Client container removal stacktrace", e);
        }
    }
}
