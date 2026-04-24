package net.eca.network;

import net.eca.client.gui.BossShowEditorHomeScreen;
import net.eca.util.bossshow.BossShowDefinition;
import net.eca.util.bossshow.BossShowDefinition.Marker;
import net.eca.util.bossshow.BossShowDefinition.Sample;
import net.eca.util.bossshow.BossShowNetCodec;
import net.eca.util.bossshow.Trigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

//S→C：打开 BossShow 编辑器 Home 界面，携带服务端当前所有定义的完整数据
public class BossShowOpenEditorHomePacket {

    private final List<BossShowDefinition> definitions;

    public BossShowOpenEditorHomePacket(Collection<BossShowDefinition> defs) {
        this.definitions = new ArrayList<>(defs);
    }

    public static void encode(BossShowOpenEditorHomePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.definitions.size());
        for (BossShowDefinition def : msg.definitions) {
            buf.writeResourceLocation(def.id());
            ResourceLocation typeId = def.targetType() != null
                ? BuiltInRegistries.ENTITY_TYPE.getKey(def.targetType())
                : null;
            BossShowNetCodec.writeNullableRL(buf, typeId);
            BossShowNetCodec.writeTrigger(buf, def.trigger());
            buf.writeBoolean(def.cinematic());
            buf.writeBoolean(def.allowRepeat());
            BossShowNetCodec.writeSamples(buf, def.samples());
            BossShowNetCodec.writeMarkers(buf, def.markers());
            buf.writeFloat(def.anchorYawDeg());
        }
    }

    public static BossShowOpenEditorHomePacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<BossShowDefinition> defs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ResourceLocation id = buf.readResourceLocation();
            ResourceLocation typeId = BossShowNetCodec.readNullableRL(buf);
            Trigger trig = BossShowNetCodec.readTrigger(buf);
            boolean cine = buf.readBoolean();
            boolean allowRepeat = buf.readBoolean();
            List<Sample> samples = BossShowNetCodec.readSamples(buf);
            List<Marker> markers = BossShowNetCodec.readMarkers(buf);
            float yaw = buf.readFloat();
            EntityType<?> type = (typeId != null && BuiltInRegistries.ENTITY_TYPE.containsKey(typeId))
                ? BuiltInRegistries.ENTITY_TYPE.get(typeId)
                : null;
            defs.add(new BossShowDefinition(id, type, trig, cine, allowRepeat, samples, markers, BossShowDefinition.Source.CONFIG, yaw));
        }
        return new BossShowOpenEditorHomePacket(defs);
    }

    public static void handle(BossShowOpenEditorHomePacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandlerRef.onOpen(msg)));
        ctx.setPacketHandled(true);
    }

    public List<BossShowDefinition> definitions() {
        return Collections.unmodifiableList(definitions);
    }

    private static final class ClientHandlerRef {
        static void onOpen(BossShowOpenEditorHomePacket msg) {
            BossShowEditorHomeScreen.openFromPacket(msg);
        }
    }
}
