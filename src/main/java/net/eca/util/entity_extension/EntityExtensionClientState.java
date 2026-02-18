package net.eca.util.entity_extension;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public final class EntityExtensionClientState {

    private static final Map<ResourceLocation, EntityType<?>> ACTIVE_TYPES = new ConcurrentHashMap<>();
    private static final Map<UUID, EntityType<?>> BOSS_EVENT_TYPES = new ConcurrentHashMap<>();

    public static void setActiveType(ResourceLocation dimensionId, ResourceLocation typeId) {
        if (dimensionId == null) {
            return;
        }

        if (typeId == null) {
            ACTIVE_TYPES.remove(dimensionId);
            return;
        }

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(typeId).orElse(null);
        if (type == null) {
            ACTIVE_TYPES.remove(dimensionId);
            return;
        }

        ACTIVE_TYPES.put(dimensionId, type);
    }

    public static EntityType<?> getActiveType(ResourceLocation dimensionId) {
        if (dimensionId == null) {
            return null;
        }
        return ACTIVE_TYPES.get(dimensionId);
    }

    public static void clear(ResourceLocation dimensionId) {
        if (dimensionId == null) {
            return;
        }
        ACTIVE_TYPES.remove(dimensionId);
    }

    public static void setBossEventType(UUID bossEventId, ResourceLocation typeId) {
        if (bossEventId == null) {
            return;
        }

        if (typeId == null) {
            BOSS_EVENT_TYPES.remove(bossEventId);
            return;
        }

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(typeId).orElse(null);
        if (type == null) {
            BOSS_EVENT_TYPES.remove(bossEventId);
            return;
        }

        BOSS_EVENT_TYPES.put(bossEventId, type);
    }

    public static EntityType<?> getBossEventType(UUID bossEventId) {
        if (bossEventId == null) {
            return null;
        }
        return BOSS_EVENT_TYPES.get(bossEventId);
    }

    private EntityExtensionClientState() {}
}
