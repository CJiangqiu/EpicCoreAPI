package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClassInstanceMultiMap.class)
public class ClassInstanceMultiMapMixin {

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void onClassInstanceMultiMapRemove(Object object, CallbackInfoReturnable<Boolean> cir) {
        if (object instanceof Entity entity) {
            if (EcaAPI.isInvulnerable(entity)) {
                cir.setReturnValue(false);
            }
        }
    }
}
