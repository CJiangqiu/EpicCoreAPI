package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityTickList.class)
public class EntityTickListMixin {

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void onEntityTickListRemove(Entity entity, CallbackInfo ci) {
        if (EcaAPI.isInvulnerable(entity)) {
            ci.cancel();
        }
    }
}
