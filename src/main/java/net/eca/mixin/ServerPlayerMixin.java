package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.health.HealthLockManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void eca$onDie(DamageSource source, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (EcaAPI.isInvulnerable(self) || HealthLockManager.getLock(self) != null) {
            ci.cancel();
        }
    }

    //指令/指路石等跨维度传送走 teleportTo 而非 changeDimension，补上维度切换标记，避免无敌保护误拦移除
    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V", at = @At("HEAD"))
    private void eca$markCommandTeleport(ServerLevel destination, double x, double y, double z, float yRot, float xRot, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (destination != null && destination != self.level()) {
            EntityUtil.markDimensionChanging(self);
        }
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V", at = @At("TAIL"))
    private void eca$unmarkCommandTeleport(ServerLevel destination, double x, double y, double z, float yRot, float xRot, CallbackInfo ci) {
        EntityUtil.unmarkDimensionChanging((ServerPlayer) (Object) this);
    }
}
