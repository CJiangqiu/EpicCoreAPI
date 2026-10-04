package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityRemovalQuarantine;
import net.eca.util.EntityUtil;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClassInstanceMultiMap.class)
public class ClassInstanceMultiMapMixin {

    // 区段迁移也会加入集合，只阻止已进入清除流程的实例。
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void eca$onAdd(Object object, CallbackInfoReturnable<Boolean> cir) {
        if (object instanceof Entity entity && EntityRemovalQuarantine.shouldBlockAdd(entity)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void eca$onRemove(Object object, CallbackInfoReturnable<Boolean> cir) {
        if (object instanceof LivingEntity entity) {
            if (EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity)) {
                cir.setReturnValue(false);
            }
        }
    }
}
