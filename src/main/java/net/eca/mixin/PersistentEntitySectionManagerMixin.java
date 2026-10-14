package net.eca.mixin;

import net.eca.api.EcaAPI;

import net.eca.util.EntityRemovalQuarantine;
import net.eca.util.EntityUtil;
import net.eca.util.spawn_ban.SpawnBanHook;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PersistentEntitySectionManager.class)
public class PersistentEntitySectionManagerMixin {

    // 禁生成：阻止被禁实体添加到PersistentEntitySectionManager
    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onAddEntity(EntityAccess entity, boolean flag, CallbackInfoReturnable<Boolean> cir) {
        if (SpawnBanHook.shouldBlockSpawn(entity)) {
            cir.setReturnValue(false);
        }
    }

    // 禁生成：阻止被禁实体添加到PersistentEntitySectionManager
    @Inject(method = "addNewEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onAddNewEntity(EntityAccess entity, CallbackInfoReturnable<Boolean> cir) {
        if (SpawnBanHook.shouldBlockSpawn(entity)) {
            cir.setReturnValue(false);
        }
    }

    // 无事件入口不会经过 addEntity，必须独立阻止
    @Inject(method = "addEntityWithoutEvent", at = @At("HEAD"), cancellable = true, remap = false)
    private void eca$onAddEntityWithoutEvent(EntityAccess entity, boolean flag,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (SpawnBanHook.shouldBlockSpawn(entity)) {
            cir.setReturnValue(false);
        }
    }

    // 公开包装入口也单独拦截，避免调用链被改写后绕过内部实现
    @Inject(method = "addNewEntityWithoutEvent", at = @At("HEAD"), cancellable = true, remap = false)
    private void eca$onAddNewEntityWithoutEvent(EntityAccess entity,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (SpawnBanHook.shouldBlockSpawn(entity)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "unloadEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onUnloadEntity(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof LivingEntity realEntity) {
            if (EcaAPI.isInvulnerable(realEntity) && !EntityUtil.isChangingDimension(realEntity)) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "stopTicking", at = @At("HEAD"), cancellable = true)
    private void eca$onStopTicking(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof LivingEntity realEntity) {
            if (EcaAPI.isInvulnerable(realEntity) && !EntityUtil.isChangingDimension(realEntity)) {
                ci.cancel();
                return;
            }
        }
    }

    @Inject(method = "stopTracking", at = @At("HEAD"), cancellable = true)
    private void eca$onStopTracking(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof LivingEntity realEntity) {
            if (EcaAPI.isInvulnerable(realEntity) && !EntityUtil.isChangingDimension(realEntity)) {
                ci.cancel();
                return;
            }
        }
    }

    @Mixin(PersistentEntitySectionManager.Callback.class)
    public static class CallbackMixin {
        @Final
        @Shadow
        private EntityAccess entity;

        // 清除中的移动不能借旧回调重新建立区段关联。
        @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
        private void eca$onMove(CallbackInfo ci) {
            if (this.entity instanceof Entity realEntity && EntityRemovalQuarantine.shouldBlockAdd(realEntity)) {
                ci.cancel();
            }
        }

        @Inject(method = "onRemove", at = @At("HEAD"), cancellable = true)
        private void eca$onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
            // 底层清理由隔离流程负责，恢复的旧回调不能再次访问失效区段。
            if (this.entity instanceof Entity realEntity && EntityRemovalQuarantine.shouldBlockAdd(realEntity)) {
                ci.cancel();
                return;
            }
            if (this.entity instanceof LivingEntity realEntity) {
                if (EcaAPI.isInvulnerable(realEntity) && !(reason == Entity.RemovalReason.CHANGED_DIMENSION && EntityUtil.isChangingDimension(realEntity))) {
                    ci.cancel();
                }
            }
        }
    }
}
