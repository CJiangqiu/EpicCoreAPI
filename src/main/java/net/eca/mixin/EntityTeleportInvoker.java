package net.eca.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityTeleportInvoker {

    @Invoker("positionRider")
    void eca$positionRider(Entity passenger, Entity.MoveFunction moveFunction);
}
