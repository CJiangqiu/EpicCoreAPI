package net.eca.blender.client.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.eca.blender.client.runtime.BlendFile.require;

/** Resolves socket identifiers rather than translated display names. */
final class BlendNodeGraph {
    interface MaterialResolver { int resolve(BlendFile.View material) throws IOException; }
    record Value(float x, float y, float z, float w) {
        static final Value ZERO = new Value(0, 0, 0, 0);
        static Value scalar(float x) { return new Value(x, x, x, 1); }
        float component(int i) { return switch (i) { case 0 -> x; case 1 -> y; case 2 -> z; default -> w; }; }
    }

    record Input(String id, String name, String type, boolean linked, List<Expr> sources) {
        Expr first() { return sources.get(0); }
    }

    record Expr(String operation, String output, String outputType, String label, Value value,
                BlendFile.View data, List<Input> inputs) {
        Input socket(String id) throws IOException {
            for (Input input : inputs) if (input.id.equals(id)) return input;
            throw new IOException(label + ": missing input " + id);
        }
        Expr input(String id) throws IOException { return socket(id).first(); }
        boolean has(String id) { return inputs.stream().anyMatch(input -> input.id.equals(id)); }
        int option() throws IOException { return data == null ? 0 : data.integer("custom1"); }
        BlendFile.View storage() throws IOException { return data == null ? null : data.ref("storage"); }
        IOException unsupported(String detail) { return new IOException(label + " [" + operation + "]: " + detail); }
    }

    private final BlendFile file;
    private final BlendFile.View tree;
    private final MaterialResolver materials;
    private final Map<Long, BlendFile.View> owners = new HashMap<>();
    private final Map<Long, List<BlendFile.View>> links = new HashMap<>();
    private final List<BlendFile.View> nodes;
    private final Map<Long, BlendTimeDriver> drivers = new HashMap<>();

    BlendNodeGraph(BlendFile file, BlendFile.View tree) throws IOException {
        this(file, tree, null);
    }

    BlendNodeGraph(BlendFile file, BlendFile.View tree, MaterialResolver materials) throws IOException {
        require(tree != null, "Missing node tree");
        this.file = file;
        this.tree = tree;
        this.materials = materials;
        nodes = tree.list("nodes");
        readDrivers();
        for (BlendFile.View node : nodes) {
            for (BlendFile.View socket : node.list("outputs")) owners.put(socket.address(), node);
        }
        for (BlendFile.View link : tree.list("links")) {
            if ((link.integer("flag") & 16) != 0) continue;
            BlendFile.View from = link.ref("fromsock");
            BlendFile.View to = link.ref("tosock");
            require(from != null && to != null && owners.containsKey(from.address()), "Broken node link");
            if ((from.integer("flag") & 8) != 0 || (to.integer("flag") & 8) != 0) continue;
            links.computeIfAbsent(to.address(), ignored -> new ArrayList<>()).add(link);
        }
        for (List<BlendFile.View> values : links.values()) {
            Map<Long, Integer> order = new HashMap<>();
            for (BlendFile.View link : values) order.put(link.address(), link.integer("multi_input_socket_index"));
            values.sort(Comparator.comparingInt((BlendFile.View value) -> order.get(value.address())).reversed());
        }
    }

    private void readDrivers() throws IOException {
        BlendFile.View animation = tree.ref("adt");
        if (animation == null) return;
        require(animation.ref("action") == null && animation.list("nla_tracks").isEmpty(),
            tree.idName() + ": node actions and NLA are unsupported");
        List<BlendFile.View> curves = animation.list("drivers");
        require(curves.isEmpty() || materials == null,
            tree.idName() + ": time drivers are only supported on material inputs");
        Map<String, BlendFile.View> inputs = new HashMap<>();
        for (BlendFile.View node : nodes) {
            String name = node.text("name").replace("\\", "\\\\").replace("\"", "\\\"");
            List<BlendFile.View> sockets = node.list("inputs");
            for (int i = 0; i < sockets.size(); i++) {
                inputs.put("nodes[\"" + name + "\"].inputs[" + i + "].default_value", sockets.get(i));
            }
        }
        for (BlendFile.View curve : curves) {
            String path = curve.text("rna_path");
            BlendFile.View socket = inputs.get(path);
            require(socket != null && socket.text("idname").equals("NodeSocketFloat"),
                tree.idName() + ": unsupported time driver target " + path);
            require(drivers.put(socket.address(), BlendTimeDriver.read(file, curve)) == null, "Duplicate time driver target");
        }
    }

    Expr geometry(Map<String, Value> defaults) throws IOException {
        Map<String, Expr> inputs = new LinkedHashMap<>();
        for (BlendFile.View node : nodes) {
            if (!node.text("idname").equals("NodeGroupInput")) continue;
            for (BlendFile.View socket : node.list("outputs")) {
                String id = socket.text("identifier");
                String type = socket.text("idname");
                Expr value = type.equals("NodeSocketGeometry")
                    ? new Expr("GeometryInput", id, type, tree.idName() + "/" + id, Value.ZERO, null, List.of())
                    : new Expr("Parameter", id, type, tree.idName() + "/" + id,
                        defaults.getOrDefault(id, socketValue(socket)), null, List.of());
                inputs.put(id, value);
            }
        }
        BlendFile.View output = output("NodeGroupOutput");
        for (BlendFile.View socket : output.list("inputs")) {
            if (socket.text("idname").equals("NodeSocketGeometry")) {
                return input(socket, inputs, new HashSet<>(), new HashMap<>(), 0).first();
            }
        }
        throw new IOException(tree.idName() + ": group has no geometry output");
    }

    Expr material() throws IOException {
        BlendFile.View output = output("ShaderNodeOutputMaterial");
        for (BlendFile.View socket : output.list("inputs")) {
            String id = socket.text("identifier");
            if (!id.equals("Surface") && links.containsKey(socket.address())) {
                throw new IOException(tree.idName() + ": unsupported material output " + id);
            }
        }
        return input(find(output.list("inputs"), "Surface"), Map.of(), new HashSet<>(), new HashMap<>(), 0).first();
    }

    private BlendFile.View output(String type) throws IOException {
        List<BlendFile.View> found = new ArrayList<>();
        for (BlendFile.View node : nodes) {
            if (!node.text("idname").equals(type)) continue;
            if ((node.integer("flag") & 64) != 0) return node;
            found.add(node);
        }
        require(found.size() == 1, tree.idName() + ": no unambiguous active " + type);
        return found.get(0);
    }

    private Input input(BlendFile.View socket, Map<String, Expr> bindings, Set<String> active,
                        Map<Long, Expr> memo, int depth) throws IOException {
        List<Expr> sources = new ArrayList<>();
        for (BlendFile.View link : links.getOrDefault(socket.address(), List.of())) {
            sources.add(resolve(link.ref("fromsock"), bindings, active, memo, depth + 1));
        }
        boolean linked = !sources.isEmpty();
        BlendTimeDriver driver = drivers.get(socket.address());
        require(driver == null || !linked, "Linked sockets cannot also use a time driver");
        if (!linked) sources.add(new Expr(driver == null ? "Constant" : "TimeDriver", socket.text("identifier"), socket.text("idname"),
            tree.idName() + "/" + socket.text("name"), driver == null ? socketValue(socket) : Value.scalar(driver.divisor()), socket, List.of()));
        return new Input(socket.text("identifier"), socket.text("name"), socket.text("idname"), linked || driver != null, List.copyOf(sources));
    }

    private Expr resolve(BlendFile.View socket, Map<String, Expr> bindings, Set<String> active,
                         Map<Long, Expr> memo, int depth) throws IOException {
        Expr cached = memo.get(socket.address());
        if (cached != null) return cached;
        BlendFile.View node = owners.get(socket.address());
        require(node != null, "Missing output socket owner");
        String key = tree.address() + ":" + socket.address();
        require(active.add(key), "Cyclic node dependency: " + node.text("name"));
        try {
            String op = node.text("idname");
            String id = socket.text("identifier");
            String label = tree.idName() + "/" + node.text("name");
            require((node.integer("flag") & 512) == 0, label + ": muted nodes require explicit bypass links");
            Expr result;
            if (op.equals("NodeGroupInput")) {
                result = bindings.get(id);
                require(result != null, label + ": missing group input " + id);
            } else if (op.equals("NodeReroute")) {
                result = input(node.list("inputs").get(0), bindings, active, memo, depth + 1).first();
            } else {
                List<Input> inputs = new ArrayList<>();
                for (BlendFile.View in : node.list("inputs")) {
                    // Menu-controlled sockets can remain serialized as available in 5.2.
                    if (op.equals("GeometryNodeTransform") && in.text("identifier").equals("Transform")) continue;
                    if ((in.integer("flag") & 8) == 0) inputs.add(input(in, bindings, active, memo, depth + 1));
                }
                if (op.equals("GeometryNodeGroup") || op.equals("ShaderNodeGroup")) {
                    Map<String, Expr> nestedBindings = new HashMap<>();
                    for (Input in : inputs) nestedBindings.put(in.id, in.first());
                    BlendNodeGraph nested = new BlendNodeGraph(file, node.ref("id"), materials);
                    BlendFile.View target = find(nested.output("NodeGroupOutput").list("inputs"), id);
                    result = nested.input(target, nestedBindings, active, new HashMap<>(), depth + 1).first();
                } else {
                    result = new Expr(op, id, socket.text("idname"), label, defaultValue(socket), node, List.copyOf(inputs));
                }
            }
            memo.put(socket.address(), result);
            return result;
        } finally {
            active.remove(key);
        }
    }

    private static BlendFile.View find(List<BlendFile.View> sockets, String id) throws IOException {
        for (BlendFile.View socket : sockets) if (socket.text("identifier").equals(id)) return socket;
        throw new IOException("Missing node socket " + id);
    }

    static Value defaultValue(BlendFile.View socket) throws IOException {
        BlendFile.View value = socket.ref("default_value");
        if (value == null) return Value.ZERO;
        return switch (value.type()) {
            case "bNodeSocketValueFloat" -> Value.scalar(value.scalar("value"));
            case "bNodeSocketValueInt", "bNodeSocketValueBoolean", "bNodeSocketValueMenu" -> Value.scalar(value.integer("value"));
            case "bNodeSocketValueVector" -> vector(value.floats("value", 3));
            case "bNodeSocketValueRotation" -> vector(value.floats("value_euler", 3));
            case "bNodeSocketValueRGBA" -> vector(value.floats("value", 4));
            default -> Value.ZERO;
        };
    }

    private Value socketValue(BlendFile.View socket) throws IOException {
        if (!socket.text("idname").equals("NodeSocketMaterial")) return defaultValue(socket);
        require(materials != null, "Material sockets require a geometry material resolver");
        BlendFile.View value = socket.ref("default_value");
        return Value.scalar(materials.resolve(value == null ? null : value.ref("value")));
    }

    static Value vector(float[] values) {
        return new Value(values[0], values.length > 1 ? values[1] : values[0],
            values.length > 2 ? values[2] : values[0], values.length > 3 ? values[3] : 1);
    }

    static Map<String, Value> modifierDefaults(BlendFile file, BlendFile.View modifier) throws IOException {
        BlendFile.View properties = modifier.embedded("settings").ref("properties");
        if (properties == null) properties = modifier.embedded("modifier").ref("system_properties");
        Map<String, Value> result = new LinkedHashMap<>();
        if (properties != null) readProperties(file, properties, result, new HashSet<>());
        return Map.copyOf(result);
    }

    private static void readProperties(BlendFile file, BlendFile.View group, Map<String, Value> out, Set<Long> active) throws IOException {
        require(active.add(group.address()), "Cyclic modifier properties");
        for (BlendFile.View property : group.embedded("data").list("group")) {
            String name = property.text("name");
            int type = property.integer("type");
            BlendFile.View data = property.embedded("data");
            Value value = switch (type) {
                case 1, 10 -> Value.scalar(data.integer("val"));
                case 2 -> Value.scalar(Float.intBitsToFloat(data.integer("val")));
                case 8 -> Value.scalar((float) Double.longBitsToDouble(
                    Integer.toUnsignedLong(data.integer("val")) | (long) data.integer("val2") << 32));
                default -> null;
            };
            if (type == 5) {
                int size = property.integer("len");
                int subtype = property.integer("subtype");
                require(size >= 1 && size <= 4 && (subtype == 2 || subtype == 8), "Unsupported modifier array " + name);
                ByteBuffer array = file.buffer(data.ptr("pointer"), size * (subtype == 8 ? 8 : 4));
                float[] elements = new float[size];
                for (int i = 0; i < size; i++) elements[i] = subtype == 8 ? (float) array.getDouble() : array.getFloat();
                value = vector(elements);
            }
            if (type == 6) { readProperties(file, property, out, active); continue; }
            if (name.endsWith("_use_attribute")) {
                require(value == null || value.x == 0, "Attribute-bound modifier inputs are not supported: " + name);
            } else if (value != null) {
                require(Float.isFinite(value.x) && Float.isFinite(value.y) && Float.isFinite(value.z)
                    && Float.isFinite(value.w), "Non-finite modifier input " + name);
                out.put(name, value);
            }
        }
        active.remove(group.address());
    }
}
