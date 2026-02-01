package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.health.HealthLockManager;
import net.minecraft.nbt.CompoundTag;
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
    private static final String NBT_INVULNERABLE = "ecaInvulnerable";
    private static final String NBT_HEALTH_LOCK_ENABLED = "ecaHealthLockEnabled";
    private static final String NBT_HEALTH_LOCK_VALUE = "ecaHealthLockValue";


    //静态初始化注入EntityDataAccessor
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void eca$onClinit(CallbackInfo ci) {
        EntityUtil.HEALTH_LOCK_ENABLED = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.BOOLEAN);
        EntityUtil.HEALTH_LOCK_VALUE = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.STRING);
        EntityUtil.INVULNERABLE = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.BOOLEAN);
    }

    //注册实体数据（在每个实例的defineSynchedData 中调用）
    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void eca$onDefineSynchedData(CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        entity.getEntityData().define(EntityUtil.HEALTH_LOCK_ENABLED, false);
        entity.getEntityData().define(EntityUtil.HEALTH_LOCK_VALUE, "-1024.0");
        entity.getEntityData().define(EntityUtil.INVULNERABLE, false);
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void eca$writeAdditionalSaveData(CompoundTag tag, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (EntityUtil.INVULNERABLE == null ||
            EntityUtil.HEALTH_LOCK_ENABLED == null ||
            EntityUtil.HEALTH_LOCK_VALUE == null) {
            return;
        }

        tag.putBoolean(NBT_INVULNERABLE, entity.getEntityData().get(EntityUtil.INVULNERABLE));
        tag.putBoolean(NBT_HEALTH_LOCK_ENABLED, entity.getEntityData().get(EntityUtil.HEALTH_LOCK_ENABLED));
        tag.putString(NBT_HEALTH_LOCK_VALUE, entity.getEntityData().get(EntityUtil.HEALTH_LOCK_VALUE));
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void eca$readAdditionalSaveData(CompoundTag tag, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (EntityUtil.INVULNERABLE == null ||
            EntityUtil.HEALTH_LOCK_ENABLED == null ||
            EntityUtil.HEALTH_LOCK_VALUE == null) {
            return;
        }

        if (tag.contains(NBT_INVULNERABLE)) {
            entity.getEntityData().set(EntityUtil.INVULNERABLE, tag.getBoolean(NBT_INVULNERABLE));
        }
        if (tag.contains(NBT_HEALTH_LOCK_ENABLED)) {
            entity.getEntityData().set(EntityUtil.HEALTH_LOCK_ENABLED, tag.getBoolean(NBT_HEALTH_LOCK_ENABLED));
        }
        if (tag.contains(NBT_HEALTH_LOCK_VALUE, 8)) {
            entity.getEntityData().set(EntityUtil.HEALTH_LOCK_VALUE, tag.getString(NBT_HEALTH_LOCK_VALUE));
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        boolean invulnerable = EcaAPI.isInvulnerable(self);
        boolean healthLocked = EcaAPI.isHealthLocked(self);

        if (invulnerable || healthLocked) {
            Float lockedValue = HealthLockManager.getLock(self);
            if (lockedValue != null) {
                float currentHealth = EntityUtil.getHealth(self);
                // Avoid floating-point precision issues before resetting.
                if (Math.abs(currentHealth - lockedValue) > 0.001f) {
                    EntityUtil.setHealth(self, lockedValue);
                    EntityUtil.reviveEntity(self);
                }
            }
        }

        if (invulnerable) {
            EntityUtil.clearRemovalReasonIfProtected(self);
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

    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void onSetHealth(float health, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        // 检查是否处于锁血或无敌状态
        boolean isLocked = EcaAPI.isHealthLocked(self);
        boolean isInvulnerable = EcaAPI.isInvulnerable(self);

        if (!isLocked && !isInvulnerable) {
            return;
        }
        // 获取锁定
        Float lockValue = HealthLockManager.getLock(self);
        if (lockValue == null) {
            return;
        }
        if (Math.abs(health - lockValue) < 0.001f) {
            return;
        }
        ci.cancel();
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

    @Inject(method = "isDeadOrDying", at = @At("HEAD"), cancellable = true)
    private void onIsDeadOrDying(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        Float locked = HealthLockManager.getLock(self);
        if (EcaAPI.isInvulnerable(self) || (locked != null && locked > 0.0f)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isAlive", at = @At("HEAD"), cancellable = true)
    private void onIsAlive(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        Float locked = HealthLockManager.getLock(self);
        if (EcaAPI.isInvulnerable(self) || (locked != null && locked > 0.0f)) {
            cir.setReturnValue(true);
        }
    }


}




