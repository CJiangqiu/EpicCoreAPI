package net.eca.blender.client.runtime;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.eca.blender.client.runtime.BlendFile.require;

final class BlendAnimation {
    record Binding(int node, String property, int component, Curve curve) { }
    record Clip(float start, float end, List<Binding> bindings) { }
    record Key(float time, float value, float leftTime, float leftValue, float rightTime, float rightValue, int interpolation) { }

    record Curve(List<Key> keys) {
        float sample(float frame) {
            if (frame <= keys.get(0).time) return keys.get(0).value;
            if (frame >= keys.get(keys.size() - 1).time) return keys.get(keys.size() - 1).value;
            int lo = 0, hi = keys.size() - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (keys.get(mid).time <= frame) lo = mid; else hi = mid;
            }
            Key a = keys.get(lo), b = keys.get(hi);
            if (a.interpolation == 0) return a.value;
            float factor = (frame - a.time) / (b.time - a.time);
            if (a.interpolation == 1) return a.value + factor * (b.value - a.value);
            float span = b.time - a.time;
            float ar = Math.abs(a.rightTime - a.time), bl = Math.abs(b.time - b.leftTime);
            float af = ar > span ? span / ar : 1, bf = bl > span ? span / bl : 1;
            float x1 = a.time + (a.rightTime - a.time) * af, y1 = a.value + (a.rightValue - a.value) * af;
            float x2 = b.time + (b.leftTime - b.time) * bf, y2 = b.value + (b.leftValue - b.value) * bf;
            float low = 0, high = 1;
            for (int i = 0; i < 24; i++) {
                factor = (low + high) * 0.5f;
                if (bezier(a.time, x1, x2, b.time, factor) < frame) low = factor; else high = factor;
            }
            return bezier(a.value, y1, y2, b.value, (low + high) * 0.5f);
        }

        private static float bezier(float a, float b, float c, float d, float t) {
            float s = 1 - t;
            return s * s * s * a + 3 * s * s * t * b + 3 * s * t * t * c + t * t * t * d;
        }
    }

    static final class Transform {
        final Matrix4f prefix;
        final float[] location;
        final float[] scale;
        final float[] euler;
        final float[] quaternion;
        final float[] axisAngle;
        final int mode;

        Transform(Matrix4f prefix, float[] location, float[] scale, float[] euler,
                  float[] quaternion, float[] axisAngle, int mode) {
            this.prefix = prefix; this.location = location; this.scale = scale; this.euler = euler;
            this.quaternion = quaternion; this.axisAngle = axisAngle; this.mode = mode;
        }

        static Transform read(BlendFile.View data, Matrix4f prefix, boolean object) throws IOException {
            if (data == null) return identity(prefix);
            require(data.list("constraints").isEmpty(), data.type() + ": constraints are not supported");
            int mode = data.integer("rotmode");
            require(mode >= -1 && mode <= 6, "Unsupported rotation mode " + mode);
            if (object) {
                require(zero(data.floats("dloc", 3)) && zero(data.floats("drot", 3)), "Delta location/rotation is unsupported");
                float[] ds = data.floats("dscale", 3), dq = data.floats("dquat", 4);
                require(ds[0] == 1 && ds[1] == 1 && ds[2] == 1 && dq[0] == 1 && dq[1] == 0 && dq[2] == 0 && dq[3] == 0
                    && data.scalar("drotAngle") == 0, "Delta scale/rotation is unsupported");
            }
            float[] axis = data.floats("rotAxis", 3);
            return new Transform(prefix, data.floats("loc", 3), data.floats("size", 3),
                data.floats(object ? "rot" : "eul", 3), data.floats("quat", 4),
                new float[]{data.scalar("rotAngle"), axis[0], axis[1], axis[2]}, mode);
        }

        static Transform identity(Matrix4f prefix) {
            return new Transform(prefix, new float[3], new float[]{1, 1, 1}, new float[3], new float[]{1, 0, 0, 0}, new float[]{0, 0, 1, 0}, 0);
        }

        private static boolean zero(float[] values) { for (float value : values) if (value != 0) return false; return true; }

        Transform copy() { return new Transform(prefix, location.clone(), scale.clone(), euler.clone(), quaternion.clone(), axisAngle.clone(), mode); }

        void set(String property, int component, float value) {
            float[] values = switch (property) {
                case "location" -> location; case "scale" -> scale; case "rotation_euler" -> euler;
                case "rotation_quaternion" -> quaternion; default -> axisAngle;
            };
            values[component] = value;
        }

        Matrix4f matrix() throws IOException {
            Quaternionf rotation;
            if (mode == 0) {
                rotation = new Quaternionf(quaternion[1], quaternion[2], quaternion[3], quaternion[0]);
                if (rotation.lengthSquared() == 0) rotation.identity(); else rotation.normalize();
            } else if (mode == -1) {
                Vector3f axis = new Vector3f(axisAngle[1], axisAngle[2], axisAngle[3]);
                rotation = axis.lengthSquared() == 0 ? new Quaternionf() : new Quaternionf().fromAxisAngleRad(axis.normalize(), axisAngle[0]);
            } else {
                int[][] order = {{}, {2, 1, 0}, {1, 2, 0}, {2, 0, 1}, {0, 2, 1}, {1, 0, 2}, {0, 1, 2}};
                rotation = new Quaternionf();
                for (int axis : order[mode]) {
                    if (axis == 0) rotation.rotateX(euler[0]); else if (axis == 1) rotation.rotateY(euler[1]); else rotation.rotateZ(euler[2]);
                }
            }
            Matrix4f result = new Matrix4f(prefix).translate(location[0], location[1], location[2]).rotate(rotation).scale(scale[0], scale[1], scale[2]);
            require(result.isFinite(), "Non-finite animated transform");
            return result;
        }
    }

    private final BlendFile file;
    private final Map<String, List<Binding>> bindings = new LinkedHashMap<>();

    BlendAnimation(BlendFile file) { this.file = file; }

    void addObject(BlendFile.View object, int node, Map<String, Integer> bones) throws IOException {
        BlendFile.View adt = object.ref("adt");
        if (adt != null) {
            require(adt.list("drivers").isEmpty() && adt.list("nla_tracks").isEmpty(), object.idName() + ": drivers/NLA require additional animation support");
        }
        BlendFile.View active = adt == null ? null : adt.ref("action");
        for (BlendFile.View action : file.all("bAction")) {
            int slot = Integer.MIN_VALUE;
            if (active != null && active.address() == action.address()) slot = adt.integer("slot_handle");
            else {
                for (BlendFile.View candidate : file.pointers(action.ptr("slot_array"), action.integer("slot_array_num"))) {
                    if (candidate.text("name").equals("OB" + object.idName())) slot = candidate.integer("handle");
                }
            }
            if (slot == Integer.MIN_VALUE) continue;
            List<Binding> parsed = action(action, slot, node, bones);
            bindings.computeIfAbsent(action.idName(), ignored -> new ArrayList<>()).addAll(parsed);
            if (active != null && active.address() == action.address()) {
                bindings.computeIfAbsent("__scene__", ignored -> new ArrayList<>()).addAll(parsed);
            }
        }
    }

    private List<Binding> action(BlendFile.View action, int slot, int node, Map<String, Integer> bones) throws IOException {
        List<BlendFile.View> layers = file.pointers(action.ptr("layer_array"), action.integer("layer_array_num"));
        require(layers.size() <= 1, action.idName() + ": multiple action layers are unsupported");
        if (layers.isEmpty()) return List.of();
        BlendFile.View layer = layers.get(0);
        if ((layer.integer("layer_flags") & 1) == 0) return List.of();
        require(layer.scalar("influence") == 1 && layer.integer("layer_mix_mode") == 0,
            action.idName() + ": action layer blending is unsupported");
        List<BlendFile.View> strips = file.pointers(layer.ptr("strip_array"), layer.integer("strip_array_num"));
        require(strips.size() == 1 && strips.get(0).integer("strip_type") == 0
            && strips.get(0).scalar("frame_offset") == 0, action.idName() + ": only a single unshifted keyframe strip is supported");
        List<BlendFile.View> data = file.pointers(action.ptr("strip_keyframe_data_array"), action.integer("strip_keyframe_data_array_num"));
        int index = strips.get(0).integer("data_index");
        require(index >= 0 && index < data.size(), "Invalid action strip data");
        BlendFile.View strip = data.get(index);
        List<Binding> result = new ArrayList<>();
        for (BlendFile.View bag : file.pointers(strip.ptr("channelbag_array"), strip.integer("channelbag_array_num"))) {
            if (bag.integer("slot_handle") != slot) continue;
            for (BlendFile.View curve : file.pointers(bag.ptr("fcurve_array"), bag.integer("fcurve_array_num"))) {
                if ((curve.integer("flag") & 16) != 0) continue;
                String path = curve.text("rna_path");
                int target = node;
                if (path.startsWith("pose.bones[\"")) {
                    int end = escapedEnd(path, 12);
                    String name = path.substring(12, end).replace("\\\"", "\"").replace("\\\\", "\\");
                    require(bones.containsKey(name), action.idName() + ": missing animated bone " + name);
                    target = bones.get(name);
                    require(path.startsWith("\"].", end), "Invalid bone animation path");
                    path = path.substring(end + 3);
                }
                int length = switch (path) {
                    case "location", "scale", "rotation_euler" -> 3;
                    case "rotation_quaternion", "rotation_axis_angle" -> 4;
                    default -> throw new IOException(action.idName() + ": unsupported animated property " + path);
                };
                int component = curve.integer("array_index");
                require(component >= 0 && component < length, "Invalid animation component");
                result.add(new Binding(target, path, component, curve(curve)));
            }
        }
        return result;
    }

    private static int escapedEnd(String path, int start) throws IOException {
        boolean escape = false;
        for (int i = start; i < path.length(); i++) {
            char c = path.charAt(i);
            if (!escape && c == '"') return i;
            if (!escape && c == '\\') escape = true; else escape = false;
        }
        throw new IOException("Unterminated bone path");
    }

    private Curve curve(BlendFile.View curve) throws IOException {
        require(curve.ref("driver") == null && curve.list("modifiers").isEmpty(), "FCurve modifiers/drivers are unsupported");
        require(curve.integer("extend") == 0, "Only constant FCurve extrapolation is supported");
        int count = BlendFile.count(curve.integer("totvert"));
        require(count > 0, "Empty animation curve");
        List<Key> keys = new ArrayList<>();
        if (curve.ptr("bezt") != 0) {
            for (BlendFile.View key : file.array(curve.ptr("bezt"), count)) {
                float[] v = key.floats("vec", 9);
                int interpolation = key.integer("ipo");
                require(interpolation >= 0 && interpolation <= 2, "Unsupported animation interpolation " + interpolation);
                keys.add(new Key(v[3], v[4], v[0], v[1], v[6], v[7], interpolation));
            }
        } else {
            for (BlendFile.View key : file.array(curve.ptr("fpt"), count)) {
                float[] v = key.floats("vec", 2);
                keys.add(new Key(v[0], v[1], v[0], v[1], v[0], v[1], 1));
            }
        }
        for (int i = 1; i < keys.size(); i++) require(keys.get(i).time > keys.get(i - 1).time, "Unsorted or duplicate animation keys");
        return new Curve(List.copyOf(keys));
    }

    Map<String, Clip> clips(float sceneStart, float sceneEnd) {
        Map<String, Clip> result = new LinkedHashMap<>();
        bindings.forEach((name, tracks) -> {
            float start = Float.POSITIVE_INFINITY, end = Float.NEGATIVE_INFINITY;
            for (Binding binding : tracks) {
                start = Math.min(start, binding.curve.keys.get(0).time);
                end = Math.max(end, binding.curve.keys.get(binding.curve.keys.size() - 1).time);
            }
            if (name.equals("__scene__") || tracks.isEmpty()) { start = sceneStart; end = sceneEnd; }
            result.put(name, new Clip(start, end, List.copyOf(tracks)));
        });
        result.putIfAbsent("__scene__", new Clip(sceneStart, sceneEnd, List.of()));
        return Map.copyOf(result);
    }
}
