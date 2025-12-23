package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntitySection.class)
public class EntitySectionMixin {

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void onEntitySectionRemove(EntityAccess entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Entity realEntity) {
            // Allow dimension change operations even for invulnerable entities
            if (EcaAPI.isInvulnerable(realEntity) && !EntityUtil.isChangingDimension(realEntity)) {
                cir.setReturnValue(false);
            }
        }
    }
}
