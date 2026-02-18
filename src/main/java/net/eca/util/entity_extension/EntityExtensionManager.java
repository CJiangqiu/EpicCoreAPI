package net.eca.util.entity_extension;

import net.eca.api.RegisterEntityExtension;
import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.network.EntityExtensionActiveTypePacket;
import net.eca.network.EntityExtensionBossEventTypePacket;
import net.eca.network.NetworkHandler;
import net.eca.util.EcaLogger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class EntityExtensionManager {

    private static final Map<EntityType<?>, EntityExtension> REGISTRY = new ConcurrentHashMap<>();

    private static final Map<ResourceKey<Level>, DimensionState> DIMENSION_STATES = new ConcurrentHashMap<>();
    private static final AtomicLong ORDER_COUNTER = new AtomicLong(0);

    public static void scanAndRegisterAll() {
        EcaLogger.info("Starting Entity Extension scan...");

        ModList.get().forEachModFile(modFile -> {
            for (IModInfo modInfo : modFile.getModInfos()) {
                String modId = modInfo.getModId();

                modFile.getScanResult().getAnnotations().forEach(annotationData -> {
                    if (RegisterEntityExtension.class.getName().equals(annotationData.annotationType().getClassName())) {
                        String className = annotationData.clazz().getClassName();
                        EcaLogger.info("Found @RegisterEntityExtension: {} (from mod: {})", className, modId);

                        try {
                            Class.forName(className, true, Thread.currentThread().getContextClassLoader());
                            EcaLogger.info("Triggered static initialization for: {}", className);
                        } catch (ClassNotFoundException e) {
                            EcaLogger.error("Failed to load extension class {}: {}", className, e.getMessage());
                        }
                    }
                });
            }
        });

        EcaLogger.info("Entity Extension scan completed. Registered {} extensions", REGISTRY.size());
    }

    public static boolean register(EntityExtension extension) {
        if (extension == null) {
            EcaLogger.error("Cannot register null extension");
            return false;
        }

        EntityType<?> type = extension.getEntityType();

        if (REGISTRY.containsKey(type)) {
            EntityExtension existing = REGISTRY.get(type);
            EcaLogger.error("EntityType {} already has an extension registered: {} (priority {}). Skipping new extension: {} (priority {})",
                type, existing.getClass().getName(), existing.getPriority(),
                extension.getClass().getName(), extension.getPriority());
            return false;
        }

        REGISTRY.put(type, extension);
        EcaLogger.info("Registered EntityExtension: {} -> {} (priority: {})",
            type, extension.getClass().getSimpleName(), extension.getPriority());
        return true;
    }

    public static EntityExtension getExtension(EntityType<?> type) {
        return REGISTRY.get(type);
    }

    public static Map<EntityType<?>, EntityExtension> getRegistryView() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    public static Map<EntityType<?>, Integer> getActiveTypeCounts(ServerLevel level) {
        if (level == null) {
            return Collections.emptyMap();
        }

        DimensionState state = DIMENSION_STATES.get(level.dimension());
        if (state == null || state.types.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<EntityType<?>, Integer> snapshot = new HashMap<>();
        for (Map.Entry<EntityType<?>, ActiveTypeState> entry : state.types.entrySet()) {
            ActiveTypeState active = entry.getValue();
            if (active != null && active.count > 0) {
                snapshot.put(entry.getKey(), active.count);
            }
        }

        return Collections.unmodifiableMap(snapshot);
    }

    public static EntityType<?> getActiveType(ServerLevel level) {
        if (level == null) {
            return null;
        }

        DimensionState state = DIMENSION_STATES.get(level.dimension());
        return state != null ? state.activeType : null;
    }

    public static void onEntityJoin(LivingEntity entity, ServerLevel level) {
        if (entity == null || level == null) {
            return;
        }

        EntityType<?> type = entity.getType();
        EntityExtension extension = REGISTRY.get(type);

        if (extension == null) {
            return;
        }

        // 清除原生 boss bar，由 ECA 扩展系统接管
        EntityUtil.cleanupBossBar(entity);

        ResourceKey<Level> dimension = level.dimension();
        DimensionState state = DIMENSION_STATES.computeIfAbsent(dimension, k -> new DimensionState());

        long order = ORDER_COUNTER.incrementAndGet();
        ActiveTypeState typeState = state.types.computeIfAbsent(type, k -> new ActiveTypeState());
        typeState.count++;
        typeState.priority = extension.getPriority();
        typeState.order = order;

        createCustomBossEventIfNeeded(entity, extension, state);
        syncBossEventTypeMappings(entity, extension, state);

        if (state.activeType == null) {
            state.activate(type, typeState);
            sendActiveTypeUpdate(level, type);
            EcaLogger.info("Activated initial type for dimension {}: {}", dimension.location(), type);
            return;
        }

        if (state.shouldReplace(typeState)) {
            EntityType<?> previous = state.activeType;
            state.activate(type, typeState);
            if (previous != type) {
                sendActiveTypeUpdate(level, type);
            }
            EcaLogger.info("Replaced active type for dimension {}: {}", dimension.location(), type);
        }
    }

    public static void onEntityLeave(LivingEntity entity, ServerLevel level) {
        if (entity == null || level == null) {
            return;
        }

        EntityType<?> type = entity.getType();
        if (!REGISTRY.containsKey(type)) {
            return;
        }

        DimensionState state = DIMENSION_STATES.get(level.dimension());
        if (state == null) {
            return;
        }

        EntityExtension extension = REGISTRY.get(type);
        if (extension != null) {
            removeBossEventTypeMappings(entity, state);
        }
        removeCustomBossEvent(entity.getUUID(), state);

        ActiveTypeState typeState = state.types.get(type);
        if (typeState == null) {
            return;
        }

        typeState.count = Math.max(0, typeState.count - 1);
        if (typeState.count == 0) {
            state.types.remove(type);
            if (type.equals(state.activeType)) {
                refreshActiveTable(level);
            }
        }
    }

    public static void cleanupBossBar(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        DimensionState state = DIMENSION_STATES.get(level.dimension());
        if (state == null) {
            return;
        }

        removeBossEventTypeMappings(entity, state);
        removeCustomBossEvent(entity.getUUID(), state);
    }

    public static void onStartTracking(ServerPlayer player, LivingEntity entity) {
        if (player == null || entity == null) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        DimensionState state = DIMENSION_STATES.get(level.dimension());
        if (state == null) {
            return;
        }

        // 先发送 UUID 映射，再添加玩家到 boss event（触发 boss bar 数据包）
        EntityExtension extension = REGISTRY.get(entity.getType());
        if (extension != null) {
            syncBossEventTypeMappingsToPlayer(player, entity, extension, state);
        }

        CustomBossEventState customBossEventState = state.customBossEvents.get(entity.getUUID());
        if (customBossEventState != null) {
            customBossEventState.bossEvent.addPlayer(player);
        }
    }

    public static void onStopTracking(ServerPlayer player, LivingEntity entity) {
        if (player == null || entity == null) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        DimensionState state = DIMENSION_STATES.get(level.dimension());
        if (state == null) {
            return;
        }

        CustomBossEventState customBossEventState = state.customBossEvents.get(entity.getUUID());
        if (customBossEventState != null) {
            customBossEventState.bossEvent.removePlayer(player);
        }

        EntityExtension extension = REGISTRY.get(entity.getType());
        if (extension != null) {
            removeBossEventTypeMappingsFromPlayer(player, entity, state);
        }
    }

    public static void tickDimension(ServerLevel level) {
        if (level == null) {
            return;
        }

        DimensionState state = DIMENSION_STATES.get(level.dimension());
        if (state == null) {
            return;
        }

        tickCustomBossEvents(level, state);
        validateActiveTypes(level, state);

        if (state.activeType == null) {
            refreshActiveTable(level);
            return;
        }

        ActiveTypeState typeState = state.types.get(state.activeType);
        if (typeState == null || typeState.count == 0) {
            refreshActiveTable(level);
        }
    }

    private static void validateActiveTypes(ServerLevel level, DimensionState state) {
        if (state.types.isEmpty()) {
            return;
        }

        state.types.entrySet().removeIf(entry -> {
            EntityType<?> type = entry.getKey();
            ActiveTypeState typeState = entry.getValue();
            if (typeState == null) {
                return true;
            }

            int actualCount = countAliveEntitiesOfType(level, type);
            if (actualCount == 0) {
                if (type.equals(state.activeType)) {
                    state.clearActive();
                    sendActiveTypeUpdate(level, null);
                    EcaLogger.info("Cleared active type {} - no alive entities", type);
                }
                return true;
            }

            typeState.count = actualCount;
            return false;
        });
    }

    private static int countAliveEntitiesOfType(ServerLevel level, EntityType<?> type) {
        int count = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity.getType() == type && entity instanceof LivingEntity living && living.isAlive()) {
                count++;
            }
        }
        return count;
    }

    public static EntityType<?> refreshActiveTable(ServerLevel level) {
        if (level == null) {
            return null;
        }

        DimensionState state = DIMENSION_STATES.get(level.dimension());
        if (state == null) {
            return null;
        }

        state.types.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().count <= 0);

        EntityType<?> selectedType = null;
        ActiveTypeState selectedState = null;

        for (Map.Entry<EntityType<?>, ActiveTypeState> entry : state.types.entrySet()) {
            ActiveTypeState candidate = entry.getValue();
            if (candidate == null || candidate.count <= 0) {
                continue;
            }

            if (selectedState == null || candidate.priority > selectedState.priority ||
                (candidate.priority == selectedState.priority && candidate.order > selectedState.order)) {
                selectedType = entry.getKey();
                selectedState = candidate;
            }
        }

        if (selectedType == null) {
            if (state.activeType != null) {
                state.clearActive();
                sendActiveTypeUpdate(level, null);
            }
            return null;
        }

        EntityType<?> previous = state.activeType;
        state.activate(selectedType, selectedState);
        if (previous != selectedType) {
            sendActiveTypeUpdate(level, selectedType);
        }
        return selectedType;
    }

    public static void clearActiveTable(ServerLevel level) {
        if (level == null) {
            return;
        }

        DimensionState state = DIMENSION_STATES.get(level.dimension());
        if (state == null) {
            return;
        }

        state.types.clear();
        clearCustomBossEvents(state);
        if (state.activeType != null) {
            state.clearActive();
            sendActiveTypeUpdate(level, null);
        }
    }

    public static void syncActiveType(ServerPlayer player) {
        if (player == null) {
            return;
        }

        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        EntityType<?> activeType = getActiveType(level);
        ResourceLocation dimensionId = level.dimension().location();
        ResourceLocation typeId = activeType != null ? BuiltInRegistries.ENTITY_TYPE.getKey(activeType) : null;
        NetworkHandler.sendToPlayer(new EntityExtensionActiveTypePacket(dimensionId, typeId), player);
    }

    private static void sendActiveTypeUpdate(ServerLevel level, EntityType<?> type) {
        if (level == null) {
            return;
        }

        ResourceLocation dimensionId = level.dimension().location();
        ResourceLocation typeId = type != null ? BuiltInRegistries.ENTITY_TYPE.getKey(type) : null;
        NetworkHandler.sendToDimension(new EntityExtensionActiveTypePacket(dimensionId, typeId), level);
    }

    private static void createCustomBossEventIfNeeded(LivingEntity entity, EntityExtension extension, DimensionState state) {
        if (entity == null || extension == null || state == null) {
            return;
        }
        if (!extension.shouldShowBossBar(entity)) {
            return;
        }

        UUID entityUuid = entity.getUUID();
        state.customBossEvents.computeIfAbsent(entityUuid, key -> {
            EcaBossEvent bossEvent = new EcaBossEvent(entityUuid, entity.getDisplayName());
            CustomBossEventState customState = new CustomBossEventState(entity, extension, bossEvent);
            updateCustomBossEvent(customState);
            return customState;
        });
    }

    private static void syncBossEventTypeMappings(LivingEntity entity, EntityExtension extension, DimensionState state) {
        if (entity == null || extension == null || state == null) {
            return;
        }

        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(extension.getEntityType());
        if (typeId == null) {
            return;
        }

        for (UUID bossEventId : collectBossEventIds(entity, state)) {
            NetworkHandler.sendToTrackingClients(new EntityExtensionBossEventTypePacket(bossEventId, typeId), entity);
        }
    }

    private static void syncBossEventTypeMappingsToPlayer(ServerPlayer player, LivingEntity entity, EntityExtension extension, DimensionState state) {
        if (player == null || entity == null || extension == null || state == null) {
            return;
        }

        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(extension.getEntityType());
        if (typeId == null) {
            return;
        }

        for (UUID bossEventId : collectBossEventIds(entity, state)) {
            NetworkHandler.sendToPlayer(new EntityExtensionBossEventTypePacket(bossEventId, typeId), player);
        }
    }

    private static void removeBossEventTypeMappings(LivingEntity entity, DimensionState state) {
        if (entity == null || state == null) {
            return;
        }

        for (UUID bossEventId : collectBossEventIds(entity, state)) {
            NetworkHandler.sendToTrackingClients(new EntityExtensionBossEventTypePacket(bossEventId, null), entity);
        }
    }

    private static void removeBossEventTypeMappingsFromPlayer(ServerPlayer player, LivingEntity entity, DimensionState state) {
        if (player == null || entity == null || state == null) {
            return;
        }

        for (UUID bossEventId : collectBossEventIds(entity, state)) {
            NetworkHandler.sendToPlayer(new EntityExtensionBossEventTypePacket(bossEventId, null), player);
        }
    }

    private static Set<UUID> collectBossEventIds(LivingEntity entity, DimensionState state) {
        Set<UUID> bossEventIds = new HashSet<>();
        if (entity == null || state == null) {
            return bossEventIds;
        }

        CustomBossEventState customBossEventState = state.customBossEvents.get(entity.getUUID());
        if (customBossEventState != null) {
            bossEventIds.add(customBossEventState.bossEvent.getId());
        }

        return bossEventIds;
    }

    private static void removeCustomBossEvent(UUID entityUuid, DimensionState state) {
        if (entityUuid == null || state == null) {
            return;
        }

        CustomBossEventState removed = state.customBossEvents.remove(entityUuid);
        if (removed != null) {
            removed.bossEvent.removeAllPlayers();
            removed.bossEvent.setVisible(false);
        }
    }

    private static void clearCustomBossEvents(DimensionState state) {
        if (state == null) {
            return;
        }
        for (CustomBossEventState customBossEventState : state.customBossEvents.values()) {
            if (customBossEventState != null) {
                customBossEventState.bossEvent.removeAllPlayers();
                customBossEventState.bossEvent.setVisible(false);
            }
        }
        state.customBossEvents.clear();
    }

    private static void tickCustomBossEvents(ServerLevel level, DimensionState state) {
        state.customBossEvents.entrySet().removeIf(entry -> {
            CustomBossEventState customBossEventState = entry.getValue();
            if (customBossEventState == null) {
                return true;
            }

            LivingEntity entity = customBossEventState.entity;
            if (entity == null || !entity.isAlive() || entity.level() != level) {
                customBossEventState.bossEvent.removeAllPlayers();
                customBossEventState.bossEvent.setVisible(false);
                return true;
            }

            if (!customBossEventState.extension.shouldShowBossBar(entity)) {
                customBossEventState.bossEvent.setVisible(false);
                return false;
            }

            customBossEventState.bossEvent.setVisible(true);
            updateCustomBossEvent(customBossEventState);
            return false;
        });
    }

    private static void updateCustomBossEvent(CustomBossEventState customBossEventState) {
        LivingEntity entity = customBossEventState.entity;
        EntityExtension extension = customBossEventState.extension;
        float currentHealth = resolveCurrentHealth(entity, extension);
        float maxHealth = resolveMaxHealth(entity, extension);
        customBossEventState.bossEvent.update(entity.getDisplayName(), currentHealth, maxHealth);
    }

    private static float resolveCurrentHealth(LivingEntity entity, EntityExtension extension) {
        float fallback = EcaAPI.getHealth(entity);
        if (!extension.enableCustomHealthOverride()) {
            return fallback;
        }

        Float custom = readNumericValue(extension.getCustomHealthValue(entity));
        return custom != null ? custom : fallback;
    }

    private static float resolveMaxHealth(LivingEntity entity, EntityExtension extension) {
        float fallback = entity.getMaxHealth();
        if (!extension.enableCustomMaxHealthOverride()) {
            return fallback;
        }

        Float custom = readNumericValue(extension.getCustomMaxHealthValue(entity));
        return custom != null ? custom : fallback;
    }

    private static Float readNumericValue(Number value) {
        if (value == null) {
            return null;
        }
        float result = value.floatValue();
        return Float.isFinite(result) ? result : null;
    }

    private static class DimensionState {
        final Map<EntityType<?>, ActiveTypeState> types = new ConcurrentHashMap<>();
        final Map<UUID, CustomBossEventState> customBossEvents = new ConcurrentHashMap<>();
        EntityType<?> activeType;
        int activePriority;
        long activeOrder;

        void activate(EntityType<?> type, ActiveTypeState state) {
            this.activeType = type;
            this.activePriority = state.priority;
            this.activeOrder = state.order;
        }

        void clearActive() {
            this.activeType = null;
            this.activePriority = 0;
            this.activeOrder = 0;
        }

        boolean shouldReplace(ActiveTypeState candidate) {
            return candidate.priority > activePriority ||
                (candidate.priority == activePriority && candidate.order > activeOrder);
        }
    }

    private static class ActiveTypeState {
        int count;
        int priority;
        long order;
    }

    private static class CustomBossEventState {
        final LivingEntity entity;
        final EntityExtension extension;
        final EcaBossEvent bossEvent;

        CustomBossEventState(LivingEntity entity, EntityExtension extension, EcaBossEvent bossEvent) {
            this.entity = entity;
            this.extension = extension;
            this.bossEvent = bossEvent;
        }
    }

    private EntityExtensionManager() {}
}
