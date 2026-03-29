package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Shadow
    private PacketListener packetListener;

    //兜底拦截底层连接断开，防止无敌玩家被强制断线
    @Inject(method = "disconnect",
            at = @At("HEAD"), cancellable = true)
    private void eca$onConnectionDisconnect(Component reason, CallbackInfo ci) {
        if (this.packetListener instanceof ServerGamePacketListenerImpl listener) {
            ServerPlayer player = listener.player;
            if (player != null && EcaAPI.isInvulnerable(player)) {
                ci.cancel();
            }
        }
    }
}
