package net.eca.mixin;

import net.eca.util.bossshow.BossShowClientState;
import net.eca.util.bossshow.BossShowEditorState;
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
        //播放优先；播放未激活时才考虑编辑器预览（二者互斥）
        BossShowPose pose;
        if (BossShowClientState.isActive()) {
            pose = BossShowClientState.computePoseForRender(partialTicks);
        } else if (BossShowEditorState.isPreviewActive()) {
            pose = BossShowEditorState.computePreviewPose();
        } else {
            return;
        }
        CameraAccessor self = (CameraAccessor) this;
        self.eca$setPosition(pose.x, pose.y, pose.z);
        self.eca$setRotation(pose.yaw, pose.pitch);
    }
}
