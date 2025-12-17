package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * 网络层防御Mixin集合
 * 包含三层防护：清包保护、踢人保护、连接保护
 */
public final class NetworkDefenceMixin {
    private NetworkDefenceMixin() {}

    /* ─────────── 1. 清包保护 ─────────── */
    @Mixin(Inventory.class)
    public static abstract class InventoryClearPrevent {

        @Final
        @Shadow
        public Player player;

        /**
         * 拦截清除匹配物品的操作
         * 防止无敌玩家的物品被清除
         */
        @Inject(method = "clearOrCountMatchingItems",
                at = @At("HEAD"), cancellable = true)
        private void onClearOrCountMatchingItems(Predicate<ItemStack> predicate,
                                                  int maxCount,
                                                  Container container,
                                                  CallbackInfoReturnable<Integer> cir) {
            if (EcaAPI.isInvulnerable(this.player)) {
                cir.setReturnValue(0);
            }
        }

        /**
         * 拦截清空背包内容的操作
         * 防止无敌玩家的背包被清空
         */
        @Inject(method = "clearContent",
                at = @At("HEAD"), cancellable = true)
        private void onClearContent(CallbackInfo ci) {
            if (EcaAPI.isInvulnerable(this.player)) {
                ci.cancel();
            }
        }
    }

    /* ─────────── 2. 踢人 & 断线包 保护 ─────────── */
    @Mixin(ServerGamePacketListenerImpl.class)
    public static abstract class ServerPacketListenerKickPrevent {

        @Shadow
        public ServerPlayer player;

        /**
         * 拦截 /kick 命令和主动断线
         * 防止无敌玩家被踢出服务器
         */
        @Inject(method = "disconnect",
                at = @At("HEAD"), cancellable = true)
        private void onDisconnect(Component reason, CallbackInfo ci) {
            if (EcaAPI.isInvulnerable(this.player)) {
                ci.cancel();
            }
        }

        /**
         * 拦截服务器发往客户端的危险数据包
         * 防止无敌玩家收到恶意包（断线包、死亡包、重生包等）
         */
        @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
                at = @At("HEAD"), cancellable = true)
        private void onSendPacket(Packet<?> packet, CallbackInfo ci) {
            if (!EcaAPI.isInvulnerable(this.player)) {
                return;
            }

            // 拦截断线包
            if (packet instanceof ClientboundDisconnectPacket) {
                ci.cancel();
                return;
            }

            // 拦截战斗死亡包
            if (packet instanceof ClientboundPlayerCombatKillPacket) {
                ci.cancel();
            }

        }
    }

    /* ─────────── 3. Connection 兜底保护 ─────────── */
    @Mixin(Connection.class)
    public static abstract class ConnectionDisconnectPrevent {

        @Shadow
        private PacketListener packetListener;

        /**
         * 拦截底层连接的断开操作
         * 作为最后一道防线，防止无敌玩家被强制断线
         */
        @Inject(method = "disconnect",
                at = @At("HEAD"), cancellable = true)
        private void onConnectionDisconnect(Component reason, CallbackInfo ci) {
            if (this.packetListener instanceof ServerGamePacketListenerImpl listener) {
                ServerPlayer player = listener.player;
                if (player != null && EcaAPI.isInvulnerable(player)) {
                    ci.cancel();
                }
            }
        }
    }
}
