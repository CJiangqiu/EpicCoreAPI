package net.eca.util.entity_extension;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
    private static final Map<UUID, UUID> BOSS_EVENT_ENTITY_UUIDS = new ConcurrentHashMap<>();

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
        // activeType 切换瞬间先按默认状态解析一次，避免首个条件 tick 之前的空窗
        resolveConditionalEffects(dimensionId, extension, null);
    }

    /*
     * 按当前主实体状态重新解析全局效果，每个条件 tick 调用；维度被服务端覆写时跳过对应效果。
     * 主实体未定位（entity 为 null）时不应用 shouldEnableXxx 门控，仅按 enabled() 解析。
     */
    private static void resolveConditionalEffects(ResourceLocation dimensionId, EntityExtension extension, LivingEntity entity) {
        if (!OVERRIDE_FOGS.contains(dimensionId)) {
            GlobalFogExtension fog = extension.globalFogExtension(entity);
            if (fog != null && fog.enabled() && (entity == null || extension.shouldEnableFog(entity))) {
                ACTIVE_FOGS.put(dimensionId, fog);
            } else {
                ACTIVE_FOGS.remove(dimensionId);
            }
        }
        if (!OVERRIDE_SKYBOXES.contains(dimensionId)) {
            GlobalSkyboxExtension skybox = extension.globalSkyboxExtension(entity);
            if (skybox != null && skybox.enabled() && (entity == null || extension.shouldEnableSkybox(entity))) {
                ACTIVE_SKYBOXES.put(dimensionId, skybox);
            } else {
                ACTIVE_SKYBOXES.remove(dimensionId);
            }
        }
        if (!OVERRIDE_MUSICS.contains(dimensionId)) {
            CombatMusicExtension music = extension.combatMusicExtension(entity);
            if (music != null && music.enabled() && (entity == null || extension.shouldEnableMusic(entity))) {
                ACTIVE_MUSICS.put(dimensionId, music);
            } else {
                ACTIVE_MUSICS.remove(dimensionId);
            }
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

    public static void tickConditions(ResourceLocation dimensionId, ClientLevel level) {
        if (dimensionId == null || level == null) {
            return;
        }

        EntityType<?> type = ACTIVE_TYPES.get(dimensionId);
        if (type == null) {
            return;
        }

        EntityExtension extension = EntityExtensionManager.getExtension(type);
        if (extension == null) {
            return;
        }

        resolveConditionalEffects(dimensionId, extension, findPrimaryEntity(level, type));
    }

    private static LivingEntity findPrimaryEntity(ClientLevel level, EntityType<?> type) {
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.getType() == type && entity instanceof LivingEntity living && living.isAlive()) {
                return living;
            }
        }
        return null;
    }

    // ==================== Boss Event 映射 ====================

    public static void setBossEventType(UUID bossEventId, ResourceLocation typeId, UUID entityUuid) {
        if (bossEventId == null) {
            return;
        }

        if (typeId == null) {
            BOSS_EVENT_TYPES.remove(bossEventId);
            BOSS_EVENT_ENTITY_UUIDS.remove(bossEventId);
            return;
        }

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(typeId).orElse(null);
        if (type == null) {
            BOSS_EVENT_TYPES.remove(bossEventId);
            BOSS_EVENT_ENTITY_UUIDS.remove(bossEventId);
            return;
        }

        BOSS_EVENT_TYPES.put(bossEventId, type);
        if (entityUuid != null) {
            BOSS_EVENT_ENTITY_UUIDS.put(bossEventId, entityUuid);
        } else {
            BOSS_EVENT_ENTITY_UUIDS.remove(bossEventId);
        }
    }

    public static EntityType<?> getBossEventType(UUID bossEventId) {
        if (bossEventId == null) {
            return null;
        }
        return BOSS_EVENT_TYPES.get(bossEventId);
    }

    public static UUID getBossEventEntityUuid(UUID bossEventId) {
        if (bossEventId == null) {
            return null;
        }
        return BOSS_EVENT_ENTITY_UUIDS.get(bossEventId);
    }

    // 清除全部客户端状态，断开连接时调用，防止单人模式下静态状态跨存档残留
    public static void clearAll() {
        ACTIVE_TYPES.clear();
        BOSS_EVENT_TYPES.clear();
        BOSS_EVENT_ENTITY_UUIDS.clear();
        ACTIVE_FOGS.clear();
        ACTIVE_SKYBOXES.clear();
        ACTIVE_MUSICS.clear();
        ACTIVE_PRIORITIES.clear();
        OVERRIDE_FOGS.clear();
        OVERRIDE_SKYBOXES.clear();
        OVERRIDE_MUSICS.clear();
    }

    private EntityExtensionClientState() {}
}
