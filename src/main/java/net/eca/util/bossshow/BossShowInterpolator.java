package net.eca.util.bossshow;

import net.eca.util.bossshow.BossShowDefinition.Marker;
import net.eca.util.bossshow.BossShowDefinition.Sample;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Pure interpolation helpers for BossShow playback over the new sample model.
 * Each sample is one game tick; sub-tick smoothness comes from linear interpolation
 * between consecutive samples using partialTick.
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
     * Marker curves affect the interpolation speed within each marker segment.
     *
     * @param samples       sample list (each entry = 1 game tick of anchor-local pose)
     * @param markers       marker list (sparse keyframes with curve data); may be null
     * @param cinematic     whether the playback should request cinematic bars
     * @param tickCursor    fractional tick since playback start; clamped to [0, samples.size()-1]
     * @param anchorX       anchor world X
     * @param anchorY       anchor world Y
     * @param anchorZ       anchor world Z
     * @param anchorYawDeg  anchor world yaw
     * @param out           output pose (mutated in place)
     */
    public static void computePose(List<Sample> samples, List<Marker> markers, boolean cinematic,
                                   double tickCursor,
                                   double anchorX, double anchorY, double anchorZ, float anchorYawDeg,
                                   BossShowPose out) {
        if (samples.isEmpty()) {
            out.x = anchorX; out.y = anchorY; out.z = anchorZ;
            out.yaw = anchorYawDeg; out.pitch = 0;
            out.cinematic = cinematic;
            return;
        }

        int last = samples.size() - 1;
        if (tickCursor <= 0) tickCursor = 0;
        if (tickCursor >= last) tickCursor = last;

        //曲线重映射：找到当前 tick 所在的 marker 区间，用该区间的 curve 重映射整段进度
        tickCursor = applyCurveRemap(tickCursor, markers, last);

        int i0 = (int) Math.floor(tickCursor);
        int i1 = Math.min(last, i0 + 1);
        double t = tickCursor - i0;

        Sample a = samples.get(i0);
        Sample b = samples.get(i1);

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

    //根据 marker 区间的 curve 重映射 tickCursor
    //区间 = [当前 marker tick, 下一个 marker tick)，用该 marker 的 curve 缓动区间内的进度
    private static double applyCurveRemap(double tickCursor, List<Marker> markers, int lastSample) {
        if (markers == null || markers.isEmpty()) return tickCursor;

        //找当前 tick 所在的 marker 区间
        Marker current = null;
        int segEnd = lastSample;
        for (int i = 0; i < markers.size(); i++) {
            Marker m = markers.get(i);
            if (m.tickOffset() <= tickCursor) {
                current = m;
                //下一个 marker 的 tick 作为区间终点
                segEnd = (i + 1 < markers.size()) ? markers.get(i + 1).tickOffset() : lastSample;
            } else {
                break;
            }
        }

        if (current == null || current.curve() == Curve.NONE) return tickCursor;

        int segStart = current.tickOffset();
        if (segEnd <= segStart) return tickCursor;

        double segLen = segEnd - segStart;
        double linearProgress = (tickCursor - segStart) / segLen;
        double curved = current.curve().apply(linearProgress);
        return segStart + curved * segLen;
    }
}
