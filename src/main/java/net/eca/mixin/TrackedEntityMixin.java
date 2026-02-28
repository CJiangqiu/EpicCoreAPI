package net.eca.mixin;

import net.eca.config.EcaConfiguration;

import net.eca.util.entity_extension.ForceLoadingManager;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

// AT 已解锁 ChunkMap$TrackedEntity 为 public，直接引用
@Mixin(ChunkMap.TrackedEntity.class)
public class TrackedEntityMixin {

    @Shadow @Final public Entity entity;
    @Shadow @Final public ServerEntity serverEntity;
    @Shadow @Final public Set<ServerPlayerConnection> seenBy;

    // 强加载实体：替换原版距离判断，取配置值与原版范围的较大者，避免降低原版可见性
    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void eca$forceTrackPlayer(ServerPlayer player, CallbackInfo ci) {
        if (player == this.entity) {
            return;
        }
        if (!ForceLoadingManager.isForceLoadedType(this.entity.getType())) {
            return;
        }

        // 以配置值与原版 clientTrackingRange 的较大者为准，确保不低于原版可见距离
        double vanillaRange = this.entity.getType().clientTrackingRange() * 16.0;
        double maxDist = Math.max(EcaConfiguration.getForceLoadingMaxRenderDistanceSafely(), vanillaRange);

        // 使用水平距离（与原版 updatePlayer 一致，不含 Y 轴）
        double dx = player.getX() - this.entity.getX();
        double dz = player.getZ() - this.entity.getZ();
        double distSqr = dx * dx + dz * dz;

        if (distSqr <= maxDist * maxDist) {
            if (this.seenBy.add(player.connection)) {
                this.serverEntity.addPairing(player);
            }
        } else {
            if (this.seenBy.remove(player.connection)) {
                this.serverEntity.removePairing(player);
            }
        }
        ci.cancel();
    }
}
