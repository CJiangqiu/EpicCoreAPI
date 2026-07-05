package net.eca.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InvulnerableEntityManager {

    private static final Map<String, Set<UUID>> INVULNERABLE_ENTITIES_BY_SAVE = new ConcurrentHashMap<>();

    public static void addInvulnerable(Entity entity) {
        String saveKey = getSaveKey(entity);
        if (saveKey == null) return;
        INVULNERABLE_ENTITIES_BY_SAVE
            .computeIfAbsent(saveKey, key -> ConcurrentHashMap.newKeySet())
            .add(entity.getUUID());
    }

    public static void removeInvulnerable(Entity entity) {
        String saveKey = getSaveKey(entity);
        if (saveKey == null) return;
        Set<UUID> uuids = INVULNERABLE_ENTITIES_BY_SAVE.get(saveKey);
        if (uuids == null) return;
        uuids.remove(entity.getUUID());
        if (uuids.isEmpty()) {
            INVULNERABLE_ENTITIES_BY_SAVE.remove(saveKey, uuids);
        }
    }

    public static boolean isInvulnerable(Entity entity) {
        String saveKey = getSaveKey(entity);
        if (saveKey == null) return false;
        Set<UUID> uuids = INVULNERABLE_ENTITIES_BY_SAVE.get(saveKey);
        return uuids != null && uuids.contains(entity.getUUID());
    }

    public static Set<UUID> getAllInvulnerableUUIDs(ServerLevel level) {
        String saveKey = getSaveKey(level);
        if (saveKey == null) return Set.of();
        Set<UUID> uuids = INVULNERABLE_ENTITIES_BY_SAVE.get(saveKey);
        return uuids == null ? Set.of() : Set.copyOf(uuids);
    }

    public static int getInvulnerableCount(ServerLevel level) {
        String saveKey = getSaveKey(level);
        if (saveKey == null) return 0;
        Set<UUID> uuids = INVULNERABLE_ENTITIES_BY_SAVE.get(saveKey);
        return uuids == null ? 0 : uuids.size();
    }

    public static void clearAll() {
        INVULNERABLE_ENTITIES_BY_SAVE.clear();
    }

    private static String getSaveKey(Entity entity) {
        if (entity == null || !(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        return getSaveKey(level);
    }

    private static String getSaveKey(ServerLevel level) {
        if (level == null || level.getServer() == null) {
            return null;
        }
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        return root.toAbsolutePath().normalize().toString();
    }
}
