package net.eca.network;

import net.eca.util.bossshow.BossShowClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//S→C：停止当前 BossShow 演出
public class BossShowStopPacket {

    private final ResourceLocation cutsceneId;
    private final boolean skipped;

    public BossShowStopPacket(ResourceLocation cutsceneId, boolean skipped) {
        this.cutsceneId = cutsceneId;
        this.skipped = skipped;
    }

    public static void encode(BossShowStopPacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.cutsceneId);
        buf.writeBoolean(msg.skipped);
    }

    public static BossShowStopPacket decode(FriendlyByteBuf buf) {
        return new BossShowStopPacket(buf.readResourceLocation(), buf.readBoolean());
    }

    public static void handle(BossShowStopPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandlerRef.onStop(msg)));
        ctx.setPacketHandled(true);
    }

    public ResourceLocation cutsceneId() { return cutsceneId; }
    public boolean skipped() { return skipped; }

    private static final class ClientHandlerRef {
        static void onStop(BossShowStopPacket msg) {
            BossShowClientState.onServerStop(msg);
        }
    }
}
