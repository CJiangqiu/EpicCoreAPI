package net.eca.client.render.blender;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@SuppressWarnings("removal")
final class GltfModelLoader {
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int JSON_CHUNK = 0x4E4F534A;
    private static final int BIN_CHUNK = 0x004E4942;

    private final ResourceManager resources;
    private final ResourceLocation modelLocation;
    private final ResourceLocation modelId;
    private final BlenderModelDefinition definition;
    private JsonObject root;
    private ByteBuffer binary;

    private GltfModelLoader(ResourceManager resources, ResourceLocation modelLocation,
                            ResourceLocation modelId, BlenderModelDefinition definition) {
        this.resources = resources;
        this.modelLocation = modelLocation;
        this.modelId = modelId;
        this.definition = definition;
    }

    static BlenderModelAsset load(ResourceManager resources, ResourceLocation modelLocation,
                                  ResourceLocation modelId, BlenderModelDefinition definition) throws IOException {
        GltfModelLoader loader = new GltfModelLoader(resources, modelLocation, modelId, definition);
        loader.readContainer();
        return loader.build();
    }

    private void readContainer() throws IOException {
        Resource resource = resources.getResource(modelLocation)
            .orElseThrow(() -> new IOException("Missing model resource " + modelLocation));
        byte[] bytes;
        try (InputStream input = resource.open()) {
            bytes = input.readAllBytes();
        }
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (data.remaining() < 20 || data.getInt() != GLB_MAGIC) {
            throw new IOException("Resource is not a GLB file: " + modelLocation);
        }
        int version = data.getInt();
        int declaredLength = data.getInt();
        if (version != 2 || declaredLength != bytes.length) {
            throw new IOException("Unsupported or truncated GLB resource: " + modelLocation);
        }
        byte[] jsonBytes = null;
        byte[] binaryBytes = new byte[0];
        while (data.remaining() >= 8) {
            int length = data.getInt();
            int type = data.getInt();
            if (length < 0 || length > data.remaining()) {
                throw new IOException("Invalid GLB chunk length in " + modelLocation);
            }
            byte[] chunk = new byte[length];
            data.get(chunk);
            if (type == JSON_CHUNK) {
                jsonBytes = chunk;
            } else if (type == BIN_CHUNK) {
                binaryBytes = chunk;
            }
        }
        if (jsonBytes == null) {
            throw new IOException("GLB has no JSON chunk: " + modelLocation);
        }
        String json = new String(jsonBytes, StandardCharsets.UTF_8).trim();
        root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject asset = root.getAsJsonObject("asset");
        if (asset == null || !asset.has("version") || !asset.get("version").getAsString().startsWith("2")) {
            throw new IOException("Only glTF 2.0 is supported: " + modelLocation);
        }
        binary = ByteBuffer.wrap(binaryBytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private BlenderModelAsset build() throws IOException {
        List<BlenderModelAsset.TextureData> textures = new ArrayList<>();
        Map<Integer, ResourceLocation> textureLocations = readTextures(textures);
        List<BlenderModelAsset.Material> materials = readMaterials(textureLocations);
        List<BlenderModelAsset.Mesh> meshes = readMeshes();
        List<BlenderModelAsset.Node> nodes = readNodes();
        List<BlenderModelAsset.Skin> skins = readSkins(nodes.size());
        for (BlenderModelAsset.Node node : nodes) {
            if (node.skin() < -1 || node.skin() >= skins.size()) {
                throw new IOException("Node references an invalid skin in " + modelLocation);
            }
        }
        Map<String, BlenderModelAsset.Animation> animations = readAnimations();
        int sceneIndex = root.has("scene") ? root.get("scene").getAsInt() : 0;
        JsonArray scenes = array("scenes");
        if (scenes == null || sceneIndex < 0 || sceneIndex >= scenes.size()) {
            throw new IOException("GLB has no valid scene: " + modelLocation);
        }
        int[] roots = intArray(scenes.get(sceneIndex).getAsJsonObject().getAsJsonArray("nodes"));
        return new BlenderModelAsset(modelId, definition, nodes, meshes, skins, materials, animations,
            roots, textures);
    }

    private List<BlenderModelAsset.Node> readNodes() {
        List<BlenderModelAsset.Node> result = new ArrayList<>();
        JsonArray nodes = array("nodes");
        if (nodes == null) {
            return result;
        }
        for (JsonElement element : nodes) {
            JsonObject node = element.getAsJsonObject();
            String name = node.has("name") ? node.get("name").getAsString() : "";
            int mesh = node.has("mesh") ? node.get("mesh").getAsInt() : -1;
            int skin = node.has("skin") ? node.get("skin").getAsInt() : -1;
            int[] children = intArray(node.getAsJsonArray("children"));
            Vector3f translation = vector3(node.getAsJsonArray("translation"), new Vector3f());
            Quaternionf rotation = quaternion(node.getAsJsonArray("rotation"));
            Vector3f scale = vector3(node.getAsJsonArray("scale"), new Vector3f(1.0f));
            Matrix4f matrix = matrix(node.getAsJsonArray("matrix"));
            result.add(new BlenderModelAsset.Node(name, mesh, skin, children, translation, rotation, scale, matrix));
        }
        return result;
    }

    private List<BlenderModelAsset.Mesh> readMeshes() throws IOException {
        List<BlenderModelAsset.Mesh> result = new ArrayList<>();
        JsonArray meshes = array("meshes");
        if (meshes == null) {
            return result;
        }
        for (JsonElement meshElement : meshes) {
            List<BlenderModelAsset.Primitive> primitives = new ArrayList<>();
            for (JsonElement primitiveElement : meshElement.getAsJsonObject().getAsJsonArray("primitives")) {
                JsonObject primitive = primitiveElement.getAsJsonObject();
                int mode = primitive.has("mode") ? primitive.get("mode").getAsInt() : 4;
                if (mode != 4) {
                    throw new IOException("Only triangle primitives are supported in " + modelLocation);
                }
                JsonObject attributes = primitive.getAsJsonObject("attributes");
                if (attributes == null || !attributes.has("POSITION")) {
                    throw new IOException("Primitive has no POSITION attribute in " + modelLocation);
                }
                float[] positions = readFloatAccessor(attributes.get("POSITION").getAsInt());
                float[] normals = attributes.has("NORMAL")
                    ? readFloatAccessor(attributes.get("NORMAL").getAsInt()) : new float[positions.length];
                float[] texCoords = attributes.has("TEXCOORD_0")
                    ? readFloatAccessor(attributes.get("TEXCOORD_0").getAsInt())
                    : new float[positions.length / 3 * 2];
                int vertexCount = positions.length / 3;
                boolean hasJoints = attributes.has("JOINTS_0");
                boolean hasWeights = attributes.has("WEIGHTS_0");
                if (hasJoints != hasWeights) {
                    throw new IOException("Skinned primitive must contain both JOINTS_0 and WEIGHTS_0 in "
                        + modelLocation);
                }
                int[] joints = hasJoints
                    ? readJointAccessor(attributes.get("JOINTS_0").getAsInt()) : new int[0];
                int weightsAccessor = hasWeights ? attributes.get("WEIGHTS_0").getAsInt() : -1;
                if (hasWeights) {
                    validateWeightAccessor(weightsAccessor);
                }
                float[] weights = hasWeights ? readFloatAccessor(weightsAccessor) : new float[0];
                if (hasJoints && (joints.length != vertexCount * 4 || weights.length != vertexCount * 4)) {
                    throw new IOException("Skin attributes must contain four influences per vertex in "
                        + modelLocation);
                }
                int[] indices;
                if (primitive.has("indices")) {
                    indices = readIndexAccessor(primitive.get("indices").getAsInt());
                } else {
                    indices = new int[vertexCount];
                    for (int i = 0; i < vertexCount; i++) {
                        indices[i] = i;
                    }
                }
                int material = primitive.has("material") ? primitive.get("material").getAsInt() : -1;
                primitives.add(new BlenderModelAsset.Primitive(positions, normals, texCoords,
                    joints, weights, indices, material));
            }
            result.add(new BlenderModelAsset.Mesh(primitives));
        }
        return result;
    }

    private List<BlenderModelAsset.Skin> readSkins(int nodeCount) throws IOException {
        List<BlenderModelAsset.Skin> result = new ArrayList<>();
        JsonArray skins = array("skins");
        if (skins == null) {
            return result;
        }
        for (JsonElement element : skins) {
            JsonObject skin = element.getAsJsonObject();
            int[] joints = intArray(skin.getAsJsonArray("joints"));
            if (joints.length == 0) {
                throw new IOException("Skin has no joints in " + modelLocation);
            }
            for (int joint : joints) {
                if (joint < 0 || joint >= nodeCount) {
                    throw new IOException("Skin references an invalid joint node in " + modelLocation);
                }
            }
            Matrix4f[] inverseBindMatrices = new Matrix4f[joints.length];
            if (skin.has("inverseBindMatrices")) {
                int accessorIndex = skin.get("inverseBindMatrices").getAsInt();
                JsonObject accessor = accessor(accessorIndex);
                if (!"MAT4".equals(accessor.get("type").getAsString())
                    || accessor.get("componentType").getAsInt() != 5126) {
                    throw new IOException("Inverse bind matrices must use float MAT4 values in "
                        + modelLocation);
                }
                float[] values = readFloatAccessor(accessorIndex);
                if (values.length != joints.length * 16) {
                    throw new IOException("Inverse bind matrix count does not match skin joints in "
                        + modelLocation);
                }
                for (int i = 0; i < joints.length; i++) {
                    inverseBindMatrices[i] = new Matrix4f().set(
                        Arrays.copyOfRange(values, i * 16, i * 16 + 16));
                }
            } else {
                Arrays.setAll(inverseBindMatrices, ignored -> new Matrix4f());
            }
            result.add(new BlenderModelAsset.Skin(joints, inverseBindMatrices));
        }
        return result;
    }

    private List<BlenderModelAsset.Material> readMaterials(Map<Integer, ResourceLocation> textureLocations) {
        List<BlenderModelAsset.Material> result = new ArrayList<>();
        JsonArray materials = array("materials");
        if (materials == null) {
            return result;
        }
        for (JsonElement element : materials) {
            JsonObject material = element.getAsJsonObject();
            JsonObject pbr = material.getAsJsonObject("pbrMetallicRoughness");
            float[] color = {1.0f, 1.0f, 1.0f, 1.0f};
            ResourceLocation texture = null;
            if (pbr != null) {
                JsonArray factor = pbr.getAsJsonArray("baseColorFactor");
                if (factor != null && factor.size() == 4) {
                    for (int i = 0; i < 4; i++) {
                        color[i] = factor.get(i).getAsFloat();
                    }
                }
                JsonObject textureInfo = pbr.getAsJsonObject("baseColorTexture");
                if (textureInfo != null) {
                    texture = textureLocations.get(textureInfo.get("index").getAsInt());
                }
            }
            boolean translucent = "BLEND".equals(material.has("alphaMode")
                ? material.get("alphaMode").getAsString() : "OPAQUE") || color[3] < 1.0f;
            result.add(new BlenderModelAsset.Material(color[0], color[1], color[2], color[3], texture, translucent));
        }
        return result;
    }

    private Map<Integer, ResourceLocation> readTextures(List<BlenderModelAsset.TextureData> output) throws IOException {
        Map<Integer, ResourceLocation> result = new HashMap<>();
        JsonArray textures = array("textures");
        JsonArray images = array("images");
        if (textures == null || images == null) {
            return result;
        }
        Map<Integer, ResourceLocation> imagesByIndex = new HashMap<>();
        for (int i = 0; i < images.size(); i++) {
            JsonObject image = images.get(i).getAsJsonObject();
            byte[] bytes = readImage(image);
            ResourceLocation location = new ResourceLocation(modelId.getNamespace(),
                "eca/blender_runtime/" + modelId.getPath() + "/image_" + i);
            output.add(new BlenderModelAsset.TextureData(location, bytes));
            imagesByIndex.put(i, location);
        }
        for (int i = 0; i < textures.size(); i++) {
            JsonObject texture = textures.get(i).getAsJsonObject();
            if (texture.has("source")) {
                result.put(i, imagesByIndex.get(texture.get("source").getAsInt()));
            }
        }
        return result;
    }

    private byte[] readImage(JsonObject image) throws IOException {
        if (image.has("bufferView")) {
            return readBufferView(image.get("bufferView").getAsInt());
        }
        if (!image.has("uri")) {
            throw new IOException("Image has no source in " + modelLocation);
        }
        String uri = image.get("uri").getAsString();
        if (uri.startsWith("data:")) {
            int comma = uri.indexOf(',');
            if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) {
                throw new IOException("Only base64 data images are supported in " + modelLocation);
            }
            return Base64.getDecoder().decode(uri.substring(comma + 1));
        }
        if (uri.contains("..") || uri.startsWith("/") || uri.startsWith("\\")) {
            throw new IOException("Invalid image path in " + modelLocation);
        }
        String parent = modelLocation.getPath().substring(0, modelLocation.getPath().lastIndexOf('/') + 1);
        ResourceLocation imageLocation = new ResourceLocation(modelLocation.getNamespace(), parent + uri);
        Resource resource = resources.getResource(imageLocation)
            .orElseThrow(() -> new IOException("Missing image resource " + imageLocation));
        try (InputStream input = resource.open()) {
            return input.readAllBytes();
        }
    }

    private Map<String, BlenderModelAsset.Animation> readAnimations() throws IOException {
        Map<String, BlenderModelAsset.Animation> result = new HashMap<>();
        JsonArray animations = array("animations");
        if (animations == null) {
            return result;
        }
        for (int animationIndex = 0; animationIndex < animations.size(); animationIndex++) {
            JsonObject animation = animations.get(animationIndex).getAsJsonObject();
            String name = animation.has("name") ? animation.get("name").getAsString() : "animation_" + animationIndex;
            JsonArray samplers = animation.getAsJsonArray("samplers");
            List<BlenderModelAsset.Track> tracks = new ArrayList<>();
            float duration = 0.0f;
            for (JsonElement channelElement : animation.getAsJsonArray("channels")) {
                JsonObject channel = channelElement.getAsJsonObject();
                JsonObject target = channel.getAsJsonObject("target");
                if (!target.has("node")) {
                    continue;
                }
                String pathName = target.get("path").getAsString().toUpperCase(Locale.ROOT);
                BlenderModelAsset.Path path;
                try {
                    path = BlenderModelAsset.Path.valueOf(pathName);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                JsonObject sampler = samplers.get(channel.get("sampler").getAsInt()).getAsJsonObject();
                String interpolationName = sampler.has("interpolation")
                    ? sampler.get("interpolation").getAsString() : "LINEAR";
                if ("CUBICSPLINE".equals(interpolationName)) {
                    throw new IOException("Cubic spline animation is not supported in " + modelLocation);
                }
                BlenderModelAsset.Interpolation interpolation = "STEP".equals(interpolationName)
                    ? BlenderModelAsset.Interpolation.STEP : BlenderModelAsset.Interpolation.LINEAR;
                float[] times = readFloatAccessor(sampler.get("input").getAsInt());
                float[] values = readFloatAccessor(sampler.get("output").getAsInt());
                int components = path == BlenderModelAsset.Path.ROTATION ? 4 : 3;
                if (values.length != times.length * components) {
                    throw new IOException("Invalid animation output size in " + modelLocation);
                }
                if (times.length > 0) {
                    duration = Math.max(duration, times[times.length - 1]);
                }
                tracks.add(new BlenderModelAsset.Track(target.get("node").getAsInt(), path,
                    interpolation, times, values));
            }
            result.put(name, new BlenderModelAsset.Animation(name, duration, List.copyOf(tracks)));
        }
        return result;
    }

    private float[] readFloatAccessor(int accessorIndex) throws IOException {
        JsonObject accessor = array("accessors").get(accessorIndex).getAsJsonObject();
        if (accessor.has("sparse")) {
            throw new IOException("Sparse accessors are not supported in " + modelLocation);
        }
        int componentCount = componentCount(accessor.get("type").getAsString());
        int count = accessor.get("count").getAsInt();
        int componentType = accessor.get("componentType").getAsInt();
        boolean normalized = accessor.has("normalized") && accessor.get("normalized").getAsBoolean();
        AccessorView view = accessorView(accessor, componentCount, componentType);
        float[] values = new float[count * componentCount];
        for (int i = 0; i < count; i++) {
            int base = view.offset + i * view.stride;
            for (int component = 0; component < componentCount; component++) {
                int offset = base + component * componentSize(componentType);
                values[i * componentCount + component] = readFloatComponent(offset, componentType, normalized);
            }
        }
        return values;
    }

    private int[] readIndexAccessor(int accessorIndex) throws IOException {
        JsonObject accessor = array("accessors").get(accessorIndex).getAsJsonObject();
        int count = accessor.get("count").getAsInt();
        int componentType = accessor.get("componentType").getAsInt();
        AccessorView view = accessorView(accessor, 1, componentType);
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            int offset = view.offset + i * view.stride;
            values[i] = switch (componentType) {
                case 5121 -> Byte.toUnsignedInt(binary.get(offset));
                case 5123 -> Short.toUnsignedInt(binary.getShort(offset));
                case 5125 -> binary.getInt(offset);
                default -> throw new IOException("Unsupported index component type " + componentType);
            };
        }
        return values;
    }

    private int[] readJointAccessor(int accessorIndex) throws IOException {
        JsonObject accessor = accessor(accessorIndex);
        if (accessor.has("sparse") || !"VEC4".equals(accessor.get("type").getAsString())) {
            throw new IOException("JOINTS_0 must be a non-sparse VEC4 accessor in " + modelLocation);
        }
        int count = accessor.get("count").getAsInt();
        int componentType = accessor.get("componentType").getAsInt();
        if (componentType != 5121 && componentType != 5123) {
            throw new IOException("JOINTS_0 must use unsigned byte or unsigned short components in "
                + modelLocation);
        }
        if (accessor.has("normalized") && accessor.get("normalized").getAsBoolean()) {
            throw new IOException("JOINTS_0 cannot use normalized components in " + modelLocation);
        }
        AccessorView view = accessorView(accessor, 4, componentType);
        int[] values = new int[count * 4];
        for (int i = 0; i < count; i++) {
            int base = view.offset + i * view.stride;
            for (int component = 0; component < 4; component++) {
                int offset = base + component * componentSize(componentType);
                values[i * 4 + component] = componentType == 5121
                    ? Byte.toUnsignedInt(binary.get(offset))
                    : Short.toUnsignedInt(binary.getShort(offset));
            }
        }
        return values;
    }

    private void validateWeightAccessor(int accessorIndex) throws IOException {
        JsonObject accessor = accessor(accessorIndex);
        if (!"VEC4".equals(accessor.get("type").getAsString())) {
            throw new IOException("WEIGHTS_0 must be a VEC4 accessor in " + modelLocation);
        }
        int componentType = accessor.get("componentType").getAsInt();
        boolean normalized = accessor.has("normalized") && accessor.get("normalized").getAsBoolean();
        if (componentType != 5126 && !((componentType == 5121 || componentType == 5123) && normalized)) {
            throw new IOException("WEIGHTS_0 must use float or normalized unsigned components in "
                + modelLocation);
        }
    }

    private JsonObject accessor(int accessorIndex) {
        return array("accessors").get(accessorIndex).getAsJsonObject();
    }

    private AccessorView accessorView(JsonObject accessor, int componentCount, int componentType) throws IOException {
        if (!accessor.has("bufferView")) {
            throw new IOException("Accessor without bufferView in " + modelLocation);
        }
        JsonObject view = array("bufferViews").get(accessor.get("bufferView").getAsInt()).getAsJsonObject();
        int offset = (view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0)
            + (accessor.has("byteOffset") ? accessor.get("byteOffset").getAsInt() : 0);
        int packedStride = componentCount * componentSize(componentType);
        int stride = view.has("byteStride") ? view.get("byteStride").getAsInt() : packedStride;
        return new AccessorView(offset, stride);
    }

    private byte[] readBufferView(int viewIndex) throws IOException {
        JsonObject view = array("bufferViews").get(viewIndex).getAsJsonObject();
        int offset = view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0;
        int length = view.get("byteLength").getAsInt();
        if (offset < 0 || length < 0 || offset + length > binary.capacity()) {
            throw new IOException("Invalid bufferView in " + modelLocation);
        }
        byte[] bytes = new byte[length];
        ByteBuffer copy = binary.duplicate();
        copy.position(offset);
        copy.get(bytes);
        return bytes;
    }

    private float readFloatComponent(int offset, int type, boolean normalized) throws IOException {
        return switch (type) {
            case 5120 -> normalized ? Math.max(binary.get(offset) / 127.0f, -1.0f) : binary.get(offset);
            case 5121 -> normalized ? Byte.toUnsignedInt(binary.get(offset)) / 255.0f : Byte.toUnsignedInt(binary.get(offset));
            case 5122 -> normalized ? Math.max(binary.getShort(offset) / 32767.0f, -1.0f) : binary.getShort(offset);
            case 5123 -> normalized ? Short.toUnsignedInt(binary.getShort(offset)) / 65535.0f : Short.toUnsignedInt(binary.getShort(offset));
            case 5125 -> normalized ? Integer.toUnsignedLong(binary.getInt(offset)) / 4294967295.0f : binary.getInt(offset);
            case 5126 -> binary.getFloat(offset);
            default -> throw new IOException("Unsupported accessor component type " + type);
        };
    }

    private static int componentSize(int type) throws IOException {
        return switch (type) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            case 5125, 5126 -> 4;
            default -> throw new IOException("Unsupported accessor component type " + type);
        };
    }

    private static int componentCount(String type) throws IOException {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT4" -> 16;
            default -> throw new IOException("Unsupported accessor type " + type);
        };
    }

    private JsonArray array(String name) {
        return root.getAsJsonArray(name);
    }

    private static int[] intArray(JsonArray array) {
        if (array == null) {
            return new int[0];
        }
        int[] values = new int[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsInt();
        }
        return values;
    }

    private static Vector3f vector3(JsonArray array, Vector3f fallback) {
        return array == null ? fallback : new Vector3f(array.get(0).getAsFloat(),
            array.get(1).getAsFloat(), array.get(2).getAsFloat());
    }

    private static Quaternionf quaternion(JsonArray array) {
        return array == null ? new Quaternionf() : new Quaternionf(array.get(0).getAsFloat(),
            array.get(1).getAsFloat(), array.get(2).getAsFloat(), array.get(3).getAsFloat());
    }

    private static Matrix4f matrix(JsonArray array) {
        if (array == null) {
            return null;
        }
        float[] values = new float[16];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsFloat();
        }
        return new Matrix4f().set(values);
    }

    private record AccessorView(int offset, int stride) {
    }
}
