package net.eca.mixin;

import net.eca.config.EcaConfiguration;

import net.eca.util.entity_extension.ForceLoadingManager;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void eca$forceLoadedShouldRender(Entity entity, Frustum frustum, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        ForceLoadingManager.setCurrentRenderingEntity(entity);

        if (ForceLoadingManager.isForceLoadedType(entity.getType())) {
            double maxDist = EcaConfiguration.getForceLoadingMaxRenderDistanceSafely();
            double distSqr = entity.distanceToSqr(camX, camY, camZ);
            boolean inRange = distSqr <= maxDist * maxDist;
            cir.setReturnValue(inRange);
        }
    }
}
