package net.eca.util.entity_extension;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public final class EntityExtensionClientState {

    private static final Map<ResourceLocation, EntityType<?>> ACTIVE_TYPES = new ConcurrentHashMap<>();
    private static final Map<UUID, EntityType<?>> BOSS_EVENT_TYPES = new ConcurrentHashMap<>();

    private static final Map<ResourceLocation, GlobalFogExtension> ACTIVE_FOGS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, GlobalSkyboxExtension> ACTIVE_SKYBOXES = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, CombatMusicExtension> ACTIVE_MUSICS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Integer> ACTIVE_PRIORITIES = new ConcurrentHashMap<>();

    private static final Set<ResourceLocation> OVERRIDE_FOGS = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> OVERRIDE_SKYBOXES = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> OVERRIDE_MUSICS = ConcurrentHashMap.newKeySet();

    public static void setActiveType(ResourceLocation dimensionId, ResourceLocation typeId) {
        if (dimensionId == null) {
            return;
        }

        if (typeId == null) {
            ACTIVE_TYPES.remove(dimensionId);
            clearEffectCaches(dimensionId);
            return;
        }

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(typeId).orElse(null);
        if (type == null) {
            ACTIVE_TYPES.remove(dimensionId);
            clearEffectCaches(dimensionId);
            return;
        }

        ACTIVE_TYPES.put(dimensionId, type);
        populateEffectCaches(dimensionId, type);
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
        clearEffectCaches(dimensionId);
    }

    // ==================== 全局效果缓存 ====================

    public static GlobalFogExtension getActiveFog(ResourceLocation dimensionId) {
        if (dimensionId == null) {
            return null;
        }
        return ACTIVE_FOGS.get(dimensionId);
    }

    public static GlobalSkyboxExtension getActiveSkybox(ResourceLocation dimensionId) {
        if (dimensionId == null) {
            return null;
        }
        return ACTIVE_SKYBOXES.get(dimensionId);
    }

    public static CombatMusicExtension getActiveMusic(ResourceLocation dimensionId) {
        if (dimensionId == null) {
            return null;
        }
        return ACTIVE_MUSICS.get(dimensionId);
    }

    public static void setActiveFog(ResourceLocation dimensionId, GlobalFogExtension fog) {
        setActiveFog(dimensionId, fog, false);
    }

    public static void setActiveFog(ResourceLocation dimensionId, GlobalFogExtension fog, boolean override) {
        if (dimensionId == null) {
            return;
        }
        if (fog == null) {
            ACTIVE_FOGS.remove(dimensionId);
            OVERRIDE_FOGS.remove(dimensionId);
        } else {
            ACTIVE_FOGS.put(dimensionId, fog);
            if (override) {
                OVERRIDE_FOGS.add(dimensionId);
            }
        }
    }

    public static void setActiveSkybox(ResourceLocation dimensionId, GlobalSkyboxExtension skybox) {
        setActiveSkybox(dimensionId, skybox, false);
    }

    public static void setActiveSkybox(ResourceLocation dimensionId, GlobalSkyboxExtension skybox, boolean override) {
        if (dimensionId == null) {
            return;
        }
        if (skybox == null) {
            ACTIVE_SKYBOXES.remove(dimensionId);
            OVERRIDE_SKYBOXES.remove(dimensionId);
        } else {
            ACTIVE_SKYBOXES.put(dimensionId, skybox);
            if (override) {
                OVERRIDE_SKYBOXES.add(dimensionId);
            }
        }
    }

    public static void setActiveMusic(ResourceLocation dimensionId, CombatMusicExtension music) {
        setActiveMusic(dimensionId, music, false);
    }

    public static void setActiveMusic(ResourceLocation dimensionId, CombatMusicExtension music, boolean override) {
        if (dimensionId == null) {
            return;
        }
        if (music == null) {
            ACTIVE_MUSICS.remove(dimensionId);
            OVERRIDE_MUSICS.remove(dimensionId);
        } else {
            ACTIVE_MUSICS.put(dimensionId, music);
            if (override) {
                OVERRIDE_MUSICS.add(dimensionId);
            }
        }
    }

    private static void populateEffectCaches(ResourceLocation dimensionId, EntityType<?> type) {
        EntityExtension extension = EntityExtensionManager.getExtension(type);
        if (extension == null) {
            clearEffectCaches(dimensionId);
            return;
        }
        int extensionPriority = extension.getPriority();
        int currentPriority = ACTIVE_PRIORITIES.getOrDefault(dimensionId, 0);
        if (extensionPriority < currentPriority) {
            return;
        }
        ACTIVE_PRIORITIES.put(dimensionId, extensionPriority);
        GlobalFogExtension fog = extension.globalFogExtension();
        if (fog != null && fog.enabled()) {
            ACTIVE_FOGS.put(dimensionId, fog);
        } else {
            ACTIVE_FOGS.remove(dimensionId);
        }
        GlobalSkyboxExtension skybox = extension.globalSkyboxExtension();
        if (skybox != null && skybox.enabled()) {
            ACTIVE_SKYBOXES.put(dimensionId, skybox);
        } else {
            ACTIVE_SKYBOXES.remove(dimensionId);
        }
        CombatMusicExtension music = extension.combatMusicExtension();
        if (music != null && music.enabled()) {
            ACTIVE_MUSICS.put(dimensionId, music);
        } else {
            ACTIVE_MUSICS.remove(dimensionId);
        }
    }

    private static void clearEffectCaches(ResourceLocation dimensionId) {
        if (!OVERRIDE_FOGS.contains(dimensionId)) {
            ACTIVE_FOGS.remove(dimensionId);
        }
        if (!OVERRIDE_SKYBOXES.contains(dimensionId)) {
            ACTIVE_SKYBOXES.remove(dimensionId);
        }
        if (!OVERRIDE_MUSICS.contains(dimensionId)) {
            ACTIVE_MUSICS.remove(dimensionId);
        }
        ACTIVE_PRIORITIES.remove(dimensionId);
    }

    // ==================== Boss Event 映射 ====================

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
