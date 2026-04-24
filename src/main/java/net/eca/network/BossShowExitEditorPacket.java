package net.eca.network;

import net.eca.command.BossShowCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//C→S：客户端关闭编辑器，请求服务端还原 gamemode 并清理标记
public class BossShowExitEditorPacket {

    public BossShowExitEditorPacket() {}

    public static void encode(BossShowExitEditorPacket msg, FriendlyByteBuf buf) {}

    public static BossShowExitEditorPacket decode(FriendlyByteBuf buf) {
        return new BossShowExitEditorPacket();
    }

    public static void handle(BossShowExitEditorPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                BossShowCommand.restorePreviousGameMode(player);
            }
        });
        ctx.setPacketHandled(true);
    }
}
