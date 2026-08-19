package net.eca.mixin;

import net.eca.client.render.FrustumGuard;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/* offsetToFullyIncludeCameraCube 的循环没有迭代上限，退化输入会让渲染线程永久自旋。
   两处注入都是 require = 0：本闸门是纯防御，接管了渲染路径的模组若移除该方法，
   宁可闸门失效也不能让整个客户端崩在 ECA 手里。 */
@Mixin(Frustum.class)
public abstract class FrustumMixin {

    @Shadow
    private double camX;

    @Shadow
    private double camY;

    @Shadow
    private double camZ;

    @Shadow
    private Vector4f viewVector;

    @Shadow
    @Final
    private Matrix4f matrix;

    @Unique
    private int eca$offsetIterations;

    @Unique
    private double eca$offsetOriginX;

    @Unique
    private double eca$offsetOriginY;

    @Unique
    private double eca$offsetOriginZ;

    @Inject(method = "offsetToFullyIncludeCameraCube", at = @At("HEAD"), cancellable = true, require = 0)
    private void eca$guardDegenerateOffset(int step, CallbackInfoReturnable<Frustum> cir) {
        eca$offsetIterations = 0;
        // 循环会就地推移相机，触顶时得还原成进入前的位置，先存一份
        eca$offsetOriginX = camX;
        eca$offsetOriginY = camY;
        eca$offsetOriginZ = camZ;
        String reason = FrustumGuard.degenerateReason(camX, camY, camZ, viewVector);
        if (reason == null) {
            return;
        }
        FrustumGuard.report(reason, camX, camY, camZ, viewVector, matrix);
        // 放弃这次偏移：视锥略微不准，好过整个客户端锁死
        cir.setReturnValue((Frustum) (Object) this);
    }

    /* 数值全部有限但视锥本身退化时（FOV 极小、远平面过近）立方体同样永远进不了视锥。
       这类情况在进入循环前看不出来，只能给判据加次数上限作为最后兜底。 */
    @Redirect(
            method = "offsetToFullyIncludeCameraCube",
            at = @At(value = "INVOKE", target = "Lorg/joml/FrustumIntersection;intersectAab(FFFFFF)I"),
            require = 0
    )
    private int eca$capOffsetIterations(FrustumIntersection intersection, float minX, float minY, float minZ,
                                        float maxX, float maxY, float maxZ) {
        int result = intersection.intersectAab(minX, minY, minZ, maxX, maxY, maxZ);
        if (result == FrustumGuard.FRUSTUM_INSIDE) {
            return result;
        }
        if (++eca$offsetIterations < FrustumGuard.MAX_OFFSET_ITERATIONS) {
            return result;
        }
        /* 走到这里循环已经把相机沿视线推出上千格，而 setupRender 正是拿这份副本去剔除区块。
           不还原就等于用一个错位相机做整帧剔除，可见区块会被清空，症状比死循环更难定位。 */
        camX = eca$offsetOriginX;
        camY = eca$offsetOriginY;
        camZ = eca$offsetOriginZ;
        FrustumGuard.report("iteration-cap", camX, camY, camZ, viewVector, matrix);
        return FrustumGuard.FRUSTUM_INSIDE;
    }
}
