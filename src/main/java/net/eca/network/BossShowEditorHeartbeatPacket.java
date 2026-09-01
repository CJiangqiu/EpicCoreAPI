package net.eca.network;

import net.eca.util.bossshow.BossShowEditorSessionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class BossShowEditorHeartbeatPacket {

    public static void encode(BossShowEditorHeartbeatPacket message, FriendlyByteBuf buffer) {}

    public static BossShowEditorHeartbeatPacket decode(FriendlyByteBuf buffer) {
        return new BossShowEditorHeartbeatPacket();
    }

    public static void handle(BossShowEditorHeartbeatPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                BossShowEditorSessionManager.heartbeat(player);
            }
        });
        context.setPacketHandled(true);
    }
}
