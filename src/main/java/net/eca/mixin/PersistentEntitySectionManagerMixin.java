package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PersistentEntitySectionManager.class)
public class PersistentEntitySectionManagerMixin {

    @Inject(method = "unloadEntity", at = @At("HEAD"), cancellable = true)
    private void onPersistentEntitySectionManagerUnloadEntity(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof Entity realEntity) {
            // Allow dimension change operations even for invulnerable entities
            if (EcaAPI.isInvulnerable(realEntity) && !EntityUtil.isChangingDimension(realEntity)) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "stopTicking", at = @At("HEAD"), cancellable = true)
    private void onPersistentEntitySectionManagerStopTicking(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof Entity realEntity) {
            // Allow dimension change operations even for invulnerable entities
            if (EcaAPI.isInvulnerable(realEntity) && !EntityUtil.isChangingDimension(realEntity)) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "stopTracking", at = @At("HEAD"), cancellable = true)
    private void onPersistentEntitySectionManagerStopTracking(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof Entity realEntity) {
            // Allow dimension change operations even for invulnerable entities
            if (EcaAPI.isInvulnerable(realEntity) && !EntityUtil.isChangingDimension(realEntity)) {
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
        private void onCallbackOnRemove(Entity.RemovalReason reason, CallbackInfo ci) {
            if (this.entity instanceof Entity realEntity) {
                // Allow dimension change operations even for invulnerable entities
                if (EcaAPI.isInvulnerable(realEntity) && reason != Entity.RemovalReason.CHANGED_DIMENSION) {
                    ci.cancel();
                }
            }
        }
    }
}
