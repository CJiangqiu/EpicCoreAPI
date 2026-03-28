package net.eca.util.selector;

import net.eca.api.EcaAPI;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

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
            Entity entity = serverLevel.entityManager.visibleEntityStorage.getEntity(entityId);
            if (entity != null) {
                return entity;
            }
            return findEntityInServerSectionsById(serverLevel, entityId);
        }

        if (level instanceof ClientLevel clientLevel) {
            Entity entity = clientLevel.entityStorage.entityStorage.getEntity(entityId);
            if (entity != null) {
                return entity;
            }
            return findEntityInClientSectionsById(clientLevel, entityId);
        }

        return null;
    }

    public static Entity getEntity(Level level, UUID uuid) {
        if (level == null || uuid == null) {
            return null;
        }

        if (level instanceof ServerLevel serverLevel) {
            Entity entity = serverLevel.entityManager.visibleEntityStorage.getEntity(uuid);
            if (entity != null) {
                return entity;
            }
            return findEntityInServerSectionsByUuid(serverLevel, uuid);
        }

        if (level instanceof ClientLevel clientLevel) {
            Entity entity = clientLevel.entityStorage.entityStorage.getEntity(uuid);
            if (entity != null) {
                return entity;
            }
            return findEntityInClientSectionsByUuid(clientLevel, uuid);
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

        Map<UUID, Entity> unique = new LinkedHashMap<>();

        if (level instanceof ServerLevel serverLevel) {
            for (Entity entity : serverLevel.entityManager.visibleEntityStorage.getAllEntities()) {
                if (entity != null && (!entity.isRemoved() || EcaAPI.isInvulnerable(entity)) && filter.test(entity)) {
                    unique.put(entity.getUUID(), entity);
                }
            }

            for (EntitySection<Entity> section : serverLevel.entityManager.sectionStorage.sections.values()) {
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

        if (level instanceof ClientLevel clientLevel) {
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

        Map<UUID, Entity> unique = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : getEntities(level, filter)) {
                unique.putIfAbsent(entity.getUUID(), entity);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static Entity findEntityInServerSectionsById(ServerLevel level, int entityId) {
        for (EntitySection<Entity> section : level.entityManager.sectionStorage.sections.values()) {
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

    private static Entity findEntityInServerSectionsByUuid(ServerLevel level, UUID uuid) {
        for (EntitySection<Entity> section : level.entityManager.sectionStorage.sections.values()) {
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
}
