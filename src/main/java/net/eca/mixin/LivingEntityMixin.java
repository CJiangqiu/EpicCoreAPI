package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.config.EcaConfiguration;
import net.eca.util.EntityUtil;
import net.eca.util.InvulnerableEntityManager;
import net.eca.util.ResurrectionManager;
import net.eca.util.faction.FactionUtil;
import net.eca.util.health.HealthLockManager;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;

@Mixin(LivingEntity.class)
public abstract class
LivingEntityMixin {
    private static final String NBT_INVULNERABLE = "ecaInvulnerable";
    private static final String NBT_HEALTH_LOCK_ENC   = "ecaHealthLockEnc";
    private static final String NBT_HEALTH_LOCK_KEY   = "ecaHealthLockKey";
    private static final String NBT_HEALTH_LOCK_CHECK = "ecaHealthLockCheck";
    private static final String NBT_HEAL_BAN_VALUE = "ecaHealBanValue";
    private static final String NBT_MAX_HEALTH_LOCK_ENC   = "ecaMaxHealthLockEnc";
    private static final String NBT_MAX_HEALTH_LOCK_KEY   = "ecaMaxHealthLockKey";
    private static final String NBT_MAX_HEALTH_LOCK_CHECK = "ecaMaxHealthLockCheck";
    private static final String NBT_RESURRECTION_TRACKED = "ecaResurrectionTracked";

    // 防止本钩子与其他 mod 的 setHealth 注入互相递归导致 StackOverflowError
    private static final ThreadLocal<Boolean> ECA_IN_SET_HEALTH = ThreadLocal.withInitial(() -> false);

    private static int parseIntSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }

    //静态初始化注入EntityDataAccessor
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void eca$onClinit(CallbackInfo ci) {
        EntityUtil.HEALTH_LOCK_VALUE = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.STRING);
        EntityUtil.HEALTH_LOCK_KEY   = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.STRING);
        EntityUtil.HEALTH_LOCK_CHECK = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.STRING);
        EntityUtil.HEAL_BAN_VALUE = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.STRING);
        EntityUtil.INVULNERABLE = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.BOOLEAN);
        EntityUtil.RESURRECTION_TRACKED = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.BOOLEAN);
        EntityUtil.MAX_HEALTH_LOCK_VALUE = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.STRING);
        EntityUtil.MAX_HEALTH_LOCK_KEY   = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.STRING);
        EntityUtil.MAX_HEALTH_LOCK_CHECK = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.STRING);
    }

    //注册实体数据（在每个实例的defineSynchedData 中调用）
    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void eca$onDefineSynchedData(CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        entity.getEntityData().define(EntityUtil.HEALTH_LOCK_VALUE, "");
        entity.getEntityData().define(EntityUtil.HEALTH_LOCK_KEY,   "0");
        entity.getEntityData().define(EntityUtil.HEALTH_LOCK_CHECK, "");
        entity.getEntityData().define(EntityUtil.HEAL_BAN_VALUE, "");
        entity.getEntityData().define(EntityUtil.INVULNERABLE, false);
        entity.getEntityData().define(EntityUtil.RESURRECTION_TRACKED, false);
        entity.getEntityData().define(EntityUtil.MAX_HEALTH_LOCK_VALUE, "");
        entity.getEntityData().define(EntityUtil.MAX_HEALTH_LOCK_KEY,   "0");
        entity.getEntityData().define(EntityUtil.MAX_HEALTH_LOCK_CHECK, "");
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void eca$writeAdditionalSaveData(CompoundTag tag, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (EntityUtil.INVULNERABLE == null ||
                EntityUtil.HEALTH_LOCK_VALUE == null ||
                EntityUtil.HEALTH_LOCK_KEY == null ||
                EntityUtil.HEALTH_LOCK_CHECK == null ||
                EntityUtil.HEAL_BAN_VALUE == null ||
                EntityUtil.MAX_HEALTH_LOCK_VALUE == null ||
                EntityUtil.MAX_HEALTH_LOCK_KEY == null ||
                EntityUtil.MAX_HEALTH_LOCK_CHECK == null) {
            return;
        }

        tag.putBoolean(NBT_INVULNERABLE, entity.getEntityData().get(EntityUtil.INVULNERABLE));
        tag.putInt(NBT_HEALTH_LOCK_ENC, parseIntSafe(entity.getEntityData().get(EntityUtil.HEALTH_LOCK_VALUE)));
        tag.putInt(NBT_HEALTH_LOCK_KEY, parseIntSafe(entity.getEntityData().get(EntityUtil.HEALTH_LOCK_KEY)));
        tag.putInt(NBT_HEALTH_LOCK_CHECK, parseIntSafe(entity.getEntityData().get(EntityUtil.HEALTH_LOCK_CHECK)));
        tag.putString(NBT_HEAL_BAN_VALUE, entity.getEntityData().get(EntityUtil.HEAL_BAN_VALUE));
        tag.putInt(NBT_MAX_HEALTH_LOCK_ENC, parseIntSafe(entity.getEntityData().get(EntityUtil.MAX_HEALTH_LOCK_VALUE)));
        tag.putInt(NBT_MAX_HEALTH_LOCK_KEY, parseIntSafe(entity.getEntityData().get(EntityUtil.MAX_HEALTH_LOCK_KEY)));
        tag.putInt(NBT_MAX_HEALTH_LOCK_CHECK, parseIntSafe(entity.getEntityData().get(EntityUtil.MAX_HEALTH_LOCK_CHECK)));
        tag.putBoolean(NBT_RESURRECTION_TRACKED, entity.getEntityData().get(EntityUtil.RESURRECTION_TRACKED));
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void eca$readAdditionalSaveData(CompoundTag tag, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (EntityUtil.INVULNERABLE == null ||
                EntityUtil.HEALTH_LOCK_VALUE == null ||
                EntityUtil.HEALTH_LOCK_KEY == null ||
                EntityUtil.HEALTH_LOCK_CHECK == null ||
                EntityUtil.HEAL_BAN_VALUE == null ||
                EntityUtil.MAX_HEALTH_LOCK_VALUE == null ||
                EntityUtil.MAX_HEALTH_LOCK_KEY == null ||
                EntityUtil.MAX_HEALTH_LOCK_CHECK == null) {
            return;
        }

        if (tag.contains(NBT_INVULNERABLE)) {
            boolean invulnerable = tag.getBoolean(NBT_INVULNERABLE);
            entity.getEntityData().set(EntityUtil.INVULNERABLE, invulnerable);
            if (invulnerable) {
                InvulnerableEntityManager.addInvulnerable(entity);
                EcaAPI.restoreInvulnerableFastPath(entity.getId());
            } else {
                InvulnerableEntityManager.removeInvulnerable(entity);
            }
        }
        // 锁血（新加密格式 int）
        if (tag.contains(NBT_HEALTH_LOCK_ENC)) {
            entity.getEntityData().set(EntityUtil.HEALTH_LOCK_VALUE, String.valueOf(tag.getInt(NBT_HEALTH_LOCK_ENC)));
            entity.getEntityData().set(EntityUtil.HEALTH_LOCK_KEY,   String.valueOf(tag.getInt(NBT_HEALTH_LOCK_KEY)));
            entity.getEntityData().set(EntityUtil.HEALTH_LOCK_CHECK, String.valueOf(tag.getInt(NBT_HEALTH_LOCK_CHECK)));
        }
        if (tag.contains(NBT_HEAL_BAN_VALUE, 8)) {
            // TODO(1.1.6): 删除此迁移；将 1.1.5 前老哨兵 "-1024.0" 归一化为空串，避免被误判为 healBan=0
            String healBan = tag.getString(NBT_HEAL_BAN_VALUE);
            if ("-1024.0".equals(healBan)) healBan = "";
            entity.getEntityData().set(EntityUtil.HEAL_BAN_VALUE, healBan);
        }
        // 最大血量锁定（新加密格式 int）
        if (tag.contains(NBT_MAX_HEALTH_LOCK_ENC)) {
            entity.getEntityData().set(EntityUtil.MAX_HEALTH_LOCK_VALUE, String.valueOf(tag.getInt(NBT_MAX_HEALTH_LOCK_ENC)));
            entity.getEntityData().set(EntityUtil.MAX_HEALTH_LOCK_KEY,   String.valueOf(tag.getInt(NBT_MAX_HEALTH_LOCK_KEY)));
            entity.getEntityData().set(EntityUtil.MAX_HEALTH_LOCK_CHECK, String.valueOf(tag.getInt(NBT_MAX_HEALTH_LOCK_CHECK)));
        }

        // NBT 恢复完成后重新填充快速路径集合（否则 getLock/getHealBan/getMaxHealthLock 因快速路径为空返回 null）
        HealthLockManager.restoreFastPaths(entity);

        // 恢复复活追踪状态
        if (tag.contains(NBT_RESURRECTION_TRACKED)) {
            boolean tracked = tag.getBoolean(NBT_RESURRECTION_TRACKED);
            entity.getEntityData().set(EntityUtil.RESURRECTION_TRACKED, tracked);
            if (tracked) {
                ResurrectionManager.add(entity);
            }
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        boolean invulnerable = EcaAPI.isInvulnerable(self);
        Float lockedValue = HealthLockManager.getLock(self);
        Float healBanValue = HealthLockManager.getHealBan(self);

        if (lockedValue != null) {
            // 激进防御：清除外部 mod 注入的数值类型实体数据
            if (EcaConfiguration.getDefenceEnableRadicalLogicSafely()) {
                EntityUtil.clearForeignEntityData(self);
            }
            float currentHealth = EntityUtil.getHealth(self);
            if (Math.abs(currentHealth - lockedValue) > 0.001f) {
                EntityUtil.revive(self);
                EntityUtil.setBasicHealth(self, lockedValue);
            }
        } else if (healBanValue != null) {
            float currentHealth = EntityUtil.getHealth(self);
            if (currentHealth > healBanValue) {
                EntityUtil.setHealth(self, healBanValue);
            }
        }

        //最大生命值锁定：每tick强制恢复到锁定值
        Float maxHealthLock = HealthLockManager.getMaxHealthLock(self);
        if (maxHealthLock != null) {
            float currentMaxHealth = self.getMaxHealth();
            if (Math.abs(currentMaxHealth - maxHealthLock) > 0.01f) {
                EntityUtil.setMaxHealth(self, maxHealthLock);
            }
        }

        if (invulnerable) {
            if (!self.level().isClientSide && !self.getActiveEffects().isEmpty()) {
                for (MobEffectInstance effectInstance : new ArrayList<>(self.getActiveEffects())) {
                    if (effectInstance.getEffect().getCategory() == MobEffectCategory.HARMFUL) {
                        self.removeEffect(effectInstance.getEffect());
                    }
                }
            }
            EntityUtil.clearRemovalReasonIfProtected(self);
        }

        // 阵营目标验证：每 tick 检查当前目标是否仍然可攻击
        // 防止关系变更后（如命令设置友好）已锁定的目标继续被攻击
        if (!self.level().isClientSide && self instanceof Mob mob && mob.getTarget() != null) {
            if (!FactionUtil.canAttack(mob, mob.getTarget())) {
                mob.setTarget(null);
            }
        }
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void onHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        // ECA 无敌保护
        if (EcaAPI.isInvulnerable(self)) {
            cir.setReturnValue(false);
            return;
        }
        // 阵营保护：攻击者与目标同阵营或友好关系时取消伤害
        Entity attacker = source.getEntity();
        if (attacker != null && !FactionUtil.canAttack(attacker, self)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "hurt", at = @At("RETURN"))
    private void onHurtUpdateHealBan(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        Float lockedValue = HealthLockManager.getLock(self);
        Float healBanValue = HealthLockManager.getHealBan(self);
        if (healBanValue != null && lockedValue == null && cir.getReturnValue()) {
            HealthLockManager.setHealBan(self, EntityUtil.getHealth(self));
        }
    }

    @Inject(method = "actuallyHurt", at = @At("HEAD"), cancellable = true)
    private void onActuallyHurt(DamageSource source, float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        // ECA 无敌保护
        if (EcaAPI.isInvulnerable(self)) {
            ci.cancel();
            return;
        }
        // 阵营保护：兜底拦截绕过 hurt 直接调用 actuallyHurt 的路径
        Entity attacker = source.getEntity();
        if (attacker != null && !FactionUtil.canAttack(attacker, self)) {
            ci.cancel();
        }
    }

    @Inject(method = "heal", at = @At("HEAD"), cancellable = true)
    private void onHeal(float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        Float lockedValue = HealthLockManager.getLock(self);
        Float healBanValue = HealthLockManager.getHealBan(self);
        if (healBanValue != null) {
            ci.cancel();
        }
    }

    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void onSetHealth(float health, CallbackInfo ci) {
        if (ECA_IN_SET_HEALTH.get()) return;
        ECA_IN_SET_HEALTH.set(true);
        try {
            LivingEntity self = (LivingEntity) (Object) this;
            Float healBanValue = HealthLockManager.getHealBan(self);
            if (healBanValue != null && health > EntityUtil.getHealth(self)) {
                ci.cancel();
            }
        } finally {
            ECA_IN_SET_HEALTH.set(false);
        }
    }

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void onDie(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        Float lockedValue = HealthLockManager.getLock(self);
        if (EcaAPI.isInvulnerable(self) || lockedValue != null) {
            ci.cancel();
        }
    }

    @Inject(method = "tickDeath", at = @At("HEAD"), cancellable = true)
    private void onTickDeath(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        Float lockedValue = HealthLockManager.getLock(self);
        boolean resurrectionTracked = EntityUtil.RESURRECTION_TRACKED != null
            && self.getEntityData().get(EntityUtil.RESURRECTION_TRACKED);
        if (EcaAPI.isInvulnerable(self) || lockedValue != null || resurrectionTracked) {
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




