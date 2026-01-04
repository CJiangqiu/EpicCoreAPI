package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.health.HealthLockManager;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    //静态初始化注入：定义 EntityDataAccessor
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void eca$onClinit(CallbackInfo ci) {
        EntityUtil.HEALTH_LOCK_ENABLED = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.BOOLEAN);
        EntityUtil.HEALTH_LOCK_VALUE = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.FLOAT);
        EntityUtil.INVULNERABLE = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.BOOLEAN);
    }

    //注册实体数据（在每个实例的 defineSynchedData 中调用）
    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void eca$onDefineSynchedData(CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        entity.getEntityData().define(EntityUtil.HEALTH_LOCK_ENABLED, false);
        entity.getEntityData().define(EntityUtil.HEALTH_LOCK_VALUE, 0.0f);
        entity.getEntityData().define(EntityUtil.INVULNERABLE, false);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (EcaAPI.isInvulnerable(self) || EcaAPI.isHealthLocked(self)) {
            float lockedValue = self.getEntityData().get(EntityUtil.HEALTH_LOCK_VALUE);
            float currentHealth = EntityUtil.getHealth(self);
            // 避免浮点数精度问题，只在血量确实变化时才重置
            if (Math.abs(currentHealth - lockedValue) > 0.001f) {
                EntityUtil.setHealth(self, lockedValue);
                EntityUtil.reviveEntity(self);

            }
        }
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void onHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (EcaAPI.isInvulnerable(self)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "actuallyHurt", at = @At("HEAD"), cancellable = true)
    private void onActuallyHurt(DamageSource source, float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (EcaAPI.isInvulnerable(self)) {
            ci.cancel();
        }
    }

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void onDie(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (EcaAPI.isInvulnerable(self) || EcaAPI.isHealthLocked(self)) {
            ci.cancel();
        }
    }

    @Inject(method = "tickDeath", at = @At("HEAD"), cancellable = true)
    private void onTickDeath(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (EcaAPI.isInvulnerable(self) || EcaAPI.isHealthLocked(self)) {
            ci.cancel();
        }
    }

    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void onSetHealth(float health, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        // 检查是否处于锁血或无敌状态
        boolean isLocked = EcaAPI.isHealthLocked(self);
        boolean isInvulnerable = EcaAPI.isInvulnerable(self);

        if (!isLocked && !isInvulnerable) {
            return;
        }
        // 获取锁定值
        Float lockValue = HealthLockManager.getLock(self);
        if (lockValue == null) {
            return;
        }
        if (Math.abs(health - lockValue) < 0.001f) {
            return;
        }
        ci.cancel();
    }
}
