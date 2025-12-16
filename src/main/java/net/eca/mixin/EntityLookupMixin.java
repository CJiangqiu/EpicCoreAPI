package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityLookup.class)
public class EntityLookupMixin {

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void onEntityLookupRemove(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof Entity realEntity) {
            if (EcaAPI.isInvulnerable(realEntity)) {
                ci.cancel();
            }
        }
    }
}
