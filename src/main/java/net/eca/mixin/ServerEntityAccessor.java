package net.eca.mixin;

import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.server.level.ServerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerEntity.class)
public interface ServerEntityAccessor {

    @Accessor("positionCodec")
    VecDeltaCodec eca$getPositionCodec();

    @Accessor("yRotp")
    void eca$setYRotp(int yRotp);

    @Accessor("xRotp")
    void eca$setXRotp(int xRotp);

    @Accessor("teleportDelay")
    void eca$setTeleportDelay(int teleportDelay);

    @Accessor("wasRiding")
    void eca$setWasRiding(boolean wasRiding);

    @Accessor("wasOnGround")
    void eca$setWasOnGround(boolean wasOnGround);
}
