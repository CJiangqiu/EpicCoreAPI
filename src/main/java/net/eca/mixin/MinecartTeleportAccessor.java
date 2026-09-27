package net.eca.mixin;

import net.minecraft.world.entity.vehicle.AbstractMinecart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractMinecart.class)
public interface MinecartTeleportAccessor {

    @Accessor("lSteps")
    void eca$setLerpSteps(int steps);

    @Accessor("lx")
    void eca$setLerpX(double x);

    @Accessor("ly")
    void eca$setLerpY(double y);

    @Accessor("lz")
    void eca$setLerpZ(double z);

    @Accessor("lyr")
    void eca$setLerpYRot(double yRot);

    @Accessor("lxr")
    void eca$setLerpXRot(double xRot);
}
