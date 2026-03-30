package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.health.HealthLockManager;
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
}
