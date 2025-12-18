package net.eca.mixin;

import net.eca.config.EcaConfiguration;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RangedAttribute.class)
public class RangedAttributeMixin {

    @Inject(method = "sanitizeValue", at = @At("HEAD"), cancellable = true)
    private void unlockLimits(double value, CallbackInfoReturnable<Double> cir) {
        if (EcaConfiguration.getAttributeUnlockLimitsSafely()) {
            // Handle NaN
            if (Double.isNaN(value)) {
                cir.setReturnValue(0.0);
                return;
            }
            // Handle Infinity
            if (Double.isInfinite(value)) {
                cir.setReturnValue(value > 0 ? Double.MAX_VALUE : -Double.MAX_VALUE);
                return;
            }
            // Direct return without clamping
            cir.setReturnValue(value);
        }
    }
}
