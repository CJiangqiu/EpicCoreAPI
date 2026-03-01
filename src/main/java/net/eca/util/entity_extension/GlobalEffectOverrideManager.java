package net.eca.util.entity_extension;

import net.eca.network.EntityExtensionOverridePacket;
import net.eca.network.EntityExtensionOverridePacket.FogData;
import net.eca.network.EntityExtensionOverridePacket.SkyboxData;
import net.eca.network.EntityExtensionOverridePacket.MusicData;
import net.eca.network.NetworkHandler;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GlobalEffectOverrideManager {

    private static final Map<ResourceKey<Level>, FogData> FOG_OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, SkyboxData> SKYBOX_OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, MusicData> MUSIC_OVERRIDES = new ConcurrentHashMap<>();

    // ==================== Fog ====================

    public static void setFog(ServerLevel level, FogData data) {
        if (level == null || data == null) {
            return;
        }
        FOG_OVERRIDES.put(level.dimension(), data);
        ResourceLocation dimensionId = level.dimension().location();
        NetworkHandler.sendToDimension(EntityExtensionOverridePacket.setFog(dimensionId, data), level);
    }

    public static void clearFog(ServerLevel level) {
        if (level == null) {
            return;
        }
        FOG_OVERRIDES.remove(level.dimension());
        ResourceLocation dimensionId = level.dimension().location();
        NetworkHandler.sendToDimension(EntityExtensionOverridePacket.clearFog(dimensionId), level);
    }

    // ==================== Skybox ====================

    public static void setSkybox(ServerLevel level, SkyboxData data) {
        if (level == null || data == null) {
            return;
        }
        SKYBOX_OVERRIDES.put(level.dimension(), data);
        ResourceLocation dimensionId = level.dimension().location();
        NetworkHandler.sendToDimension(EntityExtensionOverridePacket.setSkybox(dimensionId, data), level);
    }

    public static void clearSkybox(ServerLevel level) {
        if (level == null) {
            return;
        }
        SKYBOX_OVERRIDES.remove(level.dimension());
        ResourceLocation dimensionId = level.dimension().location();
        NetworkHandler.sendToDimension(EntityExtensionOverridePacket.clearSkybox(dimensionId), level);
    }

    // ==================== Music ====================

    public static void setMusic(ServerLevel level, MusicData data) {
        if (level == null || data == null) {
            return;
        }
        MUSIC_OVERRIDES.put(level.dimension(), data);
        ResourceLocation dimensionId = level.dimension().location();
        NetworkHandler.sendToDimension(EntityExtensionOverridePacket.setMusic(dimensionId, data), level);
    }

    public static void clearMusic(ServerLevel level) {
        if (level == null) {
            return;
        }
        MUSIC_OVERRIDES.remove(level.dimension());
        ResourceLocation dimensionId = level.dimension().location();
        NetworkHandler.sendToDimension(EntityExtensionOverridePacket.clearMusic(dimensionId), level);
    }

    // ==================== 全部清除 ====================

    public static void clearAll(ServerLevel level) {
        if (level == null) {
            return;
        }
        ResourceKey<Level> key = level.dimension();
        boolean hadFog = FOG_OVERRIDES.remove(key) != null;
        boolean hadSkybox = SKYBOX_OVERRIDES.remove(key) != null;
        boolean hadMusic = MUSIC_OVERRIDES.remove(key) != null;

        if (hadFog || hadSkybox || hadMusic) {
            ResourceLocation dimensionId = key.location();
            byte fogAction = hadFog ? (byte) 2 : (byte) 0;
            byte skyboxAction = hadSkybox ? (byte) 2 : (byte) 0;
            byte musicAction = hadMusic ? (byte) 2 : (byte) 0;
            NetworkHandler.sendToDimension(
                EntityExtensionOverridePacket.full(dimensionId, fogAction, null, skyboxAction, null, musicAction, null),
                level
            );
        }
    }

    // ==================== 玩家同步 ====================

    public static void syncToPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        ResourceKey<Level> key = level.dimension();
        ResourceLocation dimensionId = key.location();

        FogData fog = FOG_OVERRIDES.get(key);
        SkyboxData skybox = SKYBOX_OVERRIDES.get(key);
        MusicData music = MUSIC_OVERRIDES.get(key);

        if (fog == null && skybox == null && music == null) {
            return;
        }

        byte fogAction = fog != null ? (byte) 1 : (byte) 0;
        byte skyboxAction = skybox != null ? (byte) 1 : (byte) 0;
        byte musicAction = music != null ? (byte) 1 : (byte) 0;

        NetworkHandler.sendToPlayer(
            EntityExtensionOverridePacket.full(dimensionId, fogAction, fog, skyboxAction, skybox, musicAction, music),
            player
        );
    }

    private GlobalEffectOverrideManager() {}
}
