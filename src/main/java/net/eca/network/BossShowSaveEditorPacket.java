package net.eca.network;

import net.eca.util.bossshow.BossShowDefinition;
import net.eca.util.bossshow.BossShowDefinition.Marker;
import net.eca.util.bossshow.BossShowDefinition.Sample;
import net.eca.util.bossshow.BossShowManager;
import net.eca.util.bossshow.BossShowNetCodec;
import net.eca.util.bossshow.Trigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

//C→S：客户端把当前编辑中的 BossShow 定义提交保存
public class BossShowSaveEditorPacket {

    private final ResourceLocation cutsceneId;
    private final ResourceLocation targetTypeId;
    private final Trigger trigger;
    private final boolean cinematic;
    private final boolean allowRepeat;
    private final List<Sample> samples;
    private final List<Marker> markers;
    private final float anchorYawDeg;

    public BossShowSaveEditorPacket(ResourceLocation cutsceneId, ResourceLocation targetTypeId,
                                    Trigger trigger, boolean cinematic, boolean allowRepeat,
                                    List<Sample> samples, List<Marker> markers,
                                    float anchorYawDeg) {
        this.cutsceneId = cutsceneId;
        this.targetTypeId = targetTypeId;
        this.trigger = trigger;
        this.cinematic = cinematic;
        this.allowRepeat = allowRepeat;
        this.samples = samples;
        this.markers = markers;
        this.anchorYawDeg = anchorYawDeg;
    }

    public static void encode(BossShowSaveEditorPacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.cutsceneId);
        BossShowNetCodec.writeNullableRL(buf, msg.targetTypeId);
        BossShowNetCodec.writeTrigger(buf, msg.trigger);
        buf.writeBoolean(msg.cinematic);
        buf.writeBoolean(msg.allowRepeat);
        BossShowNetCodec.writeSamples(buf, msg.samples);
        BossShowNetCodec.writeMarkers(buf, msg.markers);
        buf.writeFloat(msg.anchorYawDeg);
    }

    public static BossShowSaveEditorPacket decode(FriendlyByteBuf buf) {
        ResourceLocation cutsceneId = buf.readResourceLocation();
        ResourceLocation typeId = BossShowNetCodec.readNullableRL(buf);
        Trigger trigger = BossShowNetCodec.readTrigger(buf);
        boolean cine = buf.readBoolean();
        boolean allowRepeat = buf.readBoolean();
        List<Sample> samples = BossShowNetCodec.readSamples(buf);
        List<Marker> markers = BossShowNetCodec.readMarkers(buf);
        float yaw = buf.readFloat();
        return new BossShowSaveEditorPacket(cutsceneId, typeId, trigger, cine, allowRepeat, samples, markers, yaw);
    }

    public static void handle(BossShowSaveEditorPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            EntityType<?> type = (msg.targetTypeId != null && BuiltInRegistries.ENTITY_TYPE.containsKey(msg.targetTypeId))
                ? BuiltInRegistries.ENTITY_TYPE.get(msg.targetTypeId)
                : null;
            //过滤越界 marker，与 BossShowJsonCodec.parse 行为一致
            int sampleCount = msg.samples.size();
            List<Marker> sanitized = new java.util.ArrayList<>(msg.markers.size());
            for (Marker m : msg.markers) {
                if (m.tickOffset() >= 0 && m.tickOffset() < sampleCount) {
                    sanitized.add(m);
                }
            }
            BossShowDefinition def = new BossShowDefinition(
                msg.cutsceneId, type, msg.trigger, msg.cinematic, msg.allowRepeat,
                msg.samples, sanitized,
                BossShowDefinition.Source.CONFIG, msg.anchorYawDeg);
            boolean ok = BossShowManager.save(def);
            if (ok) {
                player.sendSystemMessage(Component.literal("§aSaved BossShow " + msg.cutsceneId));
            } else {
                player.sendSystemMessage(Component.literal("§cFailed to save BossShow " + msg.cutsceneId + " (see server log)"));
            }
        });
        ctx.setPacketHandled(true);
    }
}
