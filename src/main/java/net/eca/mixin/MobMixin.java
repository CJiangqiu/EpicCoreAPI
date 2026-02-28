package net.eca.mixin;

import net.eca.api.EcaAPI;

import net.eca.util.entity_extension.ForceLoadingManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public class MobMixin {

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void onSetTarget(LivingEntity target, CallbackInfo ci) {
        if (EcaAPI.isInvulnerable(target)) {
            ci.cancel();
        }
    }

    // 强加载实体：阻止因距离过远导致的自然消失
    @Inject(method = "checkDespawn", at = @At("HEAD"), cancellable = true)
    private void eca$preventForceDespawn(CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (ForceLoadingManager.isForceLoadedType(self.getType())) {
            ci.cancel();
        }
    }
}
