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
            // 解开属性上限的同时保留各属性自身的下限，避免 MAX_HEALTH 等被削减到 0 或负数
            double min = ((RangedAttribute) (Object) this).getMinValue();
            if (Double.isNaN(value)) {
                cir.setReturnValue(min);
                return;
            }
            if (Double.isInfinite(value)) {
                cir.setReturnValue(value > 0 ? Double.MAX_VALUE : min);
                return;
            }
            cir.setReturnValue(Math.max(value, min));
        }
    }
}
