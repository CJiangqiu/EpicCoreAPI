package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        // 无敌状态下每tick调用复活API
        if (EcaAPI.isInvulnerable(self)) {
            EntityUtil.reviveEntity(self);
        }
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void onHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (EcaAPI.isInvulnerable((LivingEntity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "actuallyHurt", at = @At("HEAD"), cancellable = true)
    private void onActuallyHurt(DamageSource source, float amount, CallbackInfo ci) {
        if (EcaAPI.isInvulnerable((LivingEntity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void onDie(DamageSource source, CallbackInfo ci) {
        if (EcaAPI.isInvulnerable((LivingEntity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "tickDeath", at = @At("HEAD"), cancellable = true)
    private void onTickDeath(CallbackInfo ci) {
        if (EcaAPI.isInvulnerable((LivingEntity) (Object) this)) {
            ci.cancel();
        }
    }
}
