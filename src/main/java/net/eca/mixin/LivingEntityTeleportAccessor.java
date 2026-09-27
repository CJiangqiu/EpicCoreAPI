package net.eca.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityTeleportAccessor {

    @Accessor("lerpSteps")
    void eca$setLerpSteps(int steps);

    @Accessor("lerpX")
    void eca$setLerpX(double x);

    @Accessor("lerpY")
    void eca$setLerpY(double y);

    @Accessor("lerpZ")
    void eca$setLerpZ(double z);

    @Accessor("lerpYRot")
    void eca$setLerpYRot(double yRot);

    @Accessor("lerpXRot")
    void eca$setLerpXRot(double xRot);
}
