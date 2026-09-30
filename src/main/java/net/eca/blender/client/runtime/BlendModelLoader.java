package net.eca.blender.client.runtime;

import net.eca.blender.client.model.BlenderModelAsset;
import net.eca.blender.model.BlenderModelDefinition;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.eca.blender.client.runtime.BlendFile.require;

@SuppressWarnings("removal")
public final class BlendModelLoader {
    private final ResourceManager resources;
    private final ResourceLocation location;
    private final ResourceLocation id;
    private final BlenderModelDefinition definition;
    private final Resource source;
    private final BlendFile file;
    private final List<Node> nodes = new ArrayList<>();
    private final Map<Long, Integer> objectNodes = new LinkedHashMap<>();
    private final Map<Long, Map<String, Integer>> boneNodes = new HashMap<>();
    private final Map<Long, Map<String, Matrix4f>> restBones = new HashMap<>();
    private final Map<Long, Map<String, BlendFile.View>> boneData = new HashMap<>();
    private final List<BlendRuntime.Geometry> geometries = new ArrayList<>();
    private final List<BlenderModelAsset.Skin> skins = new ArrayList<>();
    private final List<BlenderModelAsset.Material> materials = new ArrayList<>();
    private final Map<Long, Integer> materialIndices = new HashMap<>();
    private final List<BlenderModelAsset.TextureData> textures = new ArrayList<>();
    private final Map<Long, ResourceLocation> imageLocations = new HashMap<>();

    public static BlenderModelAsset load(ResourceManager resources, ResourceLocation location, ResourceLocation id,
                                  BlenderModelDefinition definition) throws IOException {
        Resource source = resources.getResourceOrThrow(location);
        BlendFile file;
        try (InputStream input = source.open()) { file = BlendFile.read(input); }
        return new BlendModelLoader(resources, location, id, definition, source, file).build();
    }

    private BlendModelLoader(ResourceManager resources, ResourceLocation location, ResourceLocation id,
                             BlenderModelDefinition definition, Resource source, BlendFile file) {
        this.resources = resources; this.location = location; this.id = id; this.definition = definition;
        this.source = source; this.file = file;
    }

    private BlenderModelAsset build() throws IOException {
        List<BlendFile.View> scenes = file.all("Scene");
        require(!scenes.isEmpty(), "Blend file has no scene");
        BlendFile.View scene = scenes.get(0);
        List<BlendFile.View> globals = file.all("FileGlobal");
        if (!globals.isEmpty() && globals.get(0).ref("curscene") != null) scene = globals.get(0).ref("curscene");
        BlendFile.View render = scene.embedded("r");
        float fps = render.integer("frs_sec") / render.scalar("frs_sec_base");
        require(Float.isFinite(fps) && fps > 0 && fps <= 240, "Unsupported scene frame rate");
        float start = render.integer("sfra"), end = render.integer("efra");
        require(end >= start, "Invalid scene frame range");
        Set<Long> selected = new LinkedHashSet<>();
        if (definition.object() != null) {
            for (BlendFile.View object : file.all("Object")) if (object.idName().equals(definition.object())) selected.add(object.address());
        } else {
            BlendFile.View collection = scene.ref("master_collection");
            if (definition.collection() != null) {
                collection = null;
                for (BlendFile.View candidate : file.all("Collection")) {
                    if (candidate.idName().equals(definition.collection())) { collection = candidate; break; }
                }
            }
            require(collection != null, "Requested collection was not found");
            collect(collection, selected, new HashSet<>(), 0);
        }
        require(!selected.isEmpty(), "Requested blend object/collection is empty or missing");
        nodes.add(new Node("__blend_coordinates__", -1, BlendAnimation.Transform.identity(new Matrix4f().rotationX(-(float) Math.PI / 2))));
        List<BlendFile.View> objects = new ArrayList<>();
        for (long address : selected) {
            BlendFile.View object = file.view(address);
            int type = object.integer("type");
            if (type == 10 || type == 11) continue;
            require(type == 0 || type == 1 || type == 25, object.idName() + ": unsupported object type " + type);
            ensureObject(object, new HashSet<>(), 0);
            objects.add(object);
        }
        for (BlendFile.View object : objects) {
            if (object.integer("type") == 25) addArmature(object);
            for (BlendFile.View modifier : object.list("modifiers")) {
                if (modifier.type().equals("ArmatureModifierData") && enabled(modifier)) {
                    BlendFile.View armature = modifier.ref("object");
                    require(armature != null, object.idName() + ": missing armature object");
                    ensureObject(armature, new HashSet<>(), 0);
                    addArmature(armature);
                }
            }
        }
        for (BlendFile.View object : objects) {
            if (object.integer("type") == 1) addMesh(object);
        }
        require(!geometries.isEmpty(), "Blend selection contains no renderable meshes");
        BlendAnimation animation = new BlendAnimation(file);
        for (Map.Entry<Long, Integer> entry : objectNodes.entrySet()) {
            animation.addObject(file.view(entry.getKey()), entry.getValue(), boneNodes.getOrDefault(entry.getKey(), Map.of()));
        }
        List<BlenderModelAsset.Node> assetNodes = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            List<Integer> children = new ArrayList<>();
            for (int j = 0; j < nodes.size(); j++) if (nodes.get(j).parent == i) children.add(j);
            assetNodes.add(new BlenderModelAsset.Node(node.name, node.mesh, node.skin, children.stream().mapToInt(Integer::intValue).toArray(),
                new Vector3f(), new Quaternionf(), new Vector3f(1), node.transform.matrix()));
        }
        BlendRuntime runtime = new BlendRuntime(nodes.stream().map(node -> node.transform).toList(), geometries, animation.clips(start, end), fps, start);
        List<BlenderModelAsset.Mesh> baseMeshes = new ArrayList<>();
        for (BlendRuntime.Geometry geometry : geometries) baseMeshes.add(geometry.base().renderMesh());
        BlenderModelAsset asset = new BlenderModelAsset(id, definition, assetNodes, baseMeshes, skins, materials,
            runtime.animations(), new int[]{0}, textures, runtime);
        // Validate reachable geometry operations before replacing an entity's visible model.
        runtime.frame(asset, definition.defaultAnimation(), 0, 0, definition.nodeTimeSource(), Map.of());
        return asset;
    }

    private void collect(BlendFile.View collection, Set<Long> selected, Set<Long> visited, int depth) throws IOException {
        require(visited.add(collection.address()), "Cyclic collection hierarchy");
        for (BlendFile.View entry : collection.list("gobject")) selected.add(entry.ptr("ob"));
        for (BlendFile.View child : collection.list("children")) collect(child.ref("collection"), selected, visited, depth + 1);
        visited.remove(collection.address());
    }

    private int ensureObject(BlendFile.View object, Set<Long> active, int depth) throws IOException {
        Integer existing = objectNodes.get(object.address());
        if (existing != null) return existing;
        require(active.add(object.address()), "Cyclic object parenting");
        require(object.embedded("id").ptr("lib") == 0, object.idName() + ": external linked libraries are unsupported");
        require(object.ptr("dup_group") == 0, object.idName() + ": collection instancing is unsupported");
        BlendFile.View parent = object.ref("parent");
        int parentIndex = parent == null ? 0 : ensureObject(parent, active, depth + 1);
        Matrix4f prefix = parent == null ? new Matrix4f() : new Matrix4f().set(object.floats("parentinv", 16));
        if (parent != null && object.integer("partype") == 7) {
            require(parent.integer("type") == 25, object.idName() + ": bone parent is not an armature");
            addArmature(parent);
            String name = object.text("parsubstr");
            Integer boneIndex = boneNodes.get(parent.address()).get(name);
            require(boneIndex != null, object.idName() + ": missing parent bone " + name);
            BlendFile.View bone = boneData.get(parent.address()).get(name);
            Matrix4f attachment = (bone.integer("flag") & (1 << 23)) != 0
                ? new Matrix4f(restBones.get(parent.address()).get(name)).invert()
                : new Matrix4f().translation(0, bone.scalar("length") * object.scalar("parent_bone_head_tail_factor"), 0);
            prefix = attachment.mul(prefix);
            parentIndex = boneIndex;
        } else {
            require(parent == null || object.integer("partype") == 0, object.idName() + ": unsupported parenting mode");
        }
        int index = nodes.size();
        nodes.add(new Node(object.idName(), parentIndex, BlendAnimation.Transform.read(object, prefix, true)));
        objectNodes.put(object.address(), index);
        active.remove(object.address());
        return index;
    }

    private void addArmature(BlendFile.View object) throws IOException {
        if (boneNodes.containsKey(object.address())) return;
        BlendFile.View armature = object.ref("data");
        require(armature != null && armature.type().equals("bArmature"), "Invalid armature data");
        Map<String, BlendFile.View> poses = new HashMap<>();
        if (object.ref("pose") != null) for (BlendFile.View pose : object.ref("pose").list("chanbase")) poses.put(pose.text("name"), pose);
        Map<String, Integer> indices = new LinkedHashMap<>();
        Map<String, Matrix4f> rest = new LinkedHashMap<>();
        Map<String, BlendFile.View> data = new LinkedHashMap<>();
        for (BlendFile.View bone : armature.list("bonebase")) {
            addBone(bone, objectNodes.get(object.address()), new Matrix4f(), poses, indices, rest, data, new HashSet<>(), 0);
        }
        boneNodes.put(object.address(), indices);
        restBones.put(object.address(), rest);
        boneData.put(object.address(), data);
    }

    private void addBone(BlendFile.View bone, int parent, Matrix4f parentRest, Map<String, BlendFile.View> poses,
                         Map<String, Integer> indices, Map<String, Matrix4f> rest, Map<String, BlendFile.View> data,
                         Set<Long> active, int depth) throws IOException {
        require(active.add(bone.address()), "Cyclic bone hierarchy");
        String name = bone.text("name");
        require(bone.integer("inherit_scale_mode") == 0 && (bone.integer("flag") & ((1 << 9) | (1 << 22))) == 0
            && bone.integer("segments") <= 1, name + ": unsupported bone inheritance or segmented deformation");
        Matrix4f global = new Matrix4f().set(bone.floats("arm_mat", 16));
        Matrix4f local = new Matrix4f(parentRest).invert().mul(global);
        int index = nodes.size();
        require(indices.put(name, index) == null, "Duplicate bone name " + name);
        rest.put(name, global);
        data.put(name, bone);
        nodes.add(new Node(name, parent, BlendAnimation.Transform.read(poses.get(name), local, false)));
        for (BlendFile.View child : bone.list("childbase")) addBone(child, index, global, poses, indices, rest, data, active, depth + 1);
        active.remove(bone.address());
    }

    private Matrix4f objectWorld(int node) throws IOException {
        Node value = nodes.get(node);
        return value.parent <= 0 ? value.transform.matrix() : objectWorld(value.parent).mul(value.transform.matrix());
    }

    private static boolean enabled(BlendFile.View modifier) throws IOException {
        return (modifier.embedded("modifier").integer("mode") & 2) != 0;
    }

    private void addMesh(BlendFile.View object) throws IOException {
        BlendFile.View mesh = object.ref("data");
        require(mesh != null && mesh.type().equals("Mesh"), "Missing mesh data");
        require(mesh.ptr("key") == 0, object.idName() + ": shape keys are unsupported");
        int nodeIndex = objectNodes.get(object.address());
        Node node = nodes.get(nodeIndex);
        Map<String, Integer> joints = Map.of();
        List<BlendGeometryModifier> modifiers = new ArrayList<>();
        boolean armatureSeen = false;
        for (BlendFile.View modifier : object.list("modifiers")) {
            if (!enabled(modifier)) continue;
            if (modifier.type().equals("NodesModifierData")) {
                require(!armatureSeen, object.idName() + ": geometry nodes after armature deformation are unsupported");
                BlendNodeGraph graph = new BlendNodeGraph(file, modifier.ref("node_group"), this::material);
                modifiers.add(new BlendGeometryNodes(graph.geometry(BlendNodeGraph.modifierDefaults(file, modifier))));
            } else if (modifier.type().equals("BevelModifierData")) {
                require(!armatureSeen, object.idName() + ": bevel after armature deformation is unsupported");
                modifiers.add(new BlendBevelModifier(modifier, object.idName()));
            } else if (modifier.type().equals("ArmatureModifierData")) {
                require(!armatureSeen && modifier.integer("deformflag") == 1 && modifier.text("defgrp_name").isEmpty(),
                    object.idName() + ": only one vertex-weight armature modifier is supported");
                armatureSeen = true;
                BlendFile.View armature = modifier.ref("object");
                joints = boneNodes.get(armature.address());
                int[] jointIndices = joints.values().stream().mapToInt(Integer::intValue).toArray();
                Matrix4f meshWorld = objectWorld(nodeIndex), armWorld = objectWorld(objectNodes.get(armature.address()));
                Matrix4f[] inverse = new Matrix4f[joints.size()];
                int k = 0;
                for (String name : joints.keySet()) inverse[k++] = new Matrix4f(armWorld).mul(restBones.get(armature.address()).get(name)).invert().mul(meshWorld);
                node.skin = skins.size();
                skins.add(new BlenderModelAsset.Skin(jointIndices, inverse));
            } else throw new IOException(object.idName() + ": unsupported modifier " + modifier.type());
        }
        int vertices = BlendFile.count(mesh.integer("totvert")), faces = BlendFile.count(mesh.integer("totpoly")), corners = BlendFile.count(mesh.integer("totloop"));
        Map<String, Attribute> attributes = attributes(mesh);
        Attribute positions = attributes.get("position"), cornerVertex = attributes.get(".corner_vert");
        require(vertices == 0 || positions != null && positions.type == 7 && positions.domain == 0, "Mesh position attribute is missing");
        require(corners == 0 || cornerVertex != null && cornerVertex.type == 3 && cornerVertex.domain == 3, "Mesh corner indices are missing");
        float[] xyz = new float[Math.multiplyExact(vertices, 3)];
        for (int i = 0; i < vertices; i++) for (int c = 0; c < 3; c++) xyz[i * 3 + c] = positions.floating(i, c);
        int[] indices = new int[corners];
        for (int i = 0; i < corners; i++) { indices[i] = cornerVertex.integer(i); require(indices[i] >= 0 && indices[i] < vertices, "Invalid corner vertex index"); }
        int[] offsets = new int[Math.addExact(faces, 1)];
        if (faces != 0) {
            ByteBuffer array = file.buffer(mesh.ptr("poly_offset_indices"), Math.multiplyExact(Math.addExact(faces, 1), 4));
            for (int i = 0; i <= faces; i++) offsets[i] = array.getInt();
        }
        require(offsets[0] == 0 && offsets[faces] == corners, "Invalid face offsets");
        for (int f = 0; f < faces; f++) require(offsets[f + 1] - offsets[f] >= 3 && offsets[f + 1] <= corners, "Invalid polygon");
        Attribute uv = null;
        for (Attribute value : attributes.values()) if (value.domain == 3 && value.type == 6) { uv = value; break; }
        float[] texCoords = new float[Math.multiplyExact(corners, 2)];
        for (int i = 0; uv != null && i < corners; i++) { texCoords[i * 2] = uv.floating(i, 0); texCoords[i * 2 + 1] = uv.floating(i, 1); }
        List<BlendFile.View> slots = file.pointers(mesh.ptr("mat"), mesh.integer("totcol"));
        List<BlendFile.View> overrides = file.pointers(object.ptr("mat"), object.integer("totcol"));
        ByteBuffer bits = overrides.isEmpty() ? null : file.buffer(object.ptr("matbits"), overrides.size());
        int[] slotMaterials = new int[Math.max(1, slots.size())];
        for (int i = 0; i < slotMaterials.length; i++) {
            BlendFile.View material = i < overrides.size() && bits.get(i) != 0 ? overrides.get(i) : i < slots.size() ? slots.get(i) : null;
            slotMaterials[i] = material(material);
        }
        int[] faceMaterials = new int[faces];
        boolean[] smooth = new boolean[faces];
        Attribute material = attributes.get("material_index"), sharp = attributes.get("sharp_face");
        for (int f = 0; f < faces; f++) {
            int slot = material == null ? 0 : material.integer(f);
            require(slot >= 0 && slot < slotMaterials.length, "Invalid material slot");
            faceMaterials[f] = slotMaterials[slot];
            smooth[f] = sharp == null || sharp.integer(f) == 0;
        }
        int[] weightJoints = joints.isEmpty() ? new int[0] : new int[Math.multiplyExact(vertices, 4)];
        float[] weights = new float[weightJoints.length];
        if (!joints.isEmpty()) readWeights(mesh, joints, weightJoints, weights, vertices);
        BlendGeometry.Mesh data = new BlendGeometry.Mesh(xyz, indices, offsets, texCoords, faceMaterials, smooth, weightJoints, weights);
        node.mesh = geometries.size();
        geometries.add(new BlendRuntime.Geometry(BlendGeometry.single(data), List.copyOf(modifiers)));
    }

    private void readWeights(BlendFile.View mesh, Map<String, Integer> joints, int[] indices, float[] weights, int vertices) throws IOException {
        List<String> jointNames = new ArrayList<>(joints.keySet());
        List<BlendFile.View> groups = mesh.list("vertex_group_names");
        BlendFile.View custom = mesh.embedded("vdata");
        for (BlendFile.View layer : file.array(custom.ptr("layers"), custom.integer("totlayer"))) {
            if (layer.integer("type") != 2) continue;
            List<BlendFile.View> deform = file.array(layer.ptr("data"), vertices);
            for (int v = 0; v < vertices; v++) {
                int n = 0;
                for (BlendFile.View weight : file.array(deform.get(v).ptr("dw"), deform.get(v).integer("totweight"))) {
                    int group = weight.integer("def_nr");
                    require(group >= 0 && group < groups.size(), "Invalid vertex group index");
                    int joint = jointNames.indexOf(groups.get(group).text("name"));
                    float amount = weight.scalar("weight");
                    if (joint < 0 || amount <= 0) continue;
                    require(n < 4, "More than four deforming bone weights on a vertex");
                    indices[v * 4 + n] = joint; weights[v * 4 + n++] = amount;
                }
            }
            return;
        }
    }

    private Map<String, Attribute> attributes(BlendFile.View mesh) throws IOException {
        Map<String, Attribute> result = new LinkedHashMap<>();
        BlendFile.View storage = mesh.embedded("attribute_storage");
        for (BlendFile.View value : file.array(storage.ptr("dna_attributes"), storage.integer("dna_attributes_num"))) {
            int type = value.integer("data_type"), domain = value.integer("domain"), kind = value.integer("storage_type");
            require(kind == 0 || kind == 1, "Unsupported attribute storage");
            BlendFile.View data = value.ref("data");
            require(data != null, "Missing attribute data");
            boolean single = kind == 1 || data.integer("is_single") != 0;
            int count = kind == 1 ? 1 : Math.toIntExact(data.number("size"));
            int stride = switch (type) { case 0, 1 -> 1; case 3, 5 -> 4; case 6 -> 8; case 7 -> 12; default -> 0; };
            if (stride == 0) continue;
            BlendFile.count(count);
            ByteBuffer buffer = count == 0 ? ByteBuffer.allocate(0) : file.buffer(data.ptr("data"), Math.multiplyExact(stride, single ? 1 : count));
            result.put(value.text("name"), new Attribute(type, domain, single, count, stride, buffer));
        }
        return result;
    }

    private record Attribute(int type, int domain, boolean single, int count, int stride, ByteBuffer data) {
        int offset(int index) throws IOException { require(index >= 0 && (single || index < count), "Attribute domain size mismatch"); return single ? 0 : Math.multiplyExact(index, stride); }
        float floating(int index, int component) throws IOException {
            float value = data.getFloat(offset(index) + component * 4);
            require(Float.isFinite(value), "Non-finite mesh attribute"); return value;
        }
        int integer(int index) throws IOException { return stride == 1 ? data.get(offset(index)) : data.getInt(offset(index)); }
    }

    private int material(BlendFile.View value) throws IOException {
        long key = value == null ? 0 : value.address();
        Integer existing = materialIndices.get(key);
        if (existing != null) return existing;
        int index = materials.size();
        BlendMaterialProgram program = null;
        float r = 1, g = 1, b = 1, a = 1;
        if (value != null) {
            require(value.ref("adt") == null, value.idName() + ": material animation is unsupported");
            if (value.ref("nodetree") != null) {
                program = BlendMaterialProgram.compile(new BlendNodeGraph(file, value.ref("nodetree")).material(),
                    new ResourceLocation(id.getNamespace(), "eca_blend/" + id.getPath() + "/material_" + index),
                    resources, source, this::image);
            } else { r = value.scalar("r"); g = value.scalar("g"); b = value.scalar("b"); a = value.scalar("a"); }
        }
        materials.add(new BlenderModelAsset.Material(r, g, b, a, null, true, program));
        materialIndices.put(key, index);
        return index;
    }

    private ResourceLocation image(BlendFile.View image) throws IOException {
        require(image != null, "Image Texture node has no image");
        ResourceLocation existing = imageLocations.get(image.address());
        if (existing != null) return existing;
        require(image.integer("source") == 1, image.idName() + ": only file images are supported");
        List<BlendFile.View> packed = image.list("packedfiles");
        byte[] bytes;
        if (!packed.isEmpty()) {
            require(packed.size() == 1, image.idName() + ": tiled or multi-view textures are unsupported");
            BlendFile.View payload = packed.get(0).ref("packedfile");
            int size = payload.integer("size");
            require(size > 0, "Empty packed texture");
            bytes = file.data(payload.ptr("data"), size);
        } else {
            String path = image.text("name").replace('\\', '/');
            if (path.startsWith("//")) path = path.substring(2);
            require(!path.startsWith("/") && !path.contains(":") && !path.contains(".."), "Texture must be packed or resource-relative: " + path);
            String folder = location.getPath().substring(0, location.getPath().lastIndexOf('/') + 1);
            try (InputStream stream = resources.getResourceOrThrow(new ResourceLocation(location.getNamespace(), folder + path)).open()) {
                bytes = stream.readAllBytes();
            }
        }
        ResourceLocation texture = new ResourceLocation(id.getNamespace(), "eca/blender_runtime/" + id.getPath() + "/blend_image_" + textures.size());
        textures.add(new BlenderModelAsset.TextureData(texture, bytes));
        imageLocations.put(image.address(), texture);
        return texture;
    }

    private static final class Node {
        final String name;
        final int parent;
        final BlendAnimation.Transform transform;
        int mesh = -1;
        int skin = -1;
        Node(String name, int parent, BlendAnimation.Transform transform) { this.name = name; this.parent = parent; this.transform = transform; }
    }
}
