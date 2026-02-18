package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.spawn_ban.SpawnBanHook;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClassInstanceMultiMap.class)
public class ClassInstanceMultiMapMixin {

    // 禁生成：阻止被禁实体添加到ClassInstanceMultiMap
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void eca$onAdd(Object object, CallbackInfoReturnable<Boolean> cir) {
        if (object instanceof Entity entity && entity.level() instanceof ServerLevel level) {
            if (SpawnBanHook.shouldBlockSpawn(level, entity)) {
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void eca$onRemove(Object object, CallbackInfoReturnable<Boolean> cir) {
        if (object instanceof Entity entity) {
            if (EcaAPI.isInvulnerable(entity)) {
                cir.setReturnValue(false);
            }
        }
    }
}
