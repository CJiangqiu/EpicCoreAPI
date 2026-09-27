package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.mixin.bridge.ServerTeleportConnectionBridge;
import net.eca.network.EntityTeleportSyncPacket;
import net.eca.network.NetworkHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin implements ServerTeleportConnectionBridge {

    @Shadow
    public ServerPlayer player;

    @Shadow
    private int tickCount;

    @Shadow
    private double firstGoodX;

    @Shadow
    private double firstGoodY;

    @Shadow
    private double firstGoodZ;

    @Shadow
    private double lastGoodX;

    @Shadow
    private double lastGoodY;

    @Shadow
    private double lastGoodZ;

    @Unique
    private Vec3 eca$pendingTeleportPosition;

    @Unique
    private float eca$pendingTeleportYRot;

    @Unique
    private float eca$pendingTeleportXRot;

    @Unique
    private boolean eca$pendingTeleportOnGround;

    @Unique
    private int eca$pendingTeleportId;

    @Unique
    private int eca$pendingTeleportTime;

    @Override
    public int eca$beginTeleport(double x, double y, double z, float yRot, float xRot, boolean onGround) {
        if (++this.eca$pendingTeleportId == Integer.MAX_VALUE) {
            this.eca$pendingTeleportId = 0;
        }
        this.eca$pendingTeleportPosition = new Vec3(x, y, z);
        this.eca$pendingTeleportYRot = yRot;
        this.eca$pendingTeleportXRot = xRot;
        this.eca$pendingTeleportOnGround = onGround;
        this.eca$pendingTeleportTime = this.tickCount;
        this.eca$sendPendingTeleport();
        return this.eca$pendingTeleportId;
    }

    @Override
    public void eca$completeTeleport(int teleportId, double x, double y, double z) {
        Vec3 expected = this.eca$pendingTeleportPosition;
        if (expected == null || teleportId != this.eca$pendingTeleportId
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || expected.distanceToSqr(x, y, z) > 1.0E-6) {
            return;
        }
        this.firstGoodX = expected.x;
        this.firstGoodY = expected.y;
        this.firstGoodZ = expected.z;
        this.lastGoodX = expected.x;
        this.lastGoodY = expected.y;
        this.lastGoodZ = expected.z;
        this.eca$pendingTeleportPosition = null;
    }

    // 确认到达前丢弃旧坐标移动包，避免网络乱序把玩家拉回传送前的位置。
    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void eca$holdMovementUntilTeleportAck(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (this.eca$pendingTeleportPosition == null) {
            return;
        }
        PacketUtils.ensureRunningOnSameThread(
                packet,
                (ServerGamePacketListenerImpl) (Object) this,
                this.player.serverLevel()
        );
        if (this.tickCount - this.eca$pendingTeleportTime > 20) {
            this.eca$pendingTeleportTime = this.tickCount;
            this.eca$sendPendingTeleport();
        }
        ci.cancel();
    }

    @Unique
    private void eca$sendPendingTeleport() {
        Vec3 target = this.eca$pendingTeleportPosition;
        if (target == null) {
            return;
        }
        NetworkHandler.sendToPlayer(new EntityTeleportSyncPacket(
                this.player.getId(),
                target.x,
                target.y,
                target.z,
                this.eca$pendingTeleportYRot,
                this.eca$pendingTeleportXRot,
                this.eca$pendingTeleportOnGround,
                this.eca$pendingTeleportId
        ), this.player);
    }

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
