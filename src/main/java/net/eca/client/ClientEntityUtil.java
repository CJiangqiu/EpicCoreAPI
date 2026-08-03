package net.eca.client;

import net.eca.coremod.EcaContainers;
import net.eca.network.EntityContainerCheckResponsePacket;
import net.eca.network.NetworkHandler;
import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.eca.util.selector.EcaEntitySelector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        Entity entity = EcaContainers.rawGet(clientLevel.entityStorage.entityStorage.byId, entityId);
        if (entity != null) {
            return entity;
        }
        entity = findEntityInClientSectionsById(clientLevel, entityId);
        if (entity != null) {
            return entity;
        }
        entity = EcaContainers.rawGet(clientLevel.tickingEntities.active, entityId);
        if (entity != null) {
            return entity;
        }
        entity = EcaContainers.rawGet(clientLevel.partEntities, entityId);
        if (entity != null) {
            return entity;
        }
        for (Entity player : EcaContainers.rawValues(clientLevel.players)) {
            if (player != null && player.getId() == entityId) return player;
        }
        return null;
    }

    // 客户端按 UUID 查找实体
    public static Entity getEntityByUuid(Level level, UUID uuid) {
        if (uuid == null || !(level instanceof ClientLevel clientLevel)) {
            return null;
        }
        Entity entity = EcaContainers.rawGet(clientLevel.entityStorage.entityStorage.byUuid, uuid);
        if (entity != null) {
            return entity;
        }
        entity = findEntityInClientSectionsByUuid(clientLevel, uuid);
        if (entity != null) {
            return entity;
        }
        entity = findByUuid(EcaContainers.rawValues(clientLevel.tickingEntities.active), uuid);
        if (entity != null) {
            return entity;
        }
        entity = findByUuid(EcaContainers.rawValues(clientLevel.players), uuid);
        if (entity != null) {
            return entity;
        }
        return findByUuid(EcaContainers.rawValues(clientLevel.partEntities), uuid);
    }

    // 客户端按条件收集实体
    public static List<Entity> getEntities(Level level, Predicate<Entity> filter) {
        if (filter == null || !(level instanceof ClientLevel clientLevel)) {
            return Collections.emptyList();
        }

        List<Entity> result = new ArrayList<>();
        Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        addAll(result, seen, EcaContainers.rawValues(clientLevel.entityStorage.entityStorage.byId), filter);
        for (EntitySection<Entity> section : EcaContainers.rawValues(clientLevel.entityStorage.sectionStorage.sections)) {
            if (section != null) {
                addAll(result, seen, EcaContainers.rawValues(section.storage.allInstances), filter);
            }
        }
        addAll(result, seen, EcaContainers.rawValues(clientLevel.tickingEntities.active), filter);
        addAll(result, seen, EcaContainers.rawValues(clientLevel.players), filter);
        addAll(result, seen, EcaContainers.rawValues(clientLevel.partEntities), filter);
        return result;
    }

    private static Entity findEntityInClientSectionsById(ClientLevel level, int entityId) {
        for (EntitySection<Entity> section : EcaContainers.rawValues(level.entityStorage.sectionStorage.sections)) {
            if (section == null) {
                continue;
            }
            for (Entity entity : EcaContainers.rawValues(section.storage.allInstances)) {
                if (entity != null && entity.getId() == entityId) {
                    return entity;
                }
            }
        }
        return null;
    }

    private static Entity findEntityInClientSectionsByUuid(ClientLevel level, UUID uuid) {
        for (EntitySection<Entity> section : EcaContainers.rawValues(level.entityStorage.sectionStorage.sections)) {
            if (section == null) {
                continue;
            }
            for (Entity entity : EcaContainers.rawValues(section.storage.allInstances)) {
                if (entity != null && uuid.equals(entity.getUUID())) {
                    return entity;
                }
            }
        }
        return null;
    }

    private static Entity findByUuid(Iterable<? extends Entity> entities, UUID uuid) {
        for (Entity entity : entities) {
            if (entity != null && uuid.equals(entity.getUUID())) return entity;
        }
        return null;
    }

    private static void addAll(List<Entity> result, Set<Entity> seen,
                               Iterable<? extends Entity> entities, Predicate<Entity> filter) {
        for (Entity entity : entities) {
            if (entity != null && seen.add(entity) && filter.test(entity)) {
                result.add(entity);
            }
        }
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

    // 处理 ClientRemovePacket 的客户端移除逻辑
    public static void handleClientRemove(int entityId, List<UUID> bossEventUUIDs) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel clientLevel = minecraft.level;
        if (clientLevel != null) {
            Entity entity = getEntityById(clientLevel, entityId);
            if (entity != null) {
                entity.onClientRemoval();
                entity.invalidateCaps();
                entity.setRemoved(Entity.RemovalReason.DISCARDED);
                entity.stopRiding();
                entity.onRemovedFromWorld();
                entity.levelCallback = EntityInLevelCallback.NULL;
                removeFromClientContainers(clientLevel, entity);
            } else {
                EcaLogger.debug("[ClientRemovePacket] Client entity removal: entity not found (ID: {})", entityId);
            }

            removeBossOverlayEntries(minecraft, bossEventUUIDs);
        }
    }

    private static void removeBossOverlayEntries(Minecraft minecraft, List<UUID> bossEventUUIDs) {
        if (bossEventUUIDs.isEmpty()) return;
        try {
            BossHealthOverlay bossOverlay = minecraft.gui.getBossOverlay();
            for (UUID uuid : bossEventUUIDs) {
                bossOverlay.events.remove(uuid);
            }
        } catch (Exception e) {
            EcaLogger.info("[ClientRemovePacket] Failed to remove boss overlay entries: {}", e.getMessage());
        }
    }

    // 处理 EntityContainerCheckRequestPacket 的客户端检查逻辑
    public static void handleContainerCheckRequest(UUID requestId, UUID entityUuid) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel clientLevel = minecraft.level;
        if (clientLevel != null) {
            result.putAll(checkEntityInClientContainers(clientLevel, entityUuid));
        } else {
            result.put("ClientLevel.getEntity(uuid)", false);
            result.put("ClientEntityStorage.entityLookup.byUuid", false);
            result.put("ClientEntityStorage.entityLookup.byId", false);
            result.put("ClientLevel.tickingEntities", false);
            result.put("ClientEntityStorage.sectionStorage", false);
            result.put("ClientEntity.levelCallback", false);
        }
        NetworkHandler.sendToServer(new EntityContainerCheckResponsePacket(requestId, entityUuid, result));
    }

    // 处理 SetHealthClientSyncPacket 的客户端血条同步
    public static void syncHealthFromServer(int entityId, float health) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Entity entity = level.getEntity(entityId);
        if (entity instanceof LivingEntity living) {
            EntityUtil.setHealthFromSync(living, health);
        }
    }

    // 打开 ShaderGenerator 编辑屏幕
    public static void openShaderGeneratorScreen() {
        net.eca.client.gui.ShaderGeneratorScreen.open();
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
