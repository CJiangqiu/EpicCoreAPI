package net.eca.network;

import net.eca.util.bossshow.BossShowManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//C→S：客户端请求删除一个 BossShow 定义（同时删 JSON 文件）。删完服务端重发 Home 包刷新列表
public class BossShowDeleteEditorPacket {

    private final ResourceLocation id;

    public BossShowDeleteEditorPacket(ResourceLocation id) {
        this.id = id;
    }

    public ResourceLocation id() { return id; }

    public static void encode(BossShowDeleteEditorPacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.id);
    }

    public static BossShowDeleteEditorPacket decode(FriendlyByteBuf buf) {
        return new BossShowDeleteEditorPacket(buf.readResourceLocation());
    }

    public static void handle(BossShowDeleteEditorPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            boolean ok = BossShowManager.delete(msg.id);
            if (ok) {
                player.sendSystemMessage(Component.translatable("msg.eca.bossshow.deleted", msg.id.toString()));
                //重发 Home 包，客户端 Home 自动刷新
                NetworkHandler.sendToPlayer(
                    new BossShowOpenEditorHomePacket(BossShowManager.getAllDefinitions().values()),
                    player
                );
            } else {
                player.sendSystemMessage(Component.translatable("msg.eca.bossshow.delete_failed", msg.id.toString()));
            }
        });
        ctx.setPacketHandled(true);
    }
}
