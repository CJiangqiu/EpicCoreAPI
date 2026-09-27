package net.eca.mixin;

import net.minecraft.world.entity.vehicle.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Boat.class)
public interface BoatTeleportAccessor {

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
