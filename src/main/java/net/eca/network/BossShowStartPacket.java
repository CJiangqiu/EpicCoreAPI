package net.eca.network;

import net.eca.util.bossshow.BossShowClientState;
import net.eca.util.bossshow.BossShowDefinition;
import net.eca.util.bossshow.BossShowDefinition.Frame;
import net.eca.util.bossshow.BossShowNetCodec;
import net.eca.util.bossshow.Trigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

//S→C：开始播放一个 BossShow 演出
public class BossShowStartPacket {

    private final ResourceLocation cutsceneId;
    private final ResourceLocation targetTypeId;
    private final UUID targetUuid;
    private final double anchorX, anchorY, anchorZ;
    private final float anchorYaw;
    private final String triggerType;
    private final double triggerRadius;
    private final boolean cinematic;
    private final List<Frame> frames;

    public BossShowStartPacket(BossShowDefinition def, UUID targetUuid, double anchorX, double anchorY, double anchorZ, float anchorYaw) {
        this.cutsceneId = def.id();
        this.targetTypeId = def.targetType() != null
            ? BuiltInRegistries.ENTITY_TYPE.getKey(def.targetType())
            : null;
        this.targetUuid = targetUuid;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.anchorYaw = anchorYaw;
        this.triggerType = def.trigger().type();
        this.triggerRadius = def.trigger() instanceof Trigger.Range r ? r.effectRadius() : 0.0;
        this.cinematic = def.cinematic();
        this.frames = def.frames();
    }

    private BossShowStartPacket(ResourceLocation cutsceneId, ResourceLocation targetTypeId, UUID targetUuid,
                                double anchorX, double anchorY, double anchorZ, float anchorYaw,
                                String triggerType, double triggerRadius, boolean cinematic,
                                List<Frame> frames) {
        this.cutsceneId = cutsceneId;
        this.targetTypeId = targetTypeId;
        this.targetUuid = targetUuid;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.anchorYaw = anchorYaw;
        this.triggerType = triggerType;
        this.triggerRadius = triggerRadius;
        this.cinematic = cinematic;
        this.frames = frames;
    }

    public static void encode(BossShowStartPacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.cutsceneId);
        BossShowNetCodec.writeNullableRL(buf, msg.targetTypeId);
        buf.writeUUID(msg.targetUuid);
        buf.writeDouble(msg.anchorX);
        buf.writeDouble(msg.anchorY);
        buf.writeDouble(msg.anchorZ);
        buf.writeFloat(msg.anchorYaw);
        buf.writeUtf(msg.triggerType);
        buf.writeDouble(msg.triggerRadius);
        buf.writeBoolean(msg.cinematic);
        BossShowNetCodec.writeFrames(buf, msg.frames);
    }

    public static BossShowStartPacket decode(FriendlyByteBuf buf) {
        ResourceLocation cutsceneId = buf.readResourceLocation();
        ResourceLocation typeId = BossShowNetCodec.readNullableRL(buf);
        UUID uuid = buf.readUUID();
        double ax = buf.readDouble();
        double ay = buf.readDouble();
        double az = buf.readDouble();
        float yaw = buf.readFloat();
        String trigType = buf.readUtf(64);
        double trigRadius = buf.readDouble();
        boolean cine = buf.readBoolean();
        List<Frame> frames = BossShowNetCodec.readFrames(buf);
        return new BossShowStartPacket(cutsceneId, typeId, uuid, ax, ay, az, yaw, trigType, trigRadius, cine, frames);
    }

    public static void handle(BossShowStartPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandlerRef.onStart(msg)));
        ctx.setPacketHandled(true);
    }

    public ResourceLocation cutsceneId() { return cutsceneId; }
    public ResourceLocation targetTypeId() { return targetTypeId; }
    public UUID targetUuid() { return targetUuid; }
    public double anchorX() { return anchorX; }
    public double anchorY() { return anchorY; }
    public double anchorZ() { return anchorZ; }
    public float anchorYaw() { return anchorYaw; }
    public String triggerType() { return triggerType; }
    public double triggerRadius() { return triggerRadius; }
    public boolean cinematic() { return cinematic; }
    public List<Frame> frames() { return Collections.unmodifiableList(frames); }

    private static final class ClientHandlerRef {
        static void onStart(BossShowStartPacket msg) {
            BossShowClientState.onServerStart(msg);
        }
    }
}
