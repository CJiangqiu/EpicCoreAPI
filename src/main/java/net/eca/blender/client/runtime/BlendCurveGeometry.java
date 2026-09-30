package net.eca.blender.client.runtime;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.eca.blender.client.runtime.BlendFile.require;

/** Cyclic polylines, kept separate from mesh and point components. */
final class BlendCurveGeometry {
    record Curve(float[] positions, Vector3f planeNormal, int material) {
        Vector3f point(int i) { return new Vector3f(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]); }

        Curve transform(Matrix4f matrix) throws IOException {
            require(matrix.isFinite() && Math.abs(matrix.determinant()) > 1e-12f, "Singular curve transform");
            float[] output = new float[positions.length];
            for (int i = 0; i < output.length / 3; i++) {
                Vector3f p = matrix.transformPosition(point(i));
                require(p.isFinite(), "Non-finite curve position");
                output[i * 3] = p.x; output[i * 3 + 1] = p.y; output[i * 3 + 2] = p.z;
            }
            Vector3f normal = new Matrix3f(matrix).invert().transpose().transform(new Vector3f(planeNormal)).normalize();
            return new Curve(output, normal, material);
        }
    }

    static BlendGeometry circle(int count, float radius) throws IOException {
        require(count >= 3 && Float.isFinite(radius) && radius > 0, "Invalid circle resolution or radius");
        float[] positions = new float[Math.multiplyExact(count, 3)];
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / count;
            positions[i * 3] = radius * (float) Math.cos(angle);
            positions[i * 3 + 1] = radius * (float) Math.sin(angle);
        }
        return new BlendGeometry(List.of(), List.of(new Curve(positions, new Vector3f(0, 0, 1), -1)));
    }

    static BlendGeometry points(BlendGeometry source, int count) throws IOException {
        require(source.parts.isEmpty(), "Curve to Points requires a curve-only input");
        require(count >= 1, "Curve point count must be positive");
        List<BlendGeometry.Part> parts = new ArrayList<>();
        for (Curve curve : source.curves) {
            int n = curve.positions.length / 3;
            float[] distances = new float[n + 1];
            for (int i = 0; i < n; i++) distances[i + 1] = distances[i] + curve.point(i).distance(curve.point((i + 1) % n));
            require(Float.isFinite(distances[n]) && distances[n] > 0, "Degenerate sampled curve");
            float[] positions = new float[Math.multiplyExact(count, 3)];
            int segment = 0;
            for (int i = 0; i < count; i++) {
                float distance = distances[n] * ((float) i / count);
                while (segment < n - 1 && distances[segment + 1] <= distance) segment++;
                float length = distances[segment + 1] - distances[segment];
                require(length > 0, "Zero-length curve segment");
                Vector3f p = curve.point(segment).lerp(curve.point((segment + 1) % n), (distance - distances[segment]) / length);
                positions[i * 3] = p.x; positions[i * 3 + 1] = p.y; positions[i * 3 + 2] = p.z;
            }
            BlendGeometry.Mesh mesh = new BlendGeometry.Mesh(positions, new int[0], new int[]{0}, new float[0],
                new int[0], new boolean[0], new int[0], new float[0]);
            parts.add(new BlendGeometry.Part(mesh, new Matrix4f(), false));
        }
        return new BlendGeometry(parts);
    }

    static BlendGeometry sweep(BlendGeometry paths, BlendGeometry profiles, float scale) throws IOException {
        require(paths.parts.isEmpty() && profiles.parts.isEmpty(), "Curve to Mesh requires curve-only inputs");
        require(Float.isFinite(scale) && scale > 0, "Curve profile scale must be positive");
        require(!profiles.curves.isEmpty(), "Curve to Mesh requires a cyclic profile curve");
        List<BlendGeometry.Part> parts = new ArrayList<>();
        for (Curve path : paths.curves) for (Curve profile : profiles.curves) {
            int n = path.positions.length / 3, m = profile.positions.length / 3;
            int vertices = Math.multiplyExact(n, m);
            float[] xyz = new float[Math.multiplyExact(vertices, 3)], uv = new float[Math.multiplyExact(vertices, 8)];
            int[] corners = new int[Math.multiplyExact(vertices, 4)], offsets = new int[Math.addExact(vertices, 1)], materials = new int[vertices];
            boolean[] smooth = new boolean[vertices];
            Arrays.fill(materials, path.material);
            for (int i = 0; i < n; i++) {
                Vector3f tangent = path.point((i + 1) % n).sub(path.point((i == 0 ? n - 1 : i - 1)));
                require(tangent.lengthSquared() > 1e-14f, "Degenerate curve tangent");
                tangent.normalize();
                Vector3f normal = new Vector3f(tangent).cross(path.planeNormal).normalize();
                Vector3f binormal = new Vector3f(tangent).cross(normal);
                for (int j = 0; j < m; j++) {
                    Vector3f q = profile.point(j).mul(scale);
                    Vector3f p = path.point(i).fma(q.x, normal).fma(q.y, binormal).fma(q.z, tangent);
                    require(p.isFinite(), "Non-finite curve sweep vertex");
                    int v = i * m + j;
                    xyz[v * 3] = p.x; xyz[v * 3 + 1] = p.y; xyz[v * 3 + 2] = p.z;
                    corners[v * 4] = v;
                    corners[v * 4 + 1] = i * m + (j + 1) % m;
                    corners[v * 4 + 2] = ((i + 1) % n) * m + (j + 1) % m;
                    corners[v * 4 + 3] = ((i + 1) % n) * m + j;
                    offsets[v] = v * 4;
                    for (int c = 0; c < 4; c++) {
                        uv[v * 8 + c * 2] = (float) (i + (c >= 2 ? 1 : 0)) / n;
                        uv[v * 8 + c * 2 + 1] = (float) (j + (c == 1 || c == 2 ? 1 : 0)) / m;
                    }
                }
            }
            offsets[vertices] = corners.length;
            parts.add(new BlendGeometry.Part(new BlendGeometry.Mesh(xyz, corners, offsets, uv, materials, smooth,
                new int[0], new float[0]), new Matrix4f(), false));
        }
        return new BlendGeometry(parts);
    }

    static BlendGeometry icoSphere(float radius, int subdivisions) throws IOException {
        require(Float.isFinite(radius) && radius > 0 && subdivisions >= 1,
            "Invalid Icosphere radius or subdivisions");
        int expectedFaces = 20;
        for (int level = 1; level < subdivisions; level++) expectedFaces = Math.multiplyExact(expectedFaces, 4);
        // Each triangle needs six UV components in the current array representation.
        Math.multiplyExact(expectedFaces, 6);
        List<Vector3f> points = new ArrayList<>();
        points.add(new Vector3f(0, 0, 1)); points.add(new Vector3f(0, 0, -1));
        float z = (float) (1 / Math.sqrt(5)), r = 2 * z;
        for (int ring = 0; ring < 2; ring++) for (int i = 0; i < 5; i++) {
            double a = Math.PI * (2 * i + ring) / 5;
            points.add(new Vector3f(r * (float) Math.cos(a), r * (float) Math.sin(a), ring == 0 ? z : -z));
        }
        List<int[]> triangles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int a = 2 + i, b = 2 + (i + 1) % 5, c = 7 + i, d = 7 + (i + 1) % 5;
            triangles.add(new int[]{0, a, b}); triangles.add(new int[]{1, d, c});
            triangles.add(new int[]{a, c, b}); triangles.add(new int[]{b, c, d});
        }
        for (int level = 1; level < subdivisions; level++) {
            Map<Long, Integer> cache = new HashMap<>();
            List<int[]> refined = new ArrayList<>();
            for (int[] face : triangles) {
                int a = midpoint(points, cache, face[0], face[1]), b = midpoint(points, cache, face[1], face[2]);
                int c = midpoint(points, cache, face[2], face[0]);
                refined.add(new int[]{face[0], a, c}); refined.add(new int[]{a, face[1], b});
                refined.add(new int[]{c, b, face[2]}); refined.add(new int[]{a, b, c});
            }
            triangles = refined;
        }
        float[] xyz = new float[Math.multiplyExact(points.size(), 3)];
        for (int i = 0; i < points.size(); i++) {
            Vector3f p = points.get(i); xyz[i * 3] = p.x * radius; xyz[i * 3 + 1] = p.y * radius; xyz[i * 3 + 2] = p.z * radius;
        }
        int[] corners = new int[Math.multiplyExact(triangles.size(), 3)], offsets = new int[Math.addExact(triangles.size(), 1)], materials = new int[triangles.size()];
        Arrays.fill(materials, -1);
        for (int i = 0; i < triangles.size(); i++) { System.arraycopy(triangles.get(i), 0, corners, i * 3, 3); offsets[i] = i * 3; }
        offsets[triangles.size()] = corners.length;
        return BlendGeometry.single(new BlendGeometry.Mesh(xyz, corners, offsets, new float[Math.multiplyExact(corners.length, 2)], materials,
            new boolean[triangles.size()], new int[0], new float[0]));
    }

    private static int midpoint(List<Vector3f> points, Map<Long, Integer> cache, int a, int b) {
        long key = (long) Math.min(a, b) << 32 | Integer.toUnsignedLong(Math.max(a, b));
        return cache.computeIfAbsent(key, ignored -> {
            int index = points.size(); points.add(new Vector3f(points.get(a)).add(points.get(b)).normalize()); return index;
        });
    }
}
