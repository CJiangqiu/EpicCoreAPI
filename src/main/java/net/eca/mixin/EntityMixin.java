package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntityMixin {

    @Inject(method = "kill", at = @At("HEAD"), cancellable = true)
    private void onKill(CallbackInfo ci) {
        if (EcaAPI.isInvulnerable((Entity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "discard", at = @At("HEAD"), cancellable = true)
    private void onDiscard(CallbackInfo ci) {
        if (EcaAPI.isInvulnerable((Entity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        // Allow players to change dimensions even when invulnerable
        if (entity instanceof Player && reason == Entity.RemovalReason.CHANGED_DIMENSION) {
            return;
        }
        if (EcaAPI.isInvulnerable(entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "setRemoved", at = @At("HEAD"), cancellable = true)
    private void onSetRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        // Allow players to change dimensions even when invulnerable
        if (entity instanceof Player && reason == Entity.RemovalReason.CHANGED_DIMENSION) {
            return;
        }
        if (EcaAPI.isInvulnerable(entity)) {
            ci.cancel();
        }
    }
}
