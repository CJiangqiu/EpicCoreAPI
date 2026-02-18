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

    // 强加载实体：替换原版距离判断，使用配置的最大渲染距离
    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void eca$forceTrackPlayer(ServerPlayer player, CallbackInfo ci) {
        if (player == this.entity) {
            return;
        }
        if (!ForceLoadingManager.isForceLoadedType(this.entity.getType())) {
            return;
        }

        double maxDist = EcaConfiguration.getForceLoadingMaxRenderDistanceSafely();
        double distSqr = player.distanceToSqr(this.entity);

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
