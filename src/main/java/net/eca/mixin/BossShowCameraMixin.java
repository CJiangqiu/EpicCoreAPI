package net.eca.mixin;

import net.eca.util.bossshow.BossShowClientState;
import net.eca.util.bossshow.BossShowPose;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//当 BossShow 活跃时，在 Camera.setup TAIL 覆写相机位置和朝向
@Mixin(Camera.class)
public class BossShowCameraMixin {

    @Inject(method = "setup", at = @At("TAIL"))
    private void eca$overrideCameraForBossShow(BlockGetter level, Entity focusedEntity, boolean thirdPerson,
                                                boolean thirdPersonReverse, float partialTicks, CallbackInfo ci) {
        if (!BossShowClientState.isActive()) return;

        BossShowPose pose = BossShowClientState.computePoseForRender(partialTicks);
        CameraAccessor self = (CameraAccessor) this;
        self.eca$setPosition(pose.x, pose.y, pose.z);
        self.eca$setRotation(pose.yaw, pose.pitch);
    }
}
