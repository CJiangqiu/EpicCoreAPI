package net.eca.network;

import com.mojang.blaze3d.shaders.FogShape;
import net.eca.util.entity_extension.CombatMusicExtension;
import net.eca.util.entity_extension.EntityExtensionClientState;
import net.eca.util.entity_extension.GlobalEffectRegistry;
import net.eca.util.entity_extension.GlobalFogExtension;
import net.eca.util.entity_extension.GlobalSkyboxExtension;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class EntityExtensionOverridePacket {

    private static final byte ACTION_NONE = 0;
    private static final byte ACTION_SET = 1;
    private static final byte ACTION_CLEAR = 2;

    private final ResourceLocation dimensionId;
    private final byte fogAction;
    private final FogData fogData;
    private final byte skyboxAction;
    private final SkyboxData skyboxData;
    private final byte musicAction;
    private final MusicData musicData;

    private EntityExtensionOverridePacket(ResourceLocation dimensionId,
                                          byte fogAction, FogData fogData,
                                          byte skyboxAction, SkyboxData skyboxData,
                                          byte musicAction, MusicData musicData) {
        this.dimensionId = dimensionId;
        this.fogAction = fogAction;
        this.fogData = fogData;
        this.skyboxAction = skyboxAction;
        this.skyboxData = skyboxData;
        this.musicAction = musicAction;
        this.musicData = musicData;
    }

    public static EntityExtensionOverridePacket setFog(ResourceLocation dimensionId, FogData data) {
        return new EntityExtensionOverridePacket(dimensionId, ACTION_SET, data, ACTION_NONE, null, ACTION_NONE, null);
    }

    public static EntityExtensionOverridePacket clearFog(ResourceLocation dimensionId) {
        return new EntityExtensionOverridePacket(dimensionId, ACTION_CLEAR, null, ACTION_NONE, null, ACTION_NONE, null);
    }

    public static EntityExtensionOverridePacket setSkybox(ResourceLocation dimensionId, SkyboxData data) {
        return new EntityExtensionOverridePacket(dimensionId, ACTION_NONE, null, ACTION_SET, data, ACTION_NONE, null);
    }

    public static EntityExtensionOverridePacket clearSkybox(ResourceLocation dimensionId) {
        return new EntityExtensionOverridePacket(dimensionId, ACTION_NONE, null, ACTION_CLEAR, null, ACTION_NONE, null);
    }

    public static EntityExtensionOverridePacket setMusic(ResourceLocation dimensionId, MusicData data) {
        return new EntityExtensionOverridePacket(dimensionId, ACTION_NONE, null, ACTION_NONE, null, ACTION_SET, data);
    }

    public static EntityExtensionOverridePacket clearMusic(ResourceLocation dimensionId) {
        return new EntityExtensionOverridePacket(dimensionId, ACTION_NONE, null, ACTION_NONE, null, ACTION_CLEAR, null);
    }

    public static EntityExtensionOverridePacket full(ResourceLocation dimensionId,
                                                      byte fogAction, FogData fogData,
                                                      byte skyboxAction, SkyboxData skyboxData,
                                                      byte musicAction, MusicData musicData) {
        return new EntityExtensionOverridePacket(dimensionId, fogAction, fogData, skyboxAction, skyboxData, musicAction, musicData);
    }

    // ==================== 编解码 ====================

    public static void encode(EntityExtensionOverridePacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.dimensionId);

        buf.writeByte(msg.fogAction);
        if (msg.fogAction == ACTION_SET && msg.fogData != null) {
            msg.fogData.write(buf);
        }

        buf.writeByte(msg.skyboxAction);
        if (msg.skyboxAction == ACTION_SET && msg.skyboxData != null) {
            msg.skyboxData.write(buf);
        }

        buf.writeByte(msg.musicAction);
        if (msg.musicAction == ACTION_SET && msg.musicData != null) {
            msg.musicData.write(buf);
        }
    }

    public static EntityExtensionOverridePacket decode(FriendlyByteBuf buf) {
        ResourceLocation dimensionId = buf.readResourceLocation();

        byte fogAction = buf.readByte();
        FogData fogData = (fogAction == ACTION_SET) ? FogData.read(buf) : null;

        byte skyboxAction = buf.readByte();
        SkyboxData skyboxData = (skyboxAction == ACTION_SET) ? SkyboxData.read(buf) : null;

        byte musicAction = buf.readByte();
        MusicData musicData = (musicAction == ACTION_SET) ? MusicData.read(buf) : null;

        return new EntityExtensionOverridePacket(dimensionId, fogAction, fogData, skyboxAction, skyboxData, musicAction, musicData);
    }

    public static void handle(EntityExtensionOverridePacket msg, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            if (msg.fogAction == ACTION_SET && msg.fogData != null) {
                EntityExtensionClientState.setActiveFog(msg.dimensionId, msg.fogData.toExtension());
            } else if (msg.fogAction == ACTION_CLEAR) {
                EntityExtensionClientState.setActiveFog(msg.dimensionId, null);
            }

            if (msg.skyboxAction == ACTION_SET && msg.skyboxData != null) {
                EntityExtensionClientState.setActiveSkybox(msg.dimensionId, msg.skyboxData.toExtension());
            } else if (msg.skyboxAction == ACTION_CLEAR) {
                EntityExtensionClientState.setActiveSkybox(msg.dimensionId, null);
            }

            if (msg.musicAction == ACTION_SET && msg.musicData != null) {
                EntityExtensionClientState.setActiveMusic(msg.dimensionId, msg.musicData.toExtension());
            } else if (msg.musicAction == ACTION_CLEAR) {
                EntityExtensionClientState.setActiveMusic(msg.dimensionId, null);
            }
        });
        ctx.setPacketHandled(true);
    }

    // ==================== 数据载体 ====================

    public static class FogData {
        public final boolean globalMode;
        public final float radius;
        public final float red;
        public final float green;
        public final float blue;
        public final float terrainStartFactor;
        public final float terrainEndFactor;
        public final float skyStartFactor;
        public final float skyEndFactor;
        public final int fogShapeOrdinal;

        public FogData(boolean globalMode, float radius,
                       float red, float green, float blue,
                       float terrainStartFactor, float terrainEndFactor,
                       float skyStartFactor, float skyEndFactor,
                       int fogShapeOrdinal) {
            this.globalMode = globalMode;
            this.radius = radius;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.terrainStartFactor = terrainStartFactor;
            this.terrainEndFactor = terrainEndFactor;
            this.skyStartFactor = skyStartFactor;
            this.skyEndFactor = skyEndFactor;
            this.fogShapeOrdinal = fogShapeOrdinal;
        }

        public static FogData fromExtension(GlobalFogExtension fog) {
            return new FogData(
                fog.globalMode(),
                fog.radius(),
                fog.fogRed(),
                fog.fogGreen(),
                fog.fogBlue(),
                fog.terrainFogStart(1.0f),
                fog.terrainFogEnd(1.0f),
                fog.skyFogStart(1.0f),
                fog.skyFogEnd(1.0f),
                fog.fogShape().ordinal()
            );
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(globalMode);
            buf.writeFloat(radius);
            buf.writeFloat(red);
            buf.writeFloat(green);
            buf.writeFloat(blue);
            buf.writeFloat(terrainStartFactor);
            buf.writeFloat(terrainEndFactor);
            buf.writeFloat(skyStartFactor);
            buf.writeFloat(skyEndFactor);
            buf.writeByte(fogShapeOrdinal);
        }

        public static FogData read(FriendlyByteBuf buf) {
            return new FogData(
                buf.readBoolean(),
                buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(),
                buf.readByte()
            );
        }

        public GlobalFogExtension toExtension() {
            FogData self = this;
            FogShape shape = (fogShapeOrdinal >= 0 && fogShapeOrdinal < FogShape.values().length)
                ? FogShape.values()[fogShapeOrdinal] : FogShape.CYLINDER;
            return new GlobalFogExtension() {
                @Override public boolean enabled() { return true; }
                @Override public boolean globalMode() { return self.globalMode; }
                @Override public float radius() { return self.radius; }
                @Override public float fogRed() { return self.red; }
                @Override public float fogGreen() { return self.green; }
                @Override public float fogBlue() { return self.blue; }
                @Override public float terrainFogStart(float rd) { return rd * self.terrainStartFactor; }
                @Override public float terrainFogEnd(float rd) { return rd * self.terrainEndFactor; }
                @Override public float skyFogStart(float rd) { return rd * self.skyStartFactor; }
                @Override public float skyFogEnd(float rd) { return rd * self.skyEndFactor; }
                @Override public FogShape fogShape() { return shape; }
            };
        }
    }

    public static class SkyboxData {
        public final boolean enableTexture;
        public final ResourceLocation texture;
        public final boolean enableShader;
        public final ResourceLocation shaderPresetId;
        public final float alpha;
        public final float size;
        public final float textureUvScale;
        public final float textureRed;
        public final float textureGreen;
        public final float textureBlue;

        public SkyboxData(boolean enableTexture, ResourceLocation texture,
                          boolean enableShader, ResourceLocation shaderPresetId,
                          float alpha, float size, float textureUvScale,
                          float textureRed, float textureGreen, float textureBlue) {
            this.enableTexture = enableTexture;
            this.texture = texture;
            this.enableShader = enableShader;
            this.shaderPresetId = shaderPresetId;
            this.alpha = alpha;
            this.size = size;
            this.textureUvScale = textureUvScale;
            this.textureRed = textureRed;
            this.textureGreen = textureGreen;
            this.textureBlue = textureBlue;
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(enableTexture);
            buf.writeBoolean(texture != null);
            if (texture != null) {
                buf.writeResourceLocation(texture);
            }
            buf.writeBoolean(enableShader);
            buf.writeBoolean(shaderPresetId != null);
            if (shaderPresetId != null) {
                buf.writeResourceLocation(shaderPresetId);
            }
            buf.writeFloat(alpha);
            buf.writeFloat(size);
            buf.writeFloat(textureUvScale);
            buf.writeFloat(textureRed);
            buf.writeFloat(textureGreen);
            buf.writeFloat(textureBlue);
        }

        public static SkyboxData read(FriendlyByteBuf buf) {
            boolean enableTexture = buf.readBoolean();
            ResourceLocation texture = buf.readBoolean() ? buf.readResourceLocation() : null;
            boolean enableShader = buf.readBoolean();
            ResourceLocation shaderPresetId = buf.readBoolean() ? buf.readResourceLocation() : null;
            float alpha = buf.readFloat();
            float size = buf.readFloat();
            float textureUvScale = buf.readFloat();
            float textureRed = buf.readFloat();
            float textureGreen = buf.readFloat();
            float textureBlue = buf.readFloat();
            return new SkyboxData(enableTexture, texture, enableShader, shaderPresetId,
                alpha, size, textureUvScale, textureRed, textureGreen, textureBlue);
        }

        public GlobalSkyboxExtension toExtension() {
            SkyboxData self = this;
            return new GlobalSkyboxExtension() {
                @Override public boolean enabled() { return true; }
                @Override public boolean enableTexture() { return self.enableTexture; }
                @Override public ResourceLocation texture() { return self.texture; }
                @Override public boolean enableShader() { return self.enableShader; }
                @Override public RenderType shaderRenderType() {
                    return GlobalEffectRegistry.getSkyboxPreset(self.shaderPresetId);
                }
                @Override public float alpha() { return self.alpha; }
                @Override public float size() { return self.size; }
                @Override public float textureUvScale() { return self.textureUvScale; }
                @Override public float textureRed() { return self.textureRed; }
                @Override public float textureGreen() { return self.textureGreen; }
                @Override public float textureBlue() { return self.textureBlue; }
            };
        }
    }

    public static class MusicData {
        public final ResourceLocation soundEventId;
        public final int soundSourceOrdinal;
        public final float volume;
        public final float pitch;
        public final boolean loop;
        public final boolean strictMusicLock;

        public MusicData(ResourceLocation soundEventId, int soundSourceOrdinal,
                         float volume, float pitch, boolean loop, boolean strictMusicLock) {
            this.soundEventId = soundEventId;
            this.soundSourceOrdinal = soundSourceOrdinal;
            this.volume = volume;
            this.pitch = pitch;
            this.loop = loop;
            this.strictMusicLock = strictMusicLock;
        }

        public static MusicData fromExtension(CombatMusicExtension music) {
            return new MusicData(
                music.soundEventId(),
                music.soundSource().ordinal(),
                music.volume(),
                music.pitch(),
                music.loop(),
                music.strictMusicLock()
            );
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(soundEventId != null);
            if (soundEventId != null) {
                buf.writeResourceLocation(soundEventId);
            }
            buf.writeByte(soundSourceOrdinal);
            buf.writeFloat(volume);
            buf.writeFloat(pitch);
            buf.writeBoolean(loop);
            buf.writeBoolean(strictMusicLock);
        }

        public static MusicData read(FriendlyByteBuf buf) {
            ResourceLocation soundEventId = buf.readBoolean() ? buf.readResourceLocation() : null;
            int soundSourceOrdinal = buf.readByte();
            float volume = buf.readFloat();
            float pitch = buf.readFloat();
            boolean loop = buf.readBoolean();
            boolean strictMusicLock = buf.readBoolean();
            return new MusicData(soundEventId, soundSourceOrdinal, volume, pitch, loop, strictMusicLock);
        }

        public CombatMusicExtension toExtension() {
            MusicData self = this;
            SoundSource source = (soundSourceOrdinal >= 0 && soundSourceOrdinal < SoundSource.values().length)
                ? SoundSource.values()[soundSourceOrdinal] : SoundSource.MUSIC;
            return new CombatMusicExtension() {
                @Override public boolean enabled() { return true; }
                @Override public ResourceLocation soundEventId() { return self.soundEventId; }
                @Override public SoundSource soundSource() { return source; }
                @Override public float volume() { return self.volume; }
                @Override public float pitch() { return self.pitch; }
                @Override public boolean loop() { return self.loop; }
                @Override public boolean strictMusicLock() { return self.strictMusicLock; }
            };
        }
    }
}
