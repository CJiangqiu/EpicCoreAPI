package net.eca.blender.client.runtime;

import net.eca.blender.client.runtime.BlendNodeGraph.Expr;
import net.eca.blender.client.runtime.BlendNodeGraph.Input;
import net.eca.blender.client.runtime.BlendNodeGraph.Value;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.eca.blender.client.runtime.BlendFile.require;

final class BlendGeometryNodes implements BlendGeometryModifier {
    private static final Set<String> SUPPORTED = Set.of("Constant", "Parameter", "GeometryInput",
        "ShaderNodeValue", "ShaderNodeRGB", "ShaderNodeMath", "ShaderNodeVectorMath",
        "ShaderNodeCombineXYZ", "ShaderNodeSeparateXYZ", "FunctionNodeInputVector",
        "FunctionNodeInputFloat", "FunctionNodeInputInt", "FunctionNodeInputBool",
        "FunctionNodeEulerToRotation", "FunctionNodeRotationToEuler",
        "GeometryNodeInputPosition", "GeometryNodeInputNormal", "GeometryNodeInputIndex", "GeometryNodeInputSceneTime",
        "GeometryNodeTransform", "GeometryNodeSetPosition", "GeometryNodeJoinGeometry",
        "GeometryNodeInstanceOnPoints", "GeometryNodeRealizeInstances", "GeometryNodeCurvePrimitiveCircle",
        "GeometryNodeCurveToMesh", "GeometryNodeCurveToPoints", "GeometryNodeMeshIcoSphere", "GeometryNodeSetMaterial");
    private final Expr root;
    private final Map<Expr, Boolean> contextual = new IdentityHashMap<>();
    private final Map<Expr, BlendGeometry> constantGeometry = new IdentityHashMap<>();
    final boolean dynamic;

    BlendGeometryNodes(Expr root) throws IOException {
        this.root = root;
        Set<Expr> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        dynamic = validate(root, visited);
        contextual(root);
    }

    private boolean contextual(Expr expr) {
        Boolean known = contextual.get(expr);
        if (known != null) return known;
        boolean depends = expr.operation().equals("GeometryInput") || expr.operation().equals("Parameter")
            || expr.operation().equals("GeometryNodeInputSceneTime");
        for (Input input : expr.inputs()) for (Expr source : input.sources()) depends |= contextual(source);
        contextual.put(expr, depends);
        return depends;
    }

    private boolean validate(Expr expr, Set<Expr> visited) throws IOException {
        if (!visited.add(expr)) return false;
        require(SUPPORTED.contains(expr.operation()), expr.label() + ": unsupported geometry node " + expr.operation());
        if (expr.operation().equals("ShaderNodeMath")) math(expr.option(), 1, 1, 1);
        if (expr.operation().equals("ShaderNodeVectorMath")) vectorMath(expr.option(), Value.scalar(1), Value.scalar(1), 1);
        if (expr.operation().equals("GeometryNodeCurvePrimitiveCircle")) {
            require(expr.storage().integer("mode") == 1 && expr.output().equals("Curve"), expr.label() + ": only Radius circle mode is supported");
        }
        if (expr.operation().equals("GeometryNodeCurveToPoints")) {
            require(expr.storage().integer("mode") == 0 && expr.output().equals("Points"), expr.label() + ": only Count mode and Points output are supported");
        }
        if (expr.operation().equals("GeometryNodeMeshIcoSphere")) {
            require(expr.output().equals("Mesh"), expr.label() + ": anonymous Icosphere UV output is unsupported");
        }
        if (expr.operation().equals("Constant") || expr.operation().equals("Parameter")) {
            require(!expr.outputType().contains("Object") && !expr.outputType().contains("Collection")
                && !expr.outputType().contains("Matrix"), expr.label() + ": unsupported socket type " + expr.outputType());
        }
        boolean changed = expr.operation().equals("Parameter") || expr.operation().equals("GeometryNodeInputSceneTime");
        for (Input input : expr.inputs()) for (Expr source : input.sources()) changed |= validate(source, visited);
        return changed;
    }

    @Override
    public boolean dynamic() { return dynamic; }

    @Override
    public BlendGeometry evaluate(BlendGeometry input, float seconds, float fps, float startFrame,
                           Map<String, Float> parameters) throws IOException {
        return geometry(root, new Context(input, seconds, fps, startFrame, parameters, null, -1, 0, null, new IdentityHashMap<>()),
            new IdentityHashMap<>());
    }

    private record Context(BlendGeometry input, float seconds, float fps, float startFrame,
                           Map<String, Float> parameters, BlendGeometry.Mesh mesh, int point, int base,
                           Vector3f[] normals, Map<Expr, Value> fields) {
        Context at(BlendGeometry.Mesh mesh, int point, int base, Vector3f[] normals) {
            return new Context(input, seconds, fps, startFrame, parameters, mesh, point, base, normals, new IdentityHashMap<>());
        }
    }

    private BlendGeometry geometry(Expr e, Context c, Map<Expr, BlendGeometry> memo) throws IOException {
        BlendGeometry cached = memo.get(e);
        if (cached == null) cached = constantGeometry.get(e);
        if (cached != null) return cached;
        BlendGeometry result;
        switch (e.operation()) {
            case "GeometryInput" -> result = c.input;
            case "Constant" -> {
                require(e.outputType().equals("NodeSocketGeometry"), e.label() + ": expected geometry socket");
                result = BlendGeometry.EMPTY;
            }
            case "GeometryNodeJoinGeometry" -> {
                List<BlendGeometry.Part> parts = new ArrayList<>();
                List<BlendCurveGeometry.Curve> curves = new ArrayList<>();
                for (Expr source : e.socket("Geometry").sources()) {
                    BlendGeometry joined = geometry(source, c, memo);
                    parts.addAll(joined.parts); curves.addAll(joined.curves);
                }
                result = new BlendGeometry(parts, curves);
            }
            case "GeometryNodeCurvePrimitiveCircle" -> result = BlendCurveGeometry.circle(integer(field(e.input("Resolution"), c), e), field(e.input("Radius"), c).x());
            case "GeometryNodeMeshIcoSphere" -> result = BlendCurveGeometry.icoSphere(field(e.input("Radius"), c).x(), integer(field(e.input("Subdivisions"), c), e));
            case "GeometryNodeCurveToPoints" -> result = BlendCurveGeometry.points(geometry(e.input("Curve"), c, memo), integer(field(e.input("Count"), c), e));
            case "GeometryNodeCurveToMesh" -> {
                field(e.input("Fill Caps"), c);
                result = BlendCurveGeometry.sweep(geometry(e.input("Curve"), c, memo), geometry(e.input("Profile Curve"), c, memo),
                    e.has("Scale") ? field(e.input("Scale"), c).x() : 1);
            }
            case "GeometryNodeSetMaterial" -> {
                BlendGeometry source = geometry(e.input("Geometry"), c, memo);
                if (field(e.input("Selection"), c).x() == 0) { result = source; break; }
                Expr material = e.input("Material");
                require(material.outputType().equals("NodeSocketMaterial") && material.operation().equals("Constant"), e.label() + ": material must be a fixed datablock");
                int index = integer(material.value(), e);
                List<BlendGeometry.Part> parts = new ArrayList<>();
                Map<BlendGeometry.Mesh, BlendGeometry.Mesh> meshes = new IdentityHashMap<>();
                for (BlendGeometry.Part part : source.parts) {
                    BlendGeometry.Mesh mesh = meshes.computeIfAbsent(part.mesh(), original -> {
                        int[] assigned = new int[original.materials.length]; Arrays.fill(assigned, index);
                        return new BlendGeometry.Mesh(original.positions, original.corners, original.offsets, original.uv, assigned,
                            original.smooth, original.joints, original.weights);
                    });
                    parts.add(new BlendGeometry.Part(mesh, part.transform(), part.instance()));
                }
                List<BlendCurveGeometry.Curve> curves = new ArrayList<>();
                for (BlendCurveGeometry.Curve curve : source.curves) curves.add(new BlendCurveGeometry.Curve(curve.positions(), curve.planeNormal(), index));
                result = new BlendGeometry(parts, curves);
            }
            case "GeometryNodeTransform" -> {
                require(!e.has("Mode") || field(e.input("Mode"), c).x() == 0, e.label() + ": matrix transform mode is unsupported");
                Matrix4f transform = matrix(field(e.input("Translation"), c), field(e.input("Rotation"), c), field(e.input("Scale"), c));
                result = geometry(e.input("Geometry"), c, memo).transform(transform);
            }
            case "GeometryNodeSetPosition" -> {
                require(geometry(e.input("Geometry"), c, memo).curves.isEmpty(), e.label() + ": Set Position on curves is unsupported");
                List<BlendGeometry.Part> parts = new ArrayList<>();
                int base = 0;
                for (BlendGeometry.Part part : geometry(e.input("Geometry"), c, memo).parts) {
                    if (part.instance()) { parts.add(part); continue; }
                    BlendGeometry.Mesh mesh = part.mesh();
                    Vector3f[] normals = mesh.pointNormals();
                    float[] positions = mesh.positions.clone();
                    for (int i = 0; i < positions.length / 3; i++) {
                        Context point = c.at(mesh, i, base, normals);
                        if (field(e.input("Selection"), point).x() == 0) continue;
                        Expr position = e.input("Position");
                        Value p = !e.socket("Position").linked()
                            ? value(mesh.position(i)) : field(position, point);
                        Value offset = field(e.input("Offset"), point);
                        positions[i * 3] = finite(p.x() + offset.x(), e);
                        positions[i * 3 + 1] = finite(p.y() + offset.y(), e);
                        positions[i * 3 + 2] = finite(p.z() + offset.z(), e);
                    }
                    parts.add(new BlendGeometry.Part(mesh.withPositions(positions), part.transform(), false));
                    base = Math.addExact(base, positions.length / 3);
                }
                result = new BlendGeometry(parts);
            }
            case "GeometryNodeInstanceOnPoints" -> {
                BlendGeometry source = geometry(e.input("Instance"), c, memo);
                require(source.curves.isEmpty() && geometry(e.input("Points"), c, memo).curves.isEmpty(), e.label() + ": convert curves before instancing");
                List<BlendGeometry.Part> parts = new ArrayList<>();
                int base = 0;
                for (BlendGeometry.Part points : geometry(e.input("Points"), c, memo).parts) {
                    if (points.instance()) { parts.add(points); continue; }
                    Vector3f[] normals = points.mesh().pointNormals();
                    for (int i = 0; i < points.mesh().positions.length / 3; i++) {
                        Context point = c.at(points.mesh(), i, base, normals);
                        if (field(e.input("Selection"), point).x() == 0) continue;
                        require(field(e.input("Pick Instance"), point).x() == 0, e.label() + ": Pick Instance is unsupported");
                        Matrix4f transform = matrix(value(points.mesh().position(i)), field(e.input("Rotation"), point), field(e.input("Scale"), point));
                        for (BlendGeometry.Part instance : source.parts) {
                            parts.add(new BlendGeometry.Part(instance.mesh(), new Matrix4f(transform).mul(instance.transform()), true));
                        }
                    }
                    base = Math.addExact(base, points.mesh().positions.length / 3);
                }
                result = new BlendGeometry(parts);
            }
            case "GeometryNodeRealizeInstances" -> {
                require(!e.has("Selection") || field(e.input("Selection"), c).x() != 0, e.label() + ": selective realization is unsupported");
                require(!e.has("Realize All") || field(e.input("Realize All"), c).x() != 0, e.label() + ": depth-limited realization is unsupported");
                result = geometry(e.input("Geometry"), c, memo).realize();
            }
            default -> throw e.unsupported("Node does not produce geometry");
        }
        memo.put(e, result);
        if (!contextual.getOrDefault(e, true)) constantGeometry.put(e, result);
        return result;
    }

    private Value field(Expr e, Context c) throws IOException {
        Value cached = c.fields.get(e);
        if (cached != null) return cached;
        Value result = switch (e.operation()) {
            case "Constant", "ShaderNodeValue", "ShaderNodeRGB" -> e.value();
            case "Parameter" -> c.parameters.containsKey(e.output()) ? Value.scalar(c.parameters.get(e.output())) : e.value();
            case "GeometryNodeInputSceneTime" -> Value.scalar(e.output().equals("Frame") ? c.startFrame + c.seconds * c.fps
                : c.seconds + c.startFrame / c.fps);
            case "GeometryNodeInputPosition" -> { require(c.mesh != null, e.label() + ": Position needs a point domain"); yield value(c.mesh.position(c.point)); }
            case "GeometryNodeInputNormal" -> { require(c.normals != null, e.label() + ": Normal needs a point domain"); yield value(c.normals[c.point]); }
            case "GeometryNodeInputIndex" -> { require(c.point >= 0, e.label() + ": Index needs a point domain"); yield Value.scalar(Math.addExact(c.base, c.point)); }
            case "FunctionNodeEulerToRotation" -> field(e.input("Euler"), c);
            case "FunctionNodeRotationToEuler" -> field(e.input("Rotation"), c);
            case "FunctionNodeInputVector" -> BlendNodeGraph.vector(e.storage().floats("vector", 3));
            case "FunctionNodeInputFloat" -> Value.scalar(e.storage().scalar("value"));
            case "FunctionNodeInputInt" -> Value.scalar(e.storage().integer("integer"));
            case "FunctionNodeInputBool" -> Value.scalar(e.storage().integer("boolean"));
            case "ShaderNodeCombineXYZ" -> new Value(field(e.input("X"), c).x(), field(e.input("Y"), c).x(), field(e.input("Z"), c).x(), 1);
            case "ShaderNodeSeparateXYZ" -> Value.scalar(field(e.input("Vector"), c).component(switch (e.output()) { case "X" -> 0; case "Y" -> 1; default -> 2; }));
            case "ShaderNodeMath" -> {
                float a = field(e.input("Value"), c).x();
                float b = e.has("Value_001") ? field(e.input("Value_001"), c).x() : 0;
                float d = e.has("Value_002") ? field(e.input("Value_002"), c).x() : 0;
                float value = math(e.option(), a, b, d);
                if ((e.data().integer("custom2") & 1) != 0) value = Math.max(0, Math.min(1, value));
                yield Value.scalar(finite(value, e));
            }
            case "ShaderNodeVectorMath" -> vectorMath(e.option(), field(e.input("Vector"), c),
                e.has("Vector_001") ? field(e.input("Vector_001"), c) : Value.ZERO,
                e.has("Scale") ? field(e.input("Scale"), c).x() : 1);
            default -> throw e.unsupported("Node does not produce a supported field");
        };
        c.fields.put(e, result);
        return result;
    }

    static float math(int op, float a, float b, float c) throws IOException {
        return switch (op) {
            case 0 -> a + b; case 1 -> a - b; case 2 -> a * b; case 3 -> b == 0 ? 0 : a / b;
            case 4 -> (float) Math.sin(a); case 5 -> (float) Math.cos(a); case 6 -> (float) Math.tan(a);
            case 7 -> (float) Math.asin(Math.max(-1, Math.min(1, a))); case 8 -> (float) Math.acos(Math.max(-1, Math.min(1, a)));
            case 9 -> (float) Math.atan(a); case 10 -> a < 0 && b != Math.rint(b) ? 0 : (float) Math.pow(a, b);
            case 11 -> a > 0 && b > 0 && b != 1 ? (float) (Math.log(a) / Math.log(b)) : 0;
            case 12 -> Math.min(a, b); case 13 -> Math.max(a, b); case 14 -> (float) Math.floor(a + 0.5f);
            case 15 -> a < b ? 1 : 0; case 16 -> a > b ? 1 : 0; case 17 -> b == 0 ? 0 : a % b;
            case 18 -> Math.abs(a); case 19 -> (float) Math.atan2(a, b); case 20 -> (float) Math.floor(a);
            case 21 -> (float) Math.ceil(a); case 22 -> a - (float) Math.floor(a);
            case 23 -> a > 0 ? (float) Math.sqrt(a) : 0; case 24 -> a > 0 ? (float) (1 / Math.sqrt(a)) : 0;
            case 25 -> Math.signum(a); case 26 -> (float) Math.exp(a); case 27 -> (float) Math.toRadians(a);
            case 28 -> (float) Math.toDegrees(a); case 32 -> a < 0 ? (float) Math.ceil(a) : (float) Math.floor(a);
            case 33 -> b == 0 ? 0 : (float) Math.floor(a / b) * b;
            case 35 -> Math.abs(a - b) <= Math.max(c, 1e-5f) ? 1 : 0;
            case 36 -> a * b + c;
            case 40 -> b == 0 ? 0 : a - (float) Math.floor(a / b) * b;
            default -> throw new IOException("Unsupported Math operation " + op);
        };
    }

    private static Value vectorMath(int op, Value a, Value b, float scale) throws IOException {
        Vector3f x = vector(a), y = vector(b);
        return switch (op) {
            case 0 -> value(x.add(y)); case 1 -> value(x.sub(y)); case 2 -> value(x.mul(y));
            case 3 -> new Value(b.x() == 0 ? 0 : a.x() / b.x(), b.y() == 0 ? 0 : a.y() / b.y(), b.z() == 0 ? 0 : a.z() / b.z(), 1);
            case 4 -> value(x.cross(y)); case 7 -> Value.scalar(x.dot(y)); case 8 -> Value.scalar(x.distance(y));
            case 9 -> Value.scalar(x.length()); case 10 -> value(x.mul(scale));
            case 11 -> value(x.lengthSquared() > 0 ? x.normalize() : x);
            case 13 -> value(x.floor()); case 14 -> value(x.ceil()); case 17 -> value(x.absolute());
            case 18 -> value(x.min(y)); case 19 -> value(x.max(y));
            default -> throw new IOException("Unsupported Vector Math operation " + op);
        };
    }

    private static float finite(float value, Expr e) throws IOException { require(Float.isFinite(value), e.label() + ": non-finite geometry result"); return value; }
    private static int integer(Value value, Expr e) throws IOException {
        require(Float.isFinite(value.x()) && value.x() >= 0 && (double) value.x() <= Integer.MAX_VALUE, e.label() + ": invalid integer input");
        return (int) value.x();
    }
    static Vector3f vector(Value value) { return new Vector3f(value.x(), value.y(), value.z()); }
    static Value value(Vector3f value) { return new Value(value.x, value.y, value.z, 1); }
    static Matrix4f matrix(Value position, Value rotation, Value scale) {
        Quaternionf quaternion = new Quaternionf().rotationZYX(rotation.z(), rotation.y(), rotation.x());
        return new Matrix4f().translationRotateScale(vector(position), quaternion, vector(scale));
    }
}
