package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.health.health_lock.HealthLockManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.util.ITeleporter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {

    @Unique
    private final Deque<Boolean> eca$commandTeleportScopes = new ArrayDeque<>();

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void eca$onDie(DamageSource source, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (EcaAPI.isInvulnerable(self) || HealthLockManager.getLock(self) != null) {
            ci.cancel();
        }
    }

    // 玩家重写的双参数入口不经过 Entity 的实现，需要独立放行旧世界清理。
    @Inject(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraftforge/common/util/ITeleporter;)Lnet/minecraft/world/entity/Entity;",
            at = @At("HEAD"), remap = false)
    private void eca$beforeCustomDimensionChange(ServerLevel destination, ITeleporter teleporter, CallbackInfoReturnable<Entity> cir) {
        EntityUtil.beginDimensionChange((ServerPlayer) (Object) this);
    }

    @Inject(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraftforge/common/util/ITeleporter;)Lnet/minecraft/world/entity/Entity;",
            at = @At("RETURN"), remap = false)
    private void eca$afterCustomDimensionChange(ServerLevel destination, ITeleporter teleporter, CallbackInfoReturnable<Entity> cir) {
        EntityUtil.finishDimensionChange((ServerPlayer) (Object) this);
    }

    // 同维度传送不持有放行层级，也不能释放外层跨维度传送的层级。
    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V", at = @At("HEAD"))
    private void eca$markCommandTeleport(ServerLevel destination, double x, double y, double z, float yRot, float xRot, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        boolean crossDimension = destination != null && destination != self.level();
        eca$commandTeleportScopes.push(crossDimension);
        if (crossDimension) {
            EntityUtil.beginDimensionChange(self);
        }
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V", at = @At("RETURN"))
    private void eca$unmarkCommandTeleport(ServerLevel destination, double x, double y, double z, float yRot, float xRot, CallbackInfo ci) {
        if (!eca$commandTeleportScopes.isEmpty() && eca$commandTeleportScopes.pop()) {
            EntityUtil.endDimensionChange((ServerPlayer) (Object) this);
        }
    }
}
