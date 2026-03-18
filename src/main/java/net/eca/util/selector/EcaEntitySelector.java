package net.eca.util.selector;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.eca.api.EcaAPI;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

public final class EcaEntitySelector {

    private static final SimpleCommandExceptionType INVALID_SELECTOR =
        new SimpleCommandExceptionType(Component.literal("Invalid ECA selector"));

    private static final SimpleCommandExceptionType UNSUPPORTED_SELECTOR =
        new SimpleCommandExceptionType(Component.literal("Only @eca_e/@eca_p/@eca_a/@eca_r/@eca_s are supported"));

    public enum SelectorMode {
        ALL_ENTITIES,
        NEAREST_PLAYER,
        ALL_PLAYERS,
        RANDOM_PLAYER,
        SELF
    }

    private EcaEntitySelector() {
    }

    public static List<Entity> select(CommandSourceStack source, String selector) throws CommandSyntaxException {
        if (source == null || selector == null || selector.isEmpty()) {
            throw INVALID_SELECTOR.create();
        }

        String trimmed = selector.trim();
        SelectorMode mode = parseSelectorMode(trimmed);
        return select(source, trimmed, mode);
    }

    public static List<Entity> select(CommandSourceStack source, String selector, SelectorMode mode) throws CommandSyntaxException {
        if (source == null || selector == null || selector.isEmpty() || mode == null) {
            throw INVALID_SELECTOR.create();
        }

        String trimmed = selector.trim();

        String optionsPart = null;
        int left = trimmed.indexOf('[');
        int right = trimmed.lastIndexOf(']');
        if (left >= 0 || right >= 0) {
            if (left < 0 || right <= left) {
                throw INVALID_SELECTOR.create();
            }
            optionsPart = trimmed.substring(left + 1, right).trim();
        }

        Map<String, String> options = parseOptions(optionsPart);

        Level level = source.getLevel();
        Vec3 origin = source.getPosition();
        double x = parseDouble(options, "x", origin.x);
        double y = parseDouble(options, "y", origin.y);
        double z = parseDouble(options, "z", origin.z);
        Vec3 center = new Vec3(x, y, z);

        AABB area = null;
        if (options.containsKey("dx") || options.containsKey("dy") || options.containsKey("dz")) {
            double dx = parseDouble(options, "dx", 0.0D);
            double dy = parseDouble(options, "dy", 0.0D);
            double dz = parseDouble(options, "dz", 0.0D);
            area = createAabbFromDelta(center, dx, dy, dz);
        }

        Range distance = parseRange(options.get("distance"));
        int defaultLimit = switch (mode) {
            case NEAREST_PLAYER, RANDOM_PLAYER, SELF -> 1;
            case ALL_ENTITIES, ALL_PLAYERS -> Integer.MAX_VALUE;
        };
        String defaultSort = switch (mode) {
            case NEAREST_PLAYER, SELF -> "nearest";
            case RANDOM_PLAYER -> "random";
            case ALL_ENTITIES, ALL_PLAYERS -> "arbitrary";
        };
        int limit = parseInt(options, "limit", defaultLimit);
        String sort = options.getOrDefault("sort", defaultSort);

        Predicate<Entity> predicate = entity -> !entity.isRemoved();
        if (mode == SelectorMode.NEAREST_PLAYER || mode == SelectorMode.ALL_PLAYERS || mode == SelectorMode.RANDOM_PLAYER) {
            predicate = predicate.and(Player.class::isInstance);
        }

        String type = options.get("type");
        if (type != null && !type.isEmpty()) {
            boolean invert = type.startsWith("!");
            String typeValue = invert ? type.substring(1) : type;
            ResourceLocation rl = ResourceLocation.tryParse(typeValue);
            if (rl == null) {
                throw INVALID_SELECTOR.create();
            }
            EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(rl);
            Predicate<Entity> typePredicate = entity -> entity.getType() == entityType;
            predicate = predicate.and(invert ? typePredicate.negate() : typePredicate);
        }

        String name = options.get("name");
        if (name != null && !name.isEmpty()) {
            boolean invert = name.startsWith("!");
            String nameValue = invert ? name.substring(1) : name;
            Predicate<Entity> namePredicate = entity -> nameValue.equals(entity.getName().getString());
            predicate = predicate.and(invert ? namePredicate.negate() : namePredicate);
        }

        List<Entity> result;
        if (mode == SelectorMode.SELF) {
            result = new ArrayList<>();
            Entity self = source.getEntity();
            if (self != null && (!self.isRemoved() || EcaAPI.isInvulnerable(self))) {
                result.add(self);
            }
        } else {
            result = area == null
                ? new ArrayList<>(getEntities(level, predicate))
                : new ArrayList<>(getEntities(level, area, predicate));
        }

        Predicate<Entity> finalPredicate = predicate;
        result.removeIf(entity -> !finalPredicate.test(entity));

        if (!distance.isUnbounded()) {
            double minSq = distance.min * distance.min;
            double maxSq = distance.max * distance.max;
            result.removeIf(entity -> {
                double d = entity.position().distanceToSqr(center);
                return d < minSq || d > maxSq;
            });
        }

        sortEntities(result, sort, center);

        if (limit < result.size()) {
            return new ArrayList<>(result.subList(0, Math.max(limit, 0)));
        }
        return result;
    }

    private static SelectorMode parseSelectorMode(String selector) throws CommandSyntaxException {
        if (selector.startsWith("@eca_e")) {
            return SelectorMode.ALL_ENTITIES;
        }
        if (selector.startsWith("@eca_p")) {
            return SelectorMode.NEAREST_PLAYER;
        }
        if (selector.startsWith("@eca_a")) {
            return SelectorMode.ALL_PLAYERS;
        }
        if (selector.startsWith("@eca_r")) {
            return SelectorMode.RANDOM_PLAYER;
        }
        if (selector.startsWith("@eca_s")) {
            return SelectorMode.SELF;
        }
        throw UNSUPPORTED_SELECTOR.create();
    }

    private static Map<String, String> parseOptions(String optionsPart) throws CommandSyntaxException {
        Map<String, String> options = new HashMap<>();
        if (optionsPart == null || optionsPart.isEmpty()) {
            return options;
        }

        String[] pairs = optionsPart.split(",");
        for (String pair : pairs) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq == trimmed.length() - 1) {
                throw INVALID_SELECTOR.create();
            }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            options.put(key, value);
        }
        return options;
    }

    private static double parseDouble(Map<String, String> options, String key, double defaultValue) throws CommandSyntaxException {
        String value = options.get(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw INVALID_SELECTOR.create();
        }
    }

    private static int parseInt(Map<String, String> options, String key, int defaultValue) throws CommandSyntaxException {
        String value = options.get(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw INVALID_SELECTOR.create();
        }
    }

    private static Range parseRange(String value) throws CommandSyntaxException {
        if (value == null || value.isEmpty()) {
            return Range.unbounded();
        }

        try {
            if (!value.contains("..")) {
                double exact = Double.parseDouble(value);
                return new Range(exact, exact);
            }

            String[] parts = value.split("\\.\\.", -1);
            if (parts.length != 2) {
                throw INVALID_SELECTOR.create();
            }

            double min = parts[0].isEmpty() ? 0.0D : Double.parseDouble(parts[0]);
            double max = parts[1].isEmpty() ? Double.MAX_VALUE : Double.parseDouble(parts[1]);
            if (max < min) {
                throw INVALID_SELECTOR.create();
            }
            return new Range(min, max);
        } catch (NumberFormatException e) {
            throw INVALID_SELECTOR.create();
        }
    }

    private static AABB createAabbFromDelta(Vec3 origin, double dx, double dy, double dz) {
        double x2 = origin.x + dx;
        double y2 = origin.y + dy;
        double z2 = origin.z + dz;
        return new AABB(
            Math.min(origin.x, x2),
            Math.min(origin.y, y2),
            Math.min(origin.z, z2),
            Math.max(origin.x, x2) + 1.0D,
            Math.max(origin.y, y2) + 1.0D,
            Math.max(origin.z, z2) + 1.0D
        );
    }

    private static void sortEntities(List<Entity> entities, String sort, Vec3 center) throws CommandSyntaxException {
        switch (sort) {
            case "nearest" -> entities.sort(Comparator.comparingDouble(entity -> entity.position().distanceToSqr(center)));
            case "furthest" -> entities.sort((a, b) -> Double.compare(b.position().distanceToSqr(center), a.position().distanceToSqr(center)));
            case "random" -> Collections.shuffle(entities, new Random());
            case "arbitrary" -> {
            }
            default -> throw INVALID_SELECTOR.create();
        }
    }

    private record Range(double min, double max) {
        static Range unbounded() {
            return new Range(0.0D, Double.MAX_VALUE);
        }

        boolean isUnbounded() {
            return min == 0.0D && max == Double.MAX_VALUE;
        }
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
