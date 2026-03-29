package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    //拦截踢人操作，防止无敌玩家被踢出服务器
    @Inject(method = "disconnect",
            at = @At("HEAD"), cancellable = true)
    private void eca$onDisconnect(Component reason, CallbackInfo ci) {
        if (EcaAPI.isInvulnerable(this.player)) {
            ci.cancel();
        }
    }

    //拦截危险数据包（断线包、死亡包），防止无敌玩家被恶意包影响
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), cancellable = true)
    private void eca$onSendPacket(Packet<?> packet, CallbackInfo ci) {
        if (!EcaAPI.isInvulnerable(this.player)) {
            return;
        }

        if (packet instanceof ClientboundDisconnectPacket) {
            ci.cancel();
            return;
        }

        if (packet instanceof ClientboundPlayerCombatKillPacket) {
            ci.cancel();
        }
    }
}
