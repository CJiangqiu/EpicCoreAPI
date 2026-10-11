package net.eca.util.bossshow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.eca.util.bossshow.BossShowDefinition.Frame;

import java.util.ArrayList;
import java.util.List;

//生成参数独立于内容关键帧，避免字幕或事件改变镜头轨迹。
public record BossShowCameraPath(boolean twoPoint, Shape shape, Facing facing, Curve easing,
                                 int duration, double centerX, double centerZ, double lookHeight,
                                 List<Node> nodes) {
    public static final int MAX_DURATION = 72000;
    public static final int MAX_NODES = 128;

    public enum Shape { LINE, FREE, ORBIT, SPIRAL }
    public enum Facing { INTERPOLATE, KEEP, TARGET }

    //自由路径使用 x/y/z；环绕使用累计角度/高度/半径，进度不受内容轨道影响。
    public record Node(double progress, double x, double y, double z, double yaw, double pitch) {
        double value(int axis) {
            return switch (axis) {
                case 0 -> x;
                case 1 -> y;
                case 2 -> z;
                case 3 -> yaw;
                default -> pitch;
            };
        }
    }

    public BossShowCameraPath {
        nodes = List.copyOf(nodes);
        if (shape == null || facing == null || easing == null || easing == Curve.STEP
            || duration < 2 || duration > MAX_DURATION || nodes.size() < 2 || nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException("Invalid camera path");
        }
        finite(centerX, centerZ, lookHeight);
        double previous = -1;
        for (Node node : nodes) {
            finite(node.progress, node.x, node.y, node.z, node.yaw, node.pitch);
            if (node.progress <= previous || node.progress < 0 || node.progress > 1
                || Math.abs(node.pitch) > 90 || Math.abs(node.yaw) > 1000000
                || ((shape == Shape.ORBIT || shape == Shape.SPIRAL) && node.z <= 0)) {
                throw new IllegalArgumentException("Invalid path control");
            }
            previous = node.progress;
        }
        if (nodes.get(0).progress != 0 || nodes.get(nodes.size() - 1).progress != 1) {
            throw new IllegalArgumentException("Missing endpoints");
        }
    }

    private static void finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value) || Math.abs(value) > 30000000) {
                throw new IllegalArgumentException("Invalid path number");
            }
        }
    }

    public boolean orbital() {
        return shape == Shape.ORBIT || shape == Shape.SPIRAL;
    }

    public BossShowCameraPath withDuration(int ticks) {
        return new BossShowCameraPath(twoPoint, shape, facing, easing, ticks, centerX, centerZ, lookHeight, nodes);
    }

    public Frame sample(double progress) {
        double t = Math.max(0, Math.min(1, progress));
        int segment = 0;
        while (segment < nodes.size() - 2 && t > nodes.get(segment + 1).progress) segment++;
        double x = interpolate(segment, t, 0);
        double y = interpolate(segment, t, 1);
        double z = interpolate(segment, t, 2);
        if (orbital()) {
            double angle = Math.toRadians(x);
            x = centerX + Math.cos(angle) * z;
            z = centerZ + Math.sin(angle) * z;
        }
        double yaw = facing == Facing.KEEP ? nodes.get(0).yaw : interpolate(segment, t, 3);
        double pitch = facing == Facing.KEEP ? nodes.get(0).pitch : interpolate(segment, t, 4);
        if (facing == Facing.TARGET) {
            double dx = centerX - x;
            double dz = centerZ - z;
            double horizontal = Math.hypot(dx, dz);
            if (horizontal > 1.0e-8) yaw = Math.toDegrees(Math.atan2(-dx, dz));
            if (horizontal > 1.0e-8 || Math.abs(lookHeight - y) > 1.0e-8) {
                pitch = -Math.toDegrees(Math.atan2(lookHeight - y, horizontal));
            }
        }
        finite(x, y, z, yaw, pitch);
        return new Frame(x, y, z, (float) yaw, (float) pitch, null);
    }

    public Node controlAt(double progress) {
        return new Node(progress, interpolateAt(progress, 0), interpolateAt(progress, 1),
            interpolateAt(progress, 2), interpolateAt(progress, 3), interpolateAt(progress, 4));
    }

    //单调 Hermite 切线保证控制点处速度连续，并防止半径插值越过零。
    private double interpolate(int segment, double progress, int axis) {
        Node a = nodes.get(segment);
        Node b = nodes.get(segment + 1);
        double span = b.progress - a.progress;
        double t = (progress - a.progress) / span;
        if (shape == Shape.LINE) return a.value(axis) + (b.value(axis) - a.value(axis)) * t;
        double t2 = t * t;
        double t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * a.value(axis)
            + (t3 - 2 * t2 + t) * span * tangent(segment, axis)
            + (-2 * t3 + 3 * t2) * b.value(axis)
            + (t3 - t2) * span * tangent(segment + 1, axis);
    }

    private double tangent(int index, int axis) {
        if (index == 0) return slope(0, axis);
        if (index == nodes.size() - 1) return slope(index - 1, axis);
        double left = slope(index - 1, axis);
        double right = slope(index, axis);
        if (left * right <= 0) return 0;
        double h0 = nodes.get(index).progress - nodes.get(index - 1).progress;
        double h1 = nodes.get(index + 1).progress - nodes.get(index).progress;
        double w0 = 2 * h1 + h0;
        double w1 = h1 + 2 * h0;
        return (w0 + w1) / (w0 / left + w1 / right);
    }

    private double slope(int index, int axis) {
        Node a = nodes.get(index);
        Node b = nodes.get(index + 1);
        return (b.value(axis) - a.value(axis)) / (b.progress - a.progress);
    }

    public List<Frame> generate() {
        ArrayList<Frame> frames = new ArrayList<>(duration);
        Frame previous = null;
        for (int i = 0; i < duration; i++) {
            Frame frame = sample(easing.apply(i / (double) (duration - 1)));
            //注视角跨过 ±180° 时展开角度，便于后续继续编辑。
            if (previous != null && facing == Facing.TARGET) {
                frame = new Frame(frame.dx(), frame.dy(), frame.dz(),
                    BossShowInterpolator.lerpYaw(previous.yaw(), frame.yaw(), 1), frame.pitch(), null);
            }
            if (previous != null && orbital()) {
                double a = interpolateAt(easing.apply((i - 1.0) / (duration - 1)), 0);
                double b = interpolateAt(easing.apply(i / (double) (duration - 1)), 0);
                //一帧跨过半圈会在逐 tick 回放中折返，需要更长时长。
                if (Math.abs(b - a) >= 180) throw new IllegalArgumentException("Orbit needs more ticks");
            }
            if (previous != null && Math.abs(frame.yaw() - previous.yaw()) >= 180) {
                throw new IllegalArgumentException("Camera rotation needs more ticks");
            }
            frames.add(frame);
            previous = frame;
        }
        return frames;
    }

    private double interpolateAt(double t, int axis) {
        int segment = 0;
        while (segment < nodes.size() - 2 && t > nodes.get(segment + 1).progress) segment++;
        return interpolate(segment, t, axis);
    }

    //按实际曲线累计长度换算平均速度，闭合路径不能使用首尾距离。
    public double length() {
        double length = 0;
        for (int segment = 0; segment < nodes.size() - 1; segment++) {
            Node a = nodes.get(segment);
            Node b = nodes.get(segment + 1);
            int steps = orbital() ? Math.max(64, (int) Math.ceil(Math.abs(b.x - a.x) * 2)) : 256;
            if (steps > MAX_DURATION) throw new IllegalArgumentException("Orbit too long");
            Frame previous = sample(a.progress);
            for (int i = 1; i <= steps; i++) {
                Frame next = sample(a.progress + (b.progress - a.progress) * i / steps);
                length += Math.sqrt(Math.pow(next.dx() - previous.dx(), 2)
                    + Math.pow(next.dy() - previous.dy(), 2) + Math.pow(next.dz() - previous.dz(), 2));
                previous = next;
            }
        }
        return length;
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("two_point", twoPoint);
        object.addProperty("shape", shape.name());
        object.addProperty("facing", facing.name());
        object.addProperty("easing", easing.key());
        object.addProperty("duration", duration);
        object.addProperty("center_x", centerX);
        object.addProperty("center_z", centerZ);
        object.addProperty("look_height", lookHeight);
        JsonArray controls = new JsonArray();
        for (Node node : nodes) {
            JsonArray values = new JsonArray();
            values.add(node.progress);
            values.add(node.x);
            values.add(node.y);
            values.add(node.z);
            values.add(node.yaw);
            values.add(node.pitch);
            controls.add(values);
        }
        object.add("nodes", controls);
        return object;
    }

    public static BossShowCameraPath fromJson(JsonObject object) {
        List<Node> nodes = new ArrayList<>();
        JsonArray controls = object.getAsJsonArray("nodes");
        if (controls.size() > MAX_NODES) throw new IllegalArgumentException("Too many path controls");
        for (JsonElement element : controls) {
            JsonArray values = element.getAsJsonArray();
            nodes.add(new Node(values.get(0).getAsDouble(), values.get(1).getAsDouble(),
                values.get(2).getAsDouble(), values.get(3).getAsDouble(),
                values.get(4).getAsDouble(), values.get(5).getAsDouble()));
        }
        return new BossShowCameraPath(object.get("two_point").getAsBoolean(),
            Shape.valueOf(object.get("shape").getAsString()), Facing.valueOf(object.get("facing").getAsString()),
            Curve.fromKey(object.get("easing").getAsString()), object.get("duration").getAsInt(),
            object.get("center_x").getAsDouble(), object.get("center_z").getAsDouble(),
            object.get("look_height").getAsDouble(), nodes);
    }
}
