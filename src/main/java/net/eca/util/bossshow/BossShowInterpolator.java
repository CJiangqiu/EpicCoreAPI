package net.eca.util.bossshow;

import net.eca.util.bossshow.BossShowDefinition.Frame;
import net.eca.util.bossshow.BossShowDefinition.Keyframe;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Pure interpolation helpers for BossShow playback over the frame model.
 * Each frame is one game tick; sub-tick smoothness comes from linear interpolation
 * between consecutive frames using partialTick.
 */
public final class BossShowInterpolator {

    private BossShowInterpolator() {}

    //把 anchor-local 坐标转换到世界坐标
    public static Vec3 anchorToWorld(double dx, double dy, double dz,
                                     double anchorX, double anchorY, double anchorZ,
                                     float anchorYawDeg) {
        double rad = Math.toRadians(anchorYawDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double wx = anchorX + (dx * cos + dz * sin);
        double wy = anchorY + dy;
        double wz = anchorZ + (-dx * sin + dz * cos);
        return new Vec3(wx, wy, wz);
    }

    //角度环绕差
    private static float wrapDegrees(float deg) {
        deg = deg % 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    public static float lerpYaw(float a, float b, double t) {
        float diff = wrapDegrees(b - a);
        return a + (float) (diff * t);
    }

    public static float lerpPitch(float a, float b, double t) {
        return a + (float) ((b - a) * t);
    }

    /**
     * Compute world-space pose at the given fractional tick cursor.
     * Keyframe curves affect the interpolation speed within each keyframe segment.
     *
     * @param frames        frame list (each entry = 1 game tick of anchor-local pose)
     * @param cinematic     whether the playback should request cinematic bars
     * @param tickCursor    fractional tick since playback start; clamped to [0, frames.size()-1]
     * @param anchorX       anchor world X
     * @param anchorY       anchor world Y
     * @param anchorZ       anchor world Z
     * @param anchorYawDeg  anchor world yaw
     * @param out           output pose (mutated in place)
     */
    public static void computePose(List<Frame> frames, boolean cinematic,
                                   double tickCursor,
                                   double anchorX, double anchorY, double anchorZ, float anchorYawDeg,
                                   BossShowPose out) {
        if (frames.isEmpty()) {
            out.x = anchorX; out.y = anchorY; out.z = anchorZ;
            out.yaw = anchorYawDeg; out.pitch = 0;
            out.cinematic = cinematic;
            return;
        }

        int last = frames.size() - 1;
        if (tickCursor <= 0) tickCursor = 0;
        if (tickCursor >= last) tickCursor = last;

        //曲线重映射：找当前 tick 所在的关键帧区间，用该关键帧的 curve 重映射整段进度
        tickCursor = applyCurveRemap(tickCursor, frames, last);

        int i0 = (int) Math.floor(tickCursor);
        int i1 = Math.min(last, i0 + 1);
        double t = tickCursor - i0;

        Frame a = frames.get(i0);
        Frame b = frames.get(i1);

        double dx = a.dx() + (b.dx() - a.dx()) * t;
        double dy = a.dy() + (b.dy() - a.dy()) * t;
        double dz = a.dz() + (b.dz() - a.dz()) * t;

        Vec3 world = anchorToWorld(dx, dy, dz, anchorX, anchorY, anchorZ, anchorYawDeg);
        out.x = world.x;
        out.y = world.y;
        out.z = world.z;

        float localYaw = lerpYaw(a.yaw(), b.yaw(), t);
        out.yaw = localYaw + anchorYawDeg;
        out.pitch = lerpPitch(a.pitch(), b.pitch(), t);
        out.cinematic = cinematic;
    }

    /* 根据关键帧区间的 curve 重映射 tickCursor。
     * 区间 = [当前关键帧帧下标, 下一个关键帧帧下标)，用当前关键帧的 curve 缓动区间内的进度。 */
    private static double applyCurveRemap(double tickCursor, List<Frame> frames, int lastFrame) {
        int cursorInt = (int) tickCursor;

        //找 tickCursor 之前最近的关键帧
        int segStart = -1;
        Curve segCurve = Curve.NONE;
        for (int i = 0; i <= cursorInt && i < frames.size(); i++) {
            Keyframe kf = frames.get(i).keyframe();
            if (kf != null) {
                segStart = i;
                segCurve = kf.curve();
            }
        }

        if (segStart < 0 || segCurve == Curve.NONE) return tickCursor;

        //找下一个关键帧确定区间终点
        int segEnd = lastFrame;
        for (int i = segStart + 1; i < frames.size(); i++) {
            if (frames.get(i).keyframe() != null) {
                segEnd = i;
                break;
            }
        }

        double segLen = segEnd - segStart;
        if (segLen <= 0) return tickCursor;
        double linearProgress = (tickCursor - segStart) / segLen;
        double curved = segCurve.apply(linearProgress);
        return segStart + curved * segLen;
    }
}
