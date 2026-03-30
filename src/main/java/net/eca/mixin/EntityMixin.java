package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EcaLogger;
import net.eca.util.EntityLocationManager;
import net.eca.util.EntityUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

        // 维度切换：必须已被 changeDimension 标记才放行
        if (reason == Entity.RemovalReason.CHANGED_DIMENSION && EntityUtil.isChangingDimension(entity)) {
            return;
        }

        // 检查无敌保护
        if (EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity)) {
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
        if (EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity)) {
            ci.cancel();
        }
    }

    // 防止受保护实体因 removalReason 残留导致 tick 跳过、@e 选择器失效、客户端追踪丢失
    @Inject(method = "isRemoved", at = @At("HEAD"), cancellable = true)
    private void eca$preventRemovedState(CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;
        if (entity.removalReason != null
                && !EntityUtil.isChangingDimension(entity)
                && EcaAPI.isInvulnerable(entity)) {
            entity.removalReason = null;
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "setLevelCallback", at = @At("HEAD"), cancellable = true)
    private void onSetLevelCallback(EntityInLevelCallback callback, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (!EcaAPI.isInvulnerable(entity) || EntityUtil.isChangingDimension(entity)) {
            return;
        }

        if (callback == EntityInLevelCallback.NULL) {
            ci.cancel();
        }

    }

    @Inject(method = "move", at = @At("RETURN"))
    private void afterMove(MoverType type, Vec3 movement, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;

        // 如果位置被锁定且不在维度切换中，直接拉回锁定位置
        if (EntityLocationManager.isLocationLocked(entity) && !EntityUtil.isChangingDimension(entity)) {
            Vec3 lockedPos = EntityLocationManager.getLockedPosition(entity);
            if (lockedPos != null) {
                Vec3 currentPos = entity.position();
                double distance = currentPos.distanceTo(lockedPos);

                // 如果位置偏离，拉回去
                if (distance > 0.001) {
                    entity.setPos(lockedPos.x, lockedPos.y, lockedPos.z);
                }
            }
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

    @Inject(method = "changeDimension*", at = @At("HEAD"))
    private void beforeChangeDimension(ServerLevel destination, CallbackInfoReturnable<Entity> cir) {
        EntityUtil.markDimensionChanging((Entity) (Object) this);
    }

    @Inject(method = "changeDimension*", at = @At("RETURN"))
    private void afterChangeDimension(ServerLevel destination, CallbackInfoReturnable<Entity> cir) {
        Entity oldEntity = (Entity) (Object) this;
        // End→Overworld 的终末之诗流程中，changeDimension 返回 this 但实体仍为 CHANGED_DIMENSION 状态
        // 此时不应 unmark，否则无敌保护会重新激活，阻止后续 respawn 的清理操作
        // 延迟到 addPlayer TAIL 中 unmark
        if (oldEntity.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION) {
            return;
        }
        EntityUtil.unmarkDimensionChanging(oldEntity);
    }

    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void onShouldBeSaved(CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;

        if (!EcaAPI.isInvulnerable(entity)) {
            return;
        }
        cir.setReturnValue(true);
    }

    @Inject(method = "saveAsPassenger", at = @At("HEAD"), cancellable = true)
    private void onSaveAsPassenger(CompoundTag tag, CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;

        if (!EcaAPI.isInvulnerable(entity)) {
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

}
