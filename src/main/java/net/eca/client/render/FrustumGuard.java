package net.eca.client.render;

import com.mojang.blaze3d.platform.Window;
import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 视锥偏移循环的退化输入闸门。
 * 原版 Frustum.offsetToFullyIncludeCameraCube 是一个无上限循环：靠 viewVector 逐步推移相机，
 * 直到相机所在立方体完全落入视锥。相机坐标或 viewVector 一旦非有限，循环变量不再变化，
 * 判据永远得不到新输入，渲染线程会 100% 占核永久自旋，表现为窗口冻结但进程存活。
 * 本类负责在进入循环前识别这类输入，并打印足以定位污染源的现场值。
 */
public final class FrustumGuard {

    /* 步长 4 格、立方体边长 8 格，正常视野下十余次即收敛；
       FOV 拉到极小时需要的次数也在两位数量级，1024 留了三个数量级余量。 */
    public static final int MAX_OFFSET_ITERATIONS = 1024;

    // JOML 的“完全在视锥内”返回值，原版判据直接写的字面量 -2
    public static final int FRUSTUM_INSIDE = -2;

    // 退化输入会每帧重复触发，按时间节流避免刷屏
    private static final long REPORT_INTERVAL_NANOS = 5_000_000_000L;

    private static final AtomicLong LAST_REPORT_NANOS = new AtomicLong(Long.MIN_VALUE);

    /**
     * 判定视锥偏移循环的输入是否退化。
     * @return 退化原因标识，输入正常时返回 null
     */
    public static String degenerateReason(double camX, double camY, double camZ, Vector4f viewVector) {
        if (!Double.isFinite(camX) || !Double.isFinite(camY) || !Double.isFinite(camZ)) {
            return "non-finite-camera";
        }
        if (viewVector == null) {
            return "null-view-vector";
        }
        if (!Float.isFinite(viewVector.x()) || !Float.isFinite(viewVector.y()) || !Float.isFinite(viewVector.z())) {
            return "non-finite-view-vector";
        }
        // 三个分量全零时相机不会移动，判据永远拿不到新输入
        if (viewVector.x() == 0.0f && viewVector.y() == 0.0f && viewVector.z() == 0.0f) {
            return "zero-view-vector";
        }
        return null;
    }

    /**
     * 打印退化现场。字段取的是原版投影/模型视图矩阵与相机位置的全部上游输入，
     * 谁被写成非有限值可以直接从这几行读出来。
     */
    public static void report(String reason, double camX, double camY, double camZ,
                              Vector4f viewVector, Matrix4f matrix) {
        if (!shouldReport()) {
            return;
        }

        EcaLogger.info("[FrustumGuard] degenerate offset input reason={} frustumCam=({}, {}, {})",
                reason, camX, camY, camZ);
        if (viewVector == null) {
            EcaLogger.info("[FrustumGuard] viewVector=null");
        } else {
            EcaLogger.info("[FrustumGuard] viewVector=({}, {}, {}, {})",
                    viewVector.x(), viewVector.y(), viewVector.z(), viewVector.w());
        }

        reportDepthPlanes(matrix);

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }

        reportDepthFar(minecraft);

        Window window = minecraft.getWindow();
        EcaLogger.info("[FrustumGuard] window={}x{} fov={} partialTick={}",
                window == null ? -1 : window.getWidth(),
                window == null ? -1 : window.getHeight(),
                minecraft.options == null ? "n/a" : minecraft.options.fov().get(),
                minecraft.getFrameTime());

        reportCamera(minecraft);
        reportPlayer(minecraft);
    }

    /* 远平面在矩阵里是 row3 - row2，法线长度等于 |1 + m22|。远平面推得越远这个长度越小，
       小到 float 舍成 0 时 JOML 的平面归一化会除以 0，远平面整条变成 NaN，
       此后每一次可见性判断都恒假。normalLen 打成 0.0 就是这个塌陷。 */
    private static void reportDepthPlanes(Matrix4f matrix) {
        if (matrix == null) {
            EcaLogger.info("[FrustumGuard] matrix=null");
            return;
        }
        float farX = matrix.m03() - matrix.m02();
        float farY = matrix.m13() - matrix.m12();
        float farZ = matrix.m23() - matrix.m22();
        float farW = matrix.m33() - matrix.m32();
        double normalLen = Math.sqrt((double) farX * farX + (double) farY * farY + (double) farZ * farZ);
        EcaLogger.info("[FrustumGuard] farPlane=({}, {}, {}, {}) normalLen={}{}",
                farX, farY, farZ, farW, normalLen,
                normalLen == 0.0 ? " COLLAPSED - every visibility test degenerates to NaN" : "");
    }

    // 直接打出远平面距离与驱动它的配置项，省掉从 viewVector 反推的一轮往返
    private static void reportDepthFar(Minecraft minecraft) {
        GameRenderer gameRenderer = minecraft.gameRenderer;
        EcaLogger.info("[FrustumGuard] depthFar={} forceLoadingMaxRenderDistance={}",
                gameRenderer == null ? "n/a" : gameRenderer.getDepthFar(),
                EcaConfiguration.getForceLoadingMaxRenderDistanceSafely());
    }

    private static void reportCamera(Minecraft minecraft) {
        Camera camera = minecraft.gameRenderer == null ? null : minecraft.gameRenderer.getMainCamera();
        if (camera == null) {
            return;
        }
        Vec3 pos = camera.getPosition();
        Entity cameraEntity = camera.getEntity();
        EcaLogger.info("[FrustumGuard] camera pos=({}, {}, {}) xRot={} yRot={} entity={}",
                pos == null ? "null" : pos.x,
                pos == null ? "null" : pos.y,
                pos == null ? "null" : pos.z,
                camera.getXRot(), camera.getYRot(),
                cameraEntity == null ? "null" : cameraEntity.getClass().getName());
    }

    /* 玩家侧字段是 bobView/bobHurt 与相机插值的全部输入源：
       位置与上一 tick 位置决定相机坐标，walkDist/bob 决定视角摇晃，
       hurt 三项决定受伤倾斜，任一为非有限值都会把矩阵整体带成 NaN。 */
    private static void reportPlayer(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        Vec3 motion = player.getDeltaMovement();
        EcaLogger.info("[FrustumGuard] player pos=({}, {}, {}) old=({}, {}, {}) rot=({}, {})",
                player.getX(), player.getY(), player.getZ(),
                player.xo, player.yo, player.zo,
                player.getXRot(), player.getYRot());
        EcaLogger.info("[FrustumGuard] player motion=({}, {}, {}) walkDist={} bob={} oBob={}",
                motion == null ? "null" : motion.x,
                motion == null ? "null" : motion.y,
                motion == null ? "null" : motion.z,
                player.walkDist, player.bob, player.oBob);
        EcaLogger.info("[FrustumGuard] player hurtTime={} hurtDuration={} hurtDir={} health={} maxHealth={}",
                player.hurtTime, player.hurtDuration, player.getHurtDir(),
                player.getHealth(), player.getMaxHealth());
    }

    private static boolean shouldReport() {
        long now = System.nanoTime();
        long last = LAST_REPORT_NANOS.get();
        if (last != Long.MIN_VALUE && now - last < REPORT_INTERVAL_NANOS) {
            return false;
        }
        return LAST_REPORT_NANOS.compareAndSet(last, now);
    }

    private FrustumGuard() {}
}
