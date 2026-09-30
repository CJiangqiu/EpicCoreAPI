package net.eca.blender.client.runtime;

import org.joml.Vector3f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.eca.blender.client.runtime.BlendFile.require;

/** Convex edge bevels with circular profiles; corner patches use a bounded fan tessellation. */
final class BlendBevelModifier implements BlendGeometryModifier {
    private final String label;
    private final float width;
    private final float angle;
    private final int segments;
    private final boolean clamp;

    BlendBevelModifier(BlendFile.View data, String object) throws IOException {
        label = object + "/" + data.embedded("modifier").text("name");
        width = data.scalar("value");
        angle = data.scalar("bevel_angle");
        segments = data.integer("res");
        int flags = data.integer("flags");
        clamp = (flags & (1 << 13)) == 0;
        require(Float.isFinite(width) && width >= 0 && Float.isFinite(angle) && angle >= 0 && angle <= Math.PI,
            label + ": invalid bevel width or angle");
        require(segments >= 1, label + ": bevel segments must be positive");
        require(data.integer("affect_type") == 1 && data.integer("val_flags") == 0
            && data.integer("lim_flags") == 8, label + ": bevel requires Edges, Offset and Angle limit");
        require(data.integer("profile_type") == 0 && Math.abs(data.scalar("profile") - 0.5f) < 1e-6f,
            label + ": bevel requires a circular profile");
        require(data.integer("miter_inner") == 0 && data.integer("miter_outer") == 0 && data.integer("vmesh_method") == 0,
            label + ": unsupported bevel intersection mode");
        require((flags & (1 << 14)) == 0, label + ": bevel requires Loop Slide");
        require(data.integer("mat") == -1 && data.integer("edge_flags") == 0 && data.integer("face_str_mode") == 0
            && (flags & (1 << 15)) == 0, label + ": bevel material override, edge marks and hardened normals are unsupported");
    }

    @Override
    public boolean dynamic() { return false; }

    @Override
    public BlendGeometry evaluate(BlendGeometry input, float seconds, float fps, float startFrame,
                                  Map<String, Float> parameters) throws IOException {
        if (width == 0) return input;
        require(input.curves.isEmpty(), label + ": convert curves to mesh before applying bevel");
        List<BlendGeometry.Part> parts = new ArrayList<>();
        for (BlendGeometry.Part part : input.parts) {
            require(!part.instance(), label + ": realize instances before applying bevel");
            parts.add(new BlendGeometry.Part(bevel(part.mesh()), part.transform(), false));
        }
        return new BlendGeometry(parts);
    }

    private BlendGeometry.Mesh bevel(BlendGeometry.Mesh mesh) throws IOException {
        int count = mesh.corners.length;
        if (count == 0) return mesh;
        Map<Long, Edge> edges = new LinkedHashMap<>();
        int[] next = new int[count], previous = new int[count], faces = new int[count];
        Edge[] cornerEdges = new Edge[count];
        Vector3f[] normals = new Vector3f[mesh.materials.length];
        for (int f = 0; f < normals.length; f++) {
            normals[f] = mesh.faceNormal(f);
            for (int c = mesh.offsets[f]; c < mesh.offsets[f + 1]; c++) {
                next[c] = c + 1 == mesh.offsets[f + 1] ? mesh.offsets[f] : c + 1;
                previous[c] = c == mesh.offsets[f] ? mesh.offsets[f + 1] - 1 : c - 1;
                faces[c] = f;
                int a = mesh.corners[c], b = mesh.corners[next[c]];
                require(a != b, label + ": degenerate bevel edge");
                Edge edge = edges.computeIfAbsent(key(a, b), ignored -> new Edge());
                require(edge.second < 0, label + ": non-manifold bevel edge");
                if (edge.first < 0) edge.first = c; else edge.second = c;
                cornerEdges[c] = edge;
            }
        }
        int selected = 0;
        for (Edge edge : edges.values()) {
            if (edge.second < 0) continue;
            int a = edge.first, b = edge.second;
            require(mesh.corners[a] == mesh.corners[next[b]] && mesh.corners[next[a]] == mesh.corners[b],
                label + ": inconsistent face winding");
            float cosine = Math.max(-1, Math.min(1, normals[faces[a]].dot(normals[faces[b]])));
            edge.angle = (float) Math.acos(cosine);
            edge.selected = edge.angle > angle + 1e-6f;
            if (!edge.selected) continue;
            Vector3f direction = mesh.position(mesh.corners[next[a]]).sub(mesh.position(mesh.corners[a])).normalize();
            require(new Vector3f(normals[faces[a]]).cross(normals[faces[b]]).dot(direction) > 1e-6f,
                label + ": concave bevel edges are unsupported");
            require(edge.angle < Math.PI - 1e-4f, label + ": folded bevel edge");
            selected++;
        }
        if (selected == 0) return mesh;
        require(mesh.joints.length == 0, label + ": bevel weight interpolation is not implemented");
        Vector3f[] shifts = new Vector3f[count];
        for (int c = 0; c < count; c++) {
            Vector3f p = mesh.position(mesh.corners[c]);
            Vector3f incoming = new Vector3f(p).sub(mesh.position(mesh.corners[previous[c]])).normalize();
            Vector3f outgoing = mesh.position(mesh.corners[next[c]]).sub(p).normalize();
            Vector3f n = normals[faces[c]];
            float turn = new Vector3f(incoming).cross(outgoing).dot(n);
            require(turn > 1e-6f, label + ": bevel requires strictly convex planar faces");
            Vector3f first = new Vector3f(n).cross(incoming), second = new Vector3f(n).cross(outgoing);
            float d1 = cornerEdges[previous[c]].selected ? 1 : 0;
            float d2 = cornerEdges[c].selected ? 1 : 0;
            float dot = first.dot(second), denominator = 1 - dot * dot;
            require(denominator > 1e-8f, label + ": unstable bevel corner");
            shifts[c] = first.mul((d1 - dot * d2) / denominator).fma((d2 - dot * d1) / denominator, second);
            Vector3f planePoint = mesh.position(mesh.corners[mesh.offsets[faces[c]]]);
            require(Math.abs(new Vector3f(p).sub(planePoint).dot(n)) < 1e-4f,
                label + ": non-planar bevel face");
        }
        float amount = width;
        for (int c = 0; c < count; c++) {
            Vector3f edge = mesh.position(mesh.corners[next[c]]).sub(mesh.position(mesh.corners[c]));
            float length = edge.length();
            require(length > 1e-7f, label + ": zero-length bevel edge");
            float closing = new Vector3f(shifts[c]).sub(shifts[next[c]]).dot(edge.div(length));
            if (closing <= 1e-7f) continue;
            float limit = length / closing;
            if (amount >= limit) {
                require(clamp, label + ": bevel overlaps; enable Clamp Overlap or reduce width");
                // Keep the inset faces non-degenerate for the runtime triangulator.
                amount = limit * 0.999f;
            }
        }
        Builder out = new Builder(mesh, label);
        int[] inset = new int[count];
        for (int c = 0; c < count; c++) {
            inset[c] = out.point(mesh.corners[c], mesh.position(mesh.corners[c]).fma(amount, shifts[c]), c);
        }
        for (int f = 0; f < normals.length; f++) {
            List<Integer> polygon = new ArrayList<>();
            for (int c = mesh.offsets[f]; c < mesh.offsets[f + 1]; c++) polygon.add(inset[c]);
            out.face(polygon, mesh.materials[f], mesh.smooth[f]);
        }
        for (Edge edge : edges.values()) {
            if (!edge.selected) continue;
            int a = edge.first, b = edge.second;
            int[] left = profile(out, mesh, a, next[b], inset, normals[faces[a]], normals[faces[b]], amount, edge.angle);
            int[] right = profile(out, mesh, next[a], b, inset, normals[faces[a]], normals[faces[b]], amount, edge.angle);
            for (int s = 0; s < segments; s++) {
                out.face(List.of(right[s], left[s], left[s + 1], right[s + 1]), mesh.materials[faces[a]],
                    mesh.smooth[faces[a]] || mesh.smooth[faces[b]]);
            }
        }
        out.closeCorners();
        for (Boundary remaining : out.boundary.values()) {
            Edge original = edges.get(key(out.origins.get(remaining.a), out.origins.get(remaining.b)));
            require(original != null && original.second < 0, label + ": bevel produced an unclosed interior boundary");
        }
        return out.mesh();
    }

    private int[] profile(Builder out, BlendGeometry.Mesh mesh, int a, int b, int[] inset,
                          Vector3f na, Vector3f nb, float amount, float angle) throws IOException {
        int[] result = new int[Math.addExact(segments, 1)];
        result[0] = inset[a]; result[segments] = inset[b];
        Vector3f start = out.positions.get(inset[a]), end = out.positions.get(inset[b]);
        float radius = amount / (float) Math.tan(angle * 0.5f);
        Vector3f ca = new Vector3f(start).fma(-radius, na), cb = new Vector3f(end).fma(-radius, nb);
        float sine = (float) Math.sin(angle);
        for (int s = 1; s < segments; s++) {
            float t = (float) s / segments;
            Vector3f normal = new Vector3f(na).mul((float) Math.sin((1 - t) * angle) / sine)
                .fma((float) Math.sin(t * angle) / sine, nb);
            Vector3f position = new Vector3f(ca).lerp(cb, t).fma(radius, normal);
            result[s] = out.point(mesh.corners[a], position, a);
        }
        return result;
    }

    private static long key(int a, int b) { return (long) Math.min(a, b) << 32 | Integer.toUnsignedLong(Math.max(a, b)); }

    private static final class Edge {
        int first = -1;
        int second = -1;
        boolean selected;
        float angle;
    }

    private record Boundary(int a, int b, int material, boolean smooth) { }

    private static final class Builder {
        final BlendGeometry.Mesh source;
        final String label;
        final List<Vector3f> positions = new ArrayList<>();
        final List<Integer> origins = new ArrayList<>(), uvCorners = new ArrayList<>();
        final Map<Integer, List<Integer>> byOrigin = new HashMap<>();
        final List<Integer> corners = new ArrayList<>(), offsets = new ArrayList<>(List.of(0)), materials = new ArrayList<>();
        final List<Boolean> smooth = new ArrayList<>();
        final Map<Long, Boundary> boundary = new LinkedHashMap<>();

        Builder(BlendGeometry.Mesh source, String label) { this.source = source; this.label = label; }

        int point(int origin, Vector3f p, int uvCorner) throws IOException {
            require(p.isFinite(), label + ": non-finite bevel vertex");
            List<Integer> candidates = byOrigin.computeIfAbsent(origin, ignored -> new ArrayList<>());
            for (int i : candidates) if (positions.get(i).distanceSquared(p) < 1e-14f) return i;
            int index = positions.size();
            positions.add(p); origins.add(origin); uvCorners.add(uvCorner); candidates.add(index);
            return index;
        }

        void face(List<Integer> polygon, int material, boolean shadeSmooth) throws IOException {
            Math.addExact(corners.size(), polygon.size());
            for (int i = 0; i < polygon.size(); i++) {
                int a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
                require(a != b, label + ": collapsed bevel face");
                long key = key(a, b);
                Boundary other = boundary.remove(key);
                if (other == null) boundary.put(key, new Boundary(a, b, material, shadeSmooth));
                else require(other.a == b && other.b == a, label + ": inconsistent bevel patch winding");
                corners.add(a);
            }
            offsets.add(corners.size()); materials.add(material); smooth.add(shadeSmooth);
        }

        void closeCorners() throws IOException {
            Map<Integer, Boundary> outgoing = new LinkedHashMap<>();
            for (Boundary edge : boundary.values()) {
                if (!origins.get(edge.a).equals(origins.get(edge.b))) continue;
                require(outgoing.put(edge.b, edge) == null, label + ": unsupported branching bevel corner");
            }
            while (!outgoing.isEmpty()) {
                Boundary start = outgoing.values().iterator().next();
                int vertex = start.b;
                List<Integer> ring = new ArrayList<>();
                do {
                    Boundary edge = outgoing.remove(vertex);
                    require(edge != null, label + ": incomplete bevel corner boundary");
                    ring.add(vertex); vertex = edge.a;
                } while (vertex != start.b);
                require(ring.size() >= 3, label + ": degenerate bevel corner cap");
                Vector3f center = new Vector3f();
                for (int index : ring) center.add(positions.get(index));
                center.div(ring.size());
                int origin = origins.get(start.a);
                int middle = point(origin, center, uvCorners.get(start.a));
                for (int i = 0; i < ring.size(); i++) {
                    face(List.of(ring.get(i), ring.get((i + 1) % ring.size()), middle), start.material, start.smooth);
                }
            }
        }

        BlendGeometry.Mesh mesh() {
            float[] xyz = new float[Math.multiplyExact(positions.size(), 3)], uv = new float[Math.multiplyExact(corners.size(), 2)];
            for (int i = 0; i < positions.size(); i++) {
                Vector3f p = positions.get(i); xyz[i * 3] = p.x; xyz[i * 3 + 1] = p.y; xyz[i * 3 + 2] = p.z;
            }
            for (int c = 0; c < corners.size(); c++) {
                int original = uvCorners.get(corners.get(c));
                uv[c * 2] = source.uv[original * 2]; uv[c * 2 + 1] = source.uv[original * 2 + 1];
            }
            boolean[] shading = new boolean[smooth.size()];
            for (int i = 0; i < shading.length; i++) shading[i] = smooth.get(i);
            return new BlendGeometry.Mesh(xyz, corners.stream().mapToInt(Integer::intValue).toArray(),
                offsets.stream().mapToInt(Integer::intValue).toArray(), uv,
                materials.stream().mapToInt(Integer::intValue).toArray(), shading, new int[0], new float[0]);
        }
    }
}
