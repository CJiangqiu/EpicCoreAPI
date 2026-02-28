package net.eca.mixin;

import net.eca.api.EcaAPI;

import net.eca.util.EntityUtil;
import net.eca.util.entity_extension.ForceLoadingManager;
import net.eca.util.spawn_ban.SpawnBanHook;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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
        if (entity instanceof Entity realEntity && realEntity.level() instanceof ServerLevel level) {
            if (SpawnBanHook.shouldBlockSpawn(level, realEntity)) {
                cir.setReturnValue(false);
            }
        }
    }

    // 禁生成：阻止被禁实体添加到PersistentEntitySectionManager
    @Inject(method = "addNewEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onAddNewEntity(EntityAccess entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Entity realEntity && realEntity.level() instanceof ServerLevel level) {
            if (SpawnBanHook.shouldBlockSpawn(level, realEntity)) {
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "unloadEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onUnloadEntity(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof Entity realEntity) {
            if (EcaAPI.isInvulnerable(realEntity) && !EntityUtil.isChangingDimension(realEntity)) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "stopTicking", at = @At("HEAD"), cancellable = true)
    private void eca$onStopTicking(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof Entity realEntity) {
            // 实体已被移除（死亡/kill等），允许清理
            if (realEntity.isRemoved()) {
                return;
            }
            if (EcaAPI.isInvulnerable(realEntity) && !EntityUtil.isChangingDimension(realEntity)) {
                ci.cancel();
                return;
            }
            // 强加载实体：区块状态降级时保持ticking
            if (ForceLoadingManager.isForceLoadedType(realEntity.getType())) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "stopTracking", at = @At("HEAD"), cancellable = true)
    private void eca$onStopTracking(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof Entity realEntity) {
            // 实体已被移除（死亡/kill等），允许清理
            if (realEntity.isRemoved()) {
                return;
            }
            if (EcaAPI.isInvulnerable(realEntity) && !EntityUtil.isChangingDimension(realEntity)) {
                ci.cancel();
                return;
            }
            // 强加载实体：区块状态降级时保持tracking，防止从ChunkMap移除
            if (ForceLoadingManager.isForceLoadedType(realEntity.getType())) {
                ci.cancel();
            }
        }
    }

    @Mixin(PersistentEntitySectionManager.Callback.class)
    public static class CallbackMixin {
        @Final
        @Shadow
        private EntityAccess entity;

        @Inject(method = "onRemove", at = @At("HEAD"), cancellable = true)
        private void eca$onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
            if (this.entity instanceof Entity realEntity) {
                if (EcaAPI.isInvulnerable(realEntity) && reason != Entity.RemovalReason.CHANGED_DIMENSION) {
                    ci.cancel();
                }
            }
        }
    }
}
