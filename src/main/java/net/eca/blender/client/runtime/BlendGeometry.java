package net.eca.blender.client.runtime;

import net.eca.blender.client.model.BlenderModelAsset;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.eca.blender.client.runtime.BlendFile.require;

/** Keeps point and corner domains separate so UV seams do not duplicate procedural points. */
final class BlendGeometry {
    static final BlendGeometry EMPTY = new BlendGeometry(List.of(), true);
    final List<Part> parts;
    final List<BlendCurveGeometry.Curve> curves;

    BlendGeometry(List<Part> parts) throws IOException {
        this(parts, List.of());
    }

    BlendGeometry(List<Part> parts, List<BlendCurveGeometry.Curve> curves) throws IOException {
        this.parts = List.copyOf(parts);
        this.curves = List.copyOf(curves);
    }

    private BlendGeometry(List<Part> parts, boolean empty) { this.parts = parts; this.curves = List.of(); }

    static BlendGeometry single(Mesh mesh) throws IOException {
        return new BlendGeometry(List.of(new Part(mesh, new Matrix4f(), false)));
    }

    BlendGeometry transform(Matrix4f matrix) throws IOException {
        List<Part> result = new ArrayList<>();
        for (Part part : parts) {
            if (part.instance) result.add(new Part(part.mesh, new Matrix4f(matrix).mul(part.transform), true));
            else result.add(new Part(part.mesh.transform(new Matrix4f(matrix).mul(part.transform)), new Matrix4f(), false));
        }
        List<BlendCurveGeometry.Curve> transformed = new ArrayList<>();
        for (BlendCurveGeometry.Curve curve : curves) transformed.add(curve.transform(matrix));
        return new BlendGeometry(result, transformed);
    }

    BlendGeometry realize() throws IOException {
        List<Part> result = new ArrayList<>();
        for (Part part : parts) result.add(new Part(part.mesh.transform(part.transform), new Matrix4f(), false));
        return new BlendGeometry(result, curves);
    }

    BlenderModelAsset.Mesh renderMesh() throws IOException {
        require(curves.isEmpty(), "Convert curves to mesh before rendering");
        List<BlenderModelAsset.Primitive> primitives = new ArrayList<>();
        for (Part part : parts) primitives.addAll(part.mesh.transform(part.transform).primitives());
        return new BlenderModelAsset.Mesh(List.copyOf(primitives));
    }

    record Part(Mesh mesh, Matrix4f transform, boolean instance) { }

    static final class Mesh {
        final float[] positions;
        final int[] corners;
        final int[] offsets;
        final float[] uv;
        final int[] materials;
        final boolean[] smooth;
        final int[] joints;
        final float[] weights;

        Mesh(float[] positions, int[] corners, int[] offsets, float[] uv, int[] materials,
             boolean[] smooth, int[] joints, float[] weights) {
            this.positions = positions;
            this.corners = corners;
            this.offsets = offsets;
            this.uv = uv;
            this.materials = materials;
            this.smooth = smooth;
            this.joints = joints;
            this.weights = weights;
        }

        Vector3f position(int i) { return new Vector3f(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]); }
        Mesh withPositions(float[] values) { return new Mesh(values, corners, offsets, uv, materials, smooth, joints, weights); }

        Mesh transform(Matrix4f matrix) {
            if (matrix.equals(new Matrix4f())) return this;
            float[] values = new float[positions.length];
            for (int i = 0; i < positions.length / 3; i++) {
                Vector3f p = matrix.transformPosition(position(i));
                values[i * 3] = p.x; values[i * 3 + 1] = p.y; values[i * 3 + 2] = p.z;
            }
            return withPositions(values);
        }

        Vector3f faceNormal(int face) {
            Vector3f normal = new Vector3f();
            int start = offsets[face], end = offsets[face + 1];
            for (int c = start; c < end; c++) {
                Vector3f a = position(corners[c]);
                Vector3f b = position(corners[c + 1 == end ? start : c + 1]);
                normal.x += (a.y - b.y) * (a.z + b.z);
                normal.y += (a.z - b.z) * (a.x + b.x);
                normal.z += (a.x - b.x) * (a.y + b.y);
            }
            return normal.lengthSquared() > 1e-20f ? normal.normalize() : normal.set(0, 0, 1);
        }

        Vector3f[] pointNormals() {
            Vector3f[] normals = new Vector3f[positions.length / 3];
            for (int i = 0; i < normals.length; i++) normals[i] = new Vector3f();
            for (int f = 0; f < materials.length; f++) {
                Vector3f n = faceNormal(f);
                int start = offsets[f], end = offsets[f + 1];
                for (int c = start; c < end; c++) {
                    Vector3f p = position(corners[c]);
                    Vector3f a = position(corners[c == start ? end - 1 : c - 1]).sub(p);
                    Vector3f b = position(corners[c + 1 == end ? start : c + 1]).sub(p);
                    float denominator = a.length() * b.length();
                    float angle = denominator > 0 ? (float) Math.acos(Math.max(-1, Math.min(1, a.dot(b) / denominator))) : 0;
                    normals[corners[c]].fma(angle, n);
                }
            }
            for (Vector3f n : normals) if (n.lengthSquared() > 1e-20f) n.normalize();
            return normals;
        }

        List<BlenderModelAsset.Primitive> primitives() throws IOException {
            Map<Integer, List<Integer>> triangles = new LinkedHashMap<>();
            int[] cornerFace = new int[corners.length];
            Vector3f[] faceNormals = new Vector3f[materials.length];
            Vector3f[] normals = pointNormals();
            for (int f = 0; f < materials.length; f++) {
                faceNormals[f] = faceNormal(f);
                for (int c = offsets[f]; c < offsets[f + 1]; c++) cornerFace[c] = f;
                triangulate(f, faceNormals[f], triangles.computeIfAbsent(materials[f], ignored -> new ArrayList<>()));
            }
            List<BlenderModelAsset.Primitive> result = new ArrayList<>();
            for (Map.Entry<Integer, List<Integer>> entry : triangles.entrySet()) {
                List<Integer> indices = entry.getValue();
                float[] p = new float[Math.multiplyExact(indices.size(), 3)];
                float[] n = new float[p.length];
                float[] t = new float[Math.multiplyExact(indices.size(), 2)];
                int[] j = joints.length == 0 ? new int[0] : new int[Math.multiplyExact(indices.size(), 4)];
                float[] w = weights.length == 0 ? new float[0] : new float[j.length];
                int[] sequential = new int[indices.size()];
                for (int i = 0; i < indices.size(); i++) {
                    int corner = indices.get(i), point = corners[corner], face = cornerFace[corner];
                    System.arraycopy(positions, point * 3, p, i * 3, 3);
                    Vector3f normal = smooth[face] ? normals[point] : faceNormals[face];
                    n[i * 3] = normal.x; n[i * 3 + 1] = normal.y; n[i * 3 + 2] = normal.z;
                    t[i * 2] = uv[corner * 2]; t[i * 2 + 1] = 1 - uv[corner * 2 + 1];
                    if (j.length != 0) {
                        System.arraycopy(joints, point * 4, j, i * 4, 4);
                        System.arraycopy(weights, point * 4, w, i * 4, 4);
                    }
                    sequential[i] = i;
                }
                result.add(new BlenderModelAsset.Primitive(p, n, t, j, w, sequential, entry.getKey()));
            }
            return result;
        }

        private void triangulate(int face, Vector3f normal, List<Integer> output) throws IOException {
            int start = offsets[face], end = offsets[face + 1];
            int axis = Math.abs(normal.x) > Math.abs(normal.y) ? 0 : 1;
            if (Math.abs(normal.z) > Math.abs(normal.get(axis))) axis = 2;
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            float sign = normal.get(axis) >= 0 ? 1 : -1;
            List<Integer> ring = new ArrayList<>();
            for (int c = start; c < end; c++) ring.add(c);
            while (ring.size() > 3) {
                boolean clipped = false;
                for (int i = 0; i < ring.size(); i++) {
                    int a = ring.get((i == 0 ? ring.size() - 1 : i - 1));
                    int b = ring.get(i), c = ring.get((i + 1) % ring.size());
                    Vector3f pa = position(corners[a]), pb = position(corners[b]), pc = position(corners[c]);
                    if (cross(pa, pb, pc, u, v) * sign <= 1e-10f) continue;
                    boolean contains = false;
                    for (int q : ring) {
                        if (q == a || q == b || q == c) continue;
                        Vector3f p = position(corners[q]);
                        if (cross(pa, pb, p, u, v) * sign >= 0 && cross(pb, pc, p, u, v) * sign >= 0
                            && cross(pc, pa, p, u, v) * sign >= 0) { contains = true; break; }
                    }
                    if (contains) continue;
                    output.add(a); output.add(b); output.add(c);
                    ring.remove(i); clipped = true; break;
                }
                require(clipped, "Degenerate or self-intersecting polygon cannot be triangulated");
            }
            if (ring.size() == 3) output.addAll(ring);
        }

        private static float cross(Vector3f a, Vector3f b, Vector3f c, int u, int v) {
            return (b.get(u) - a.get(u)) * (c.get(v) - a.get(v)) - (b.get(v) - a.get(v)) * (c.get(u) - a.get(u));
        }
    }
}
