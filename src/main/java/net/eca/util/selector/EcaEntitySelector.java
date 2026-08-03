package net.eca.util.selector;

import net.eca.client.ClientEntityUtil;
import net.eca.coremod.EcaContainers;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.world.phys.Vec3;

public final class EcaEntitySelector {

    public enum SelectorMode {
        ALL_ENTITIES,
        NEAREST_PLAYER,
        ALL_PLAYERS,
        RANDOM_PLAYER,
        SELF
    }

    private EcaEntitySelector() {
    }

    public static Entity getEntity(Level level, int entityId) {
        if (level == null) {
            return null;
        }

        if (level instanceof ServerLevel serverLevel) {
            Entity entity = EcaContainers.rawGet(serverLevel.entityManager.visibleEntityStorage.byId, entityId);
            if (entity != null) {
                return entity;
            }
            entity = findEntityInServerSectionsById(serverLevel, entityId);
            if (entity != null) {
                return entity;
            }
            entity = EcaContainers.rawGet(serverLevel.entityTickList.active, entityId);
            if (entity != null) {
                return entity;
            }
            entity = findEntityInLoadingInboxById(serverLevel, entityId);
            if (entity != null) {
                return entity;
            }
            ChunkMap.TrackedEntity tracked = EcaContainers.rawGet(
                    serverLevel.chunkSource.chunkMap.entityMap, entityId);
            if (tracked != null && tracked.entity != null) {
                return tracked.entity;
            }
            Entity part = EcaContainers.rawGet(serverLevel.dragonParts, entityId);
            if (part != null) {
                return part;
            }
            return findEntityInServerCollectionsById(serverLevel, entityId);
        }

        if (level.isClientSide()) {
            return ClientEntityUtil.getEntityById(level, entityId);
        }

        return null;
    }

    public static Entity getEntity(Level level, UUID uuid) {
        if (level == null || uuid == null) {
            return null;
        }

        if (level instanceof ServerLevel serverLevel) {
            Entity entity = EcaContainers.rawGet(serverLevel.entityManager.visibleEntityStorage.byUuid, uuid);
            if (entity != null) {
                return entity;
            }
            entity = findEntityInServerSectionsByUuid(serverLevel, uuid);
            if (entity != null) {
                return entity;
            }
            entity = findEntityInTickListByUuid(serverLevel, uuid);
            if (entity != null) {
                return entity;
            }
            entity = findEntityInLoadingInboxByUuid(serverLevel, uuid);
            if (entity != null) {
                return entity;
            }
            entity = findTrackedEntityByUuid(serverLevel, uuid);
            if (entity != null) {
                return entity;
            }
            return findEntityInServerCollectionsByUuid(serverLevel, uuid);
        }

        if (level.isClientSide()) {
            return ClientEntityUtil.getEntityByUuid(level, uuid);
        }

        return null;
    }

    public static Entity getEntity(MinecraftServer server, int entityId) {
        if (server == null) {
            return null;
        }

        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = getEntity(level, entityId);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    public static Entity getEntity(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) {
            return null;
        }

        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = getEntity(level, uuid);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    public static <T extends Entity> T getEntity(Level level, int entityId, Class<T> entityClass) {
        Entity entity = getEntity(level, entityId);
        return entityClass != null && entityClass.isInstance(entity) ? entityClass.cast(entity) : null;
    }

    public static <T extends Entity> T getEntity(Level level, UUID uuid, Class<T> entityClass) {
        Entity entity = getEntity(level, uuid);
        return entityClass != null && entityClass.isInstance(entity) ? entityClass.cast(entity) : null;
    }

    public static List<Entity> getEntities(Level level) {
        return getEntities(level, entity -> true);
    }

    public static List<Entity> getEntities(Level level, AABB area) {
        if (area == null) {
            return Collections.emptyList();
        }
        return getEntities(level, entity -> entity.getBoundingBox().intersects(area));
    }

    public static List<Entity> getEntities(Level level, Predicate<Entity> filter) {
        if (level == null || filter == null) {
            return Collections.emptyList();
        }

        if (level instanceof ServerLevel serverLevel) {
            return collectServerEntities(serverLevel, filter);
        }

        if (level.isClientSide()) {
            return ClientEntityUtil.getEntities(level, filter);
        }

        return Collections.emptyList();
    }

    public static List<Entity> getEntities(Level level, AABB area, Predicate<Entity> filter) {
        if (area == null || filter == null) {
            return Collections.emptyList();
        }
        return getEntities(level, entity -> entity.getBoundingBox().intersects(area) && filter.test(entity));
    }

    public static <T extends Entity> List<T> getEntities(Level level, Class<T> entityClass) {
        if (entityClass == null) {
            return Collections.emptyList();
        }
        List<T> result = new ArrayList<>();
        for (Entity entity : getEntities(level, entityClass::isInstance)) {
            result.add(entityClass.cast(entity));
        }
        return result;
    }

    public static <T extends Entity> List<T> getEntities(Level level, AABB area, Class<T> entityClass) {
        if (entityClass == null) {
            return Collections.emptyList();
        }
        List<T> result = new ArrayList<>();
        for (Entity entity : getEntities(level, area, entityClass::isInstance)) {
            result.add(entityClass.cast(entity));
        }
        return result;
    }

    public static List<Entity> getEntities(MinecraftServer server) {
        return getEntities(server, entity -> true);
    }

    public static List<Entity> getEntities(MinecraftServer server, Predicate<Entity> filter) {
        if (server == null || filter == null) {
            return Collections.emptyList();
        }

        List<Entity> result = new ArrayList<>();
        Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : getEntities(level, filter)) {
                if (seen.add(entity)) {
                    result.add(entity);
                }
            }
        }
        return result;
    }

    // 使用 raw 容器按实例身份判断，避免逻辑 getter 的过滤造成删除成功假象。
    public static boolean containsPhysicalInstance(ServerLevel level, Entity target) {
        if (level == null || target == null) return false;
        return !collectServerEntities(level, entity -> entity == target).isEmpty();
    }

    // ==================== 最近实体查询 ====================

    public static Entity getNearestEntity(Level level, Vec3 pos, Predicate<Entity> filter) {
        if (level == null || pos == null || filter == null) {
            return null;
        }
        return getEntities(level, filter).stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(pos)))
                .orElse(null);
    }

    public static Entity getNearestEntity(Level level, Vec3 pos, AABB area, Predicate<Entity> filter) {
        if (level == null || pos == null || area == null || filter == null) {
            return null;
        }
        return getEntities(level, area, filter).stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(pos)))
                .orElse(null);
    }

    public static <T extends Entity> T getNearestEntity(Level level, Vec3 pos, Class<T> entityClass) {
        if (level == null || pos == null || entityClass == null) {
            return null;
        }
        return getEntities(level, entityClass).stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(pos)))
                .orElse(null);
    }

    public static <T extends Entity> T getNearestEntity(Level level, Vec3 pos, AABB area, Class<T> entityClass) {
        if (level == null || pos == null || area == null || entityClass == null) {
            return null;
        }
        return getEntities(level, area, entityClass).stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(pos)))
                .orElse(null);
    }

    private static Entity findEntityInServerSectionsById(ServerLevel level, int entityId) {
        for (EntitySection<Entity> section : EcaContainers.rawValues(level.entityManager.sectionStorage.sections)) {
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

    private static Entity findEntityInServerSectionsByUuid(ServerLevel level, UUID uuid) {
        for (EntitySection<Entity> section : EcaContainers.rawValues(level.entityManager.sectionStorage.sections)) {
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

    private static List<Entity> collectServerEntities(ServerLevel level, Predicate<Entity> filter) {
        List<Entity> result = new ArrayList<>();
        Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        addAll(result, seen, EcaContainers.rawValues(level.entityManager.visibleEntityStorage.byId), filter);
        for (EntitySection<Entity> section : EcaContainers.rawValues(level.entityManager.sectionStorage.sections)) {
            if (section != null) {
                addAll(result, seen, EcaContainers.rawValues(section.storage.allInstances), filter);
            }
        }
        addAll(result, seen, EcaContainers.rawValues(level.entityTickList.active), filter);
        for (ChunkEntities<Entity> chunkEntities : level.entityManager.loadingInbox) {
            if (chunkEntities != null) {
                addAll(result, seen, EcaContainers.rawValues(chunkEntities.entities), filter);
            }
        }
        for (ChunkMap.TrackedEntity tracked : EcaContainers.rawValues(level.chunkSource.chunkMap.entityMap)) {
            if (tracked != null) {
                add(result, seen, tracked.entity, filter);
            }
        }
        addAll(result, seen, EcaContainers.rawValues(level.players), filter);
        for (Mob mob : level.navigatingMobs) {
            add(result, seen, mob, filter);
        }
        addAll(result, seen, EcaContainers.rawValues(level.dragonParts), filter);
        return result;
    }

    private static Entity findEntityInTickListByUuid(ServerLevel level, UUID uuid) {
        return findByUuid(EcaContainers.rawValues(level.entityTickList.active), uuid);
    }

    private static Entity findEntityInLoadingInboxById(ServerLevel level, int entityId) {
        for (ChunkEntities<Entity> chunkEntities : level.entityManager.loadingInbox) {
            if (chunkEntities == null) continue;
            for (Entity entity : EcaContainers.rawValues(chunkEntities.entities)) {
                if (entity != null && entity.getId() == entityId) return entity;
            }
        }
        return null;
    }

    private static Entity findEntityInLoadingInboxByUuid(ServerLevel level, UUID uuid) {
        for (ChunkEntities<Entity> chunkEntities : level.entityManager.loadingInbox) {
            if (chunkEntities == null) continue;
            Entity entity = findByUuid(EcaContainers.rawValues(chunkEntities.entities), uuid);
            if (entity != null) return entity;
        }
        return null;
    }

    private static Entity findTrackedEntityByUuid(ServerLevel level, UUID uuid) {
        for (ChunkMap.TrackedEntity tracked : EcaContainers.rawValues(level.chunkSource.chunkMap.entityMap)) {
            if (tracked != null && tracked.entity != null && uuid.equals(tracked.entity.getUUID())) {
                return tracked.entity;
            }
        }
        return null;
    }

    private static Entity findEntityInServerCollectionsById(ServerLevel level, int entityId) {
        for (Entity entity : EcaContainers.rawValues(level.players)) {
            if (entity != null && entity.getId() == entityId) return entity;
        }
        for (Mob mob : level.navigatingMobs) {
            if (mob != null && mob.getId() == entityId) return mob;
        }
        return EcaContainers.rawGet(level.dragonParts, entityId);
    }

    private static Entity findEntityInServerCollectionsByUuid(ServerLevel level, UUID uuid) {
        Entity entity = findByUuid(EcaContainers.rawValues(level.players), uuid);
        if (entity != null) return entity;
        entity = findByUuid(level.navigatingMobs, uuid);
        if (entity != null) return entity;
        return findByUuid(EcaContainers.rawValues(level.dragonParts), uuid);
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
            add(result, seen, entity, filter);
        }
    }

    private static void add(List<Entity> result, Set<Entity> seen, Entity entity, Predicate<Entity> filter) {
        if (entity != null && seen.add(entity) && filter.test(entity)) {
            result.add(entity);
        }
    }
}
