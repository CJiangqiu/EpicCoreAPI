package net.eca.network;

import net.eca.util.bossshow.BossShowPlaybackTracker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//C→S：玩家按 ESC 请求跳过当前演出
public class BossShowSkipPacket {

    public BossShowSkipPacket() {}

    public static void encode(BossShowSkipPacket msg, FriendlyByteBuf buf) {}

    public static BossShowSkipPacket decode(FriendlyByteBuf buf) {
        return new BossShowSkipPacket();
    }

    public static void handle(BossShowSkipPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                BossShowPlaybackTracker.onClientSkip(player);
            }
        });
        ctx.setPacketHandled(true);
    }
}
