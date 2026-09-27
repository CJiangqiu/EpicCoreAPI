package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityLocationManager;
import net.eca.util.EntityUtil;
import net.eca.util.ResurrectionManager;
import net.eca.util.faction.FactionUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.ITeleporter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityMixin {

    @Inject(method = "kill", at = @At("HEAD"), cancellable = true)
    private void onKill(CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (entity instanceof LivingEntity && EcaAPI.isInvulnerable(entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "discard", at = @At("HEAD"), cancellable = true)
    private void onDiscard(CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (entity instanceof LivingEntity && EcaAPI.isInvulnerable(entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;

        // 维度切换：必须已被 changeDimension 标记才放行
        if (reason == Entity.RemovalReason.CHANGED_DIMENSION && EntityUtil.isChangingDimension(entity)) {
            return;
        }

        // 检查无敌保护
        if (entity instanceof LivingEntity && EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "setRemoved", at = @At("HEAD"), cancellable = true)
    private void onSetRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;

        // 维度切换：必须已被 changeDimension 标记才放行
        if (reason == Entity.RemovalReason.CHANGED_DIMENSION && EntityUtil.isChangingDimension(entity)) {
            return;
        }

        // 检查无敌保护
        if (entity instanceof LivingEntity && EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity)) {
            ci.cancel();
        }
    }

    // 防止受保护实体因 removalReason 残留导致 tick 跳过、@e 选择器失效、客户端追踪丢失
    @Inject(method = "isRemoved", at = @At("HEAD"), cancellable = true)
    private void eca$preventRemovedState(CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;
        if (entity.removalReason != null
                && entity instanceof LivingEntity
                && !EntityUtil.isChangingDimension(entity)
                && EcaAPI.isInvulnerable(entity)) {
            entity.removalReason = null;
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "setLevelCallback", at = @At("HEAD"), cancellable = true)
    private void onSetLevelCallback(EntityInLevelCallback callback, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (!(entity instanceof LivingEntity) || !EcaAPI.isInvulnerable(entity) || EntityUtil.isChangingDimension(entity)) {
            return;
        }

        if (callback == EntityInLevelCallback.NULL) {
            ci.cancel();
        }

    }

    @Inject(method = "setPosRaw(DDD)V", at = @At("HEAD"), cancellable = true)
    private void onSetPosRaw(double x, double y, double z, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;

        if (EntityLocationManager.isLocationLocked(entity)) {
            if (EntityUtil.isChangingDimension(entity)) {
                return;
            }

            Vec3 lockedPos = EntityLocationManager.getLockedPosition(entity);
            if (lockedPos != null) {
                double dx = x - lockedPos.x;
                double dy = y - lockedPos.y;
                double dz = z - lockedPos.z;
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

                if (distance > 0.001) {
                    ci.cancel();
                }
            }
        }
    }

    @Inject(method = "setPosRaw(DDD)V", at = @At("RETURN"))
    private void recordResurrectionPosition(double x, double y, double z, CallbackInfo ci) {
        ResurrectionManager.recordPosition((Entity) (Object) this);
    }

    @Inject(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/world/entity/Entity;", at = @At("HEAD"))
    private void beforeChangeDimension(ServerLevel destination, CallbackInfoReturnable<Entity> cir) {
        EntityUtil.beginDimensionChange((Entity) (Object) this);
    }

    @Inject(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/world/entity/Entity;", at = @At("RETURN"))
    private void afterChangeDimension(ServerLevel destination, CallbackInfoReturnable<Entity> cir) {
        EntityUtil.finishDimensionChange((Entity) (Object) this);
    }

    @Inject(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraftforge/common/util/ITeleporter;)Lnet/minecraft/world/entity/Entity;",
            at = @At("HEAD"), remap = false)
    private void eca$beforeCustomDimensionChange(ServerLevel destination, ITeleporter teleporter, CallbackInfoReturnable<Entity> cir) {
        EntityUtil.beginDimensionChange((Entity) (Object) this);
    }

    @Inject(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraftforge/common/util/ITeleporter;)Lnet/minecraft/world/entity/Entity;",
            at = @At("RETURN"), remap = false)
    private void eca$afterCustomDimensionChange(ServerLevel destination, ITeleporter teleporter, CallbackInfoReturnable<Entity> cir) {
        EntityUtil.finishDimensionChange((Entity) (Object) this);
    }

    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void onShouldBeSaved(CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;

        if (!(entity instanceof LivingEntity) || !EcaAPI.isInvulnerable(entity)) {
            return;
        }
        cir.setReturnValue(true);
    }

    @Inject(method = "saveAsPassenger", at = @At("HEAD"), cancellable = true)
    private void onSaveAsPassenger(CompoundTag tag, CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;

        if (!(entity instanceof LivingEntity) || !EcaAPI.isInvulnerable(entity)) {
            return;
        }

        String encodeId = entity.getEncodeId();
        if (encodeId == null) {
            cir.setReturnValue(false);
            return;
        }

        tag.putString(Entity.ID_TAG, encodeId);
        entity.saveWithoutId(tag);
        cir.setReturnValue(true);
    }

    // 阵营系统：同阵营或友好阵营视为盟友，使原版 AI（僵尸猪人、狼、目标选择器等）自动尊重 ECA 阵营
    @Inject(method = "isAlliedTo", at = @At("HEAD"), cancellable = true)
    private void eca$checkFactionAllied(Entity other, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (FactionUtil.isFriendly(self, other)) {
            cir.setReturnValue(true);
        }
    }

}
