package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.spawn.SpawnBanHook;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TransientEntitySectionManager.class)
public class TransientEntitySectionManagerMixin {

    // 禁生成：阻止被禁实体添加到TransientEntitySectionManager
    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onAddEntity(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof Entity realEntity && realEntity.level() instanceof ServerLevel level) {
            if (SpawnBanHook.shouldBlockSpawn(level, realEntity)) {
                ci.cancel();
            }
        }
    }

    @Mixin(TransientEntitySectionManager.Callback.class)
    public static class CallbackMixin {
        @Final
        @Shadow
        private EntityAccess entity;

        @Inject(method = "onRemove", at = @At("HEAD"), cancellable = true)
        private void eca$onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
            if (this.entity instanceof Entity realEntity) {
                // Allow dimension change operations even for invulnerable entities
                if (EcaAPI.isInvulnerable(realEntity) && reason != Entity.RemovalReason.CHANGED_DIMENSION) {
                    ci.cancel();
                }
            }
        }
    }
}
