package net.eca.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

//暴露 Camera 的 protected 方法给 ECA 演出系统
@Mixin(Camera.class)
public interface CameraAccessor {

    @Invoker("setPosition")
    void eca$setPosition(double x, double y, double z);

    @Invoker("setRotation")
    void eca$setRotation(float yaw, float pitch);
}
