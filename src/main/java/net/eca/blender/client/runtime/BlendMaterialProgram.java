package net.eca.blender.client.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.eca.blender.client.runtime.BlendNodeGraph.Expr;
import net.eca.blender.client.runtime.BlendNodeGraph.Input;
import net.eca.blender.client.runtime.BlendNodeGraph.Value;
import net.eca.client.render.shader.EcaShaderInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static net.eca.blender.client.runtime.BlendFile.require;

@SuppressWarnings("removal")
public final class BlendMaterialProgram implements AutoCloseable {
    interface ImageResolver { ResourceLocation resolve(BlendFile.View image) throws IOException; }
    private final ResourceLocation id;
    private final ResourceProvider provider;
    private final List<ResourceLocation> textures;
    private final List<BlendTimeDriver> drivers;
    private EcaShaderInstance shader;
    private RenderType type;
    private MultiBufferSource.BufferSource buffers;

    private BlendMaterialProgram(ResourceLocation id, ResourceProvider provider, List<ResourceLocation> textures,
                                 List<BlendTimeDriver> drivers) {
        this.id = id; this.provider = provider; this.textures = List.copyOf(textures);
        this.drivers = List.copyOf(drivers);
    }

    static BlendMaterialProgram compile(Expr root, ResourceLocation id, ResourceProvider fallback,
                                         Resource original, ImageResolver images) throws IOException {
        Compiler compiler = new Compiler(images);
        String result = compiler.surface(root);
        String library;
        try (InputStream stream = fallback.getResourceOrThrow(new ResourceLocation("eca", "shaders/include/blend_nodes.glsl")).open()) {
            library = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder samplers = new StringBuilder();
        for (int i = 0; i < compiler.textures.size(); i++) samplers.append("uniform sampler2D Image").append(i).append(";\n");
        for (int i = 0; i < compiler.drivers.size(); i++) samplers.append("uniform float EcaDriver").append(i).append(";\n");
        String fragment = """
            #version 150
            #moj_import <fog.glsl>
            uniform vec4 ColorModulator;
            uniform float FogStart;
            uniform float FogEnd;
            uniform vec4 FogColor;
            uniform mat4 ModelViewMat;
            uniform vec3 Light0_Direction;
            uniform vec3 Light1_Direction;
            in float vertexDistance;
            in vec4 vertexColor;
            in vec4 lightColor;
            in vec4 overlayColor;
            in vec2 texCoord0;
            in vec3 localPosition;
            in vec3 generatedPosition;
            in vec3 viewPosition;
            in vec3 viewNormal;
            in vec3 lightMapColor;
            out vec4 fragColor;
            """ + samplers + library + "\n" + compiler.functions + "\nvoid main() {\n" + compiler.body
            + "vec4 color = " + result + " * vertexColor * ColorModulator;\n"
            + "if (color.a < 0.003) discard;\ncolor.rgb = blend_to_srgb(max(color.rgb, vec3(0.0)));\n"
            + "color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);\n"
            + "fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);\n}\n";
        String vertex = """
            #version 150
            #moj_import <fog.glsl>
            #moj_import <light.glsl>
            in vec3 Position;
            in vec4 Color;
            in vec2 UV0;
            in ivec2 UV1;
            in ivec2 UV2;
            in vec3 Normal;
            uniform sampler2D Sampler1;
            uniform sampler2D Sampler2;
            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            uniform mat4 EcaNodeInverse;
            uniform vec3 EcaBoundsMin;
            uniform vec3 EcaBoundsSize;
            uniform vec3 Light0_Direction;
            uniform vec3 Light1_Direction;
            uniform int FogShape;
            out float vertexDistance;
            out vec4 vertexColor;
            out vec4 lightColor;
            out vec4 overlayColor;
            out vec2 texCoord0;
            out vec3 localPosition;
            out vec3 generatedPosition;
            out vec3 viewPosition;
            out vec3 viewNormal;
            out vec3 lightMapColor;
            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                viewPosition = (ModelViewMat * vec4(Position, 1.0)).xyz;
                viewNormal = transpose(inverse(mat3(ModelViewMat))) * Normal;
                lightMapColor = texelFetch(Sampler2, UV2 / 16, 0).rgb;
                vertexDistance = fog_distance(ModelViewMat, Position, FogShape);
                vertexColor = Color;
                lightColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, vec4(1.0))
                    * texelFetch(Sampler2, UV2 / 16, 0);
                overlayColor = texelFetch(Sampler1, UV1, 0);
                texCoord0 = vec2(UV0.x, 1.0 - UV0.y);
                localPosition = (EcaNodeInverse * vec4(Position, 1.0)).xyz;
                generatedPosition = (localPosition - EcaBoundsMin) / max(EcaBoundsSize, vec3(0.000001));
            }
            """;
        Map<ResourceLocation, byte[]> files = new HashMap<>();
        files.put(shaderPath(id, ".vsh"), vertex.getBytes(StandardCharsets.UTF_8));
        files.put(shaderPath(id, ".fsh"), fragment.getBytes(StandardCharsets.UTF_8));
        files.put(shaderPath(id, ".json"), json(id, compiler.textures.size(), compiler.drivers.size()).getBytes(StandardCharsets.UTF_8));
        ResourceProvider provider = location -> {
            byte[] bytes = files.get(location);
            return bytes == null ? fallback.getResource(location) : Optional.of(new Resource(original.source(), () -> new ByteArrayInputStream(bytes)));
        };
        return new BlendMaterialProgram(id, provider, compiler.textures, compiler.drivers);
    }

    private static ResourceLocation shaderPath(ResourceLocation id, String extension) {
        return new ResourceLocation(id.getNamespace(), "shaders/core/" + id.getPath() + extension);
    }

    public void load() throws IOException {
        shader = new EcaShaderInstance(provider, id, DefaultVertexFormat.NEW_ENTITY);
        int activeImages = 0;
        for (int i = 0; i < textures.size(); i++) {
            if (GL20.glGetUniformLocation(shader.getId(), "Image" + i) >= 0) activeImages++;
        }
        int activeSamplers = activeImages;
        if (GL20.glGetUniformLocation(shader.getId(), "Sampler1") >= 0) activeSamplers++;
        if (GL20.glGetUniformLocation(shader.getId(), "Sampler2") >= 0) activeSamplers++;
        // Minecraft 1.20.1 tracks twelve texture units in GlStateManager.
        int bindingSlots = Math.min(12, GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS));
        if (activeSamplers > bindingSlots || activeImages > GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS)) {
            shader.close();
            shader = null;
            throw new IOException("Material requires more texture units than the renderer or GPU supports");
        }
        type = RenderType.create("eca_blend_" + id, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true, true,
            RenderType.CompositeState.builder().setShaderState(new RenderStateShard.ShaderStateShard(() -> shader))
                .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY).setCullState(RenderType.NO_CULL)
                .setLightmapState(RenderType.LIGHTMAP).setOverlayState(RenderType.OVERLAY)
                .setDepthTestState(RenderType.LEQUAL_DEPTH_TEST).setWriteMaskState(RenderType.COLOR_DEPTH_WRITE)
                .createCompositeState(true));
        buffers = MultiBufferSource.immediate(new BufferBuilder(256));
    }

    public VertexConsumer begin(Matrix4f pose, float[] positions, float frame) throws IOException {
        require(shader != null, "Blend material is not loaded");
        for (int i = 0; i < drivers.size(); i++) shader.safeGetUniform("EcaDriver" + i).set(drivers.get(i).evaluate(frame));
        Matrix4f inverse = new Matrix4f(pose).invert();
        require(inverse.isFinite(), "Singular material transform");
        shader.safeGetUniform("EcaNodeInverse").set(inverse);
        float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int i = 0; i < positions.length; i++) { min[i % 3] = Math.min(min[i % 3], positions[i]); max[i % 3] = Math.max(max[i % 3], positions[i]); }
        if (positions.length == 0) { min = new float[3]; max = new float[3]; }
        shader.safeGetUniform("EcaBoundsMin").set(min[0], min[1], min[2]);
        shader.safeGetUniform("EcaBoundsSize").set(max[0] - min[0], max[1] - min[1], max[2] - min[2]);
        for (int i = 0; i < textures.size(); i++) shader.setSampler("Image" + i, Minecraft.getInstance().getTextureManager().getTexture(textures.get(i)));
        return buffers.getBuffer(type);
    }

    public void end() { buffers.endBatch(type); }

    @Override
    public void close() {
        if (shader != null) shader.close();
        shader = null;
        type = null;
        buffers = null;
    }

    private static String json(ResourceLocation id, int imageCount, int driverCount) {
        JsonObject root = new JsonObject();
        root.addProperty("vertex", id.toString()); root.addProperty("fragment", id.toString());
        JsonArray attributes = new JsonArray();
        for (String name : List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal")) attributes.add(name);
        root.add("attributes", attributes);
        JsonArray samplers = new JsonArray();
        List<String> names = new ArrayList<>(List.of("Sampler1", "Sampler2"));
        for (int i = 0; i < imageCount; i++) names.add("Image" + i);
        for (String name : names) { JsonObject sampler = new JsonObject(); sampler.addProperty("name", name); samplers.add(sampler); }
        root.add("samplers", samplers);
        JsonArray uniforms = new JsonArray();
        for (int i = 0; i < driverCount; i++) uniform(uniforms, "EcaDriver" + i, "float", 1, false);
        for (String name : List.of("ModelViewMat", "ProjMat", "EcaNodeInverse")) uniform(uniforms, name, "matrix4x4", 16, true);
        for (String name : List.of("Light0_Direction", "Light1_Direction", "EcaBoundsMin", "EcaBoundsSize")) uniform(uniforms, name, "float", 3, false);
        uniform(uniforms, "ColorModulator", "float", 4, true);
        uniform(uniforms, "FogColor", "float", 4, false);
        uniform(uniforms, "FogStart", "float", 1, false); uniform(uniforms, "FogEnd", "float", 1, true);
        uniform(uniforms, "FogShape", "int", 1, false);
        root.add("uniforms", uniforms);
        return root.toString();
    }

    private static void uniform(JsonArray output, String name, String type, int count, boolean identity) {
        JsonObject uniform = new JsonObject();
        uniform.addProperty("name", name); uniform.addProperty("type", type); uniform.addProperty("count", count);
        JsonArray values = new JsonArray();
        for (int i = 0; i < count; i++) values.add(identity && (count != 16 || i % 5 == 0) ? 1 : 0);
        uniform.add("values", values); output.add(uniform);
    }

    private static final class Compiler {
        final ImageResolver images;
        final List<ResourceLocation> textures = new ArrayList<>();
        final List<BlendTimeDriver> drivers = new ArrayList<>();
        final Map<Expr, String> expressions = new IdentityHashMap<>();
        final Map<Expr, String> surfaces = new IdentityHashMap<>();
        final StringBuilder functions = new StringBuilder();
        final StringBuilder body = new StringBuilder();

        Compiler(ImageResolver images) { this.images = images; }

        String surface(Expr e) throws IOException {
            String cached = surfaces.get(e);
            if (cached != null) return cached;
            String value = switch (e.operation()) {
                case "ShaderNodeEmission" -> "vec4(" + vector(e, "Color") + " * " + scalar(e, "Strength") + ", 1.0)";
                case "ShaderNodeBsdfDiffuse" -> {
                    rejectLinkedExcept(e, Set.of("Color"));
                    yield "vec4(" + vector(e, "Color") + " * lightColor.rgb, 1.0)";
                }
                case "ShaderNodeBsdfPrincipled" -> {
                    rejectLinkedExcept(e, Set.of("Base Color", "Alpha", "Emission Color", "Emission Strength", "Metallic", "Roughness"));
                    for (String name : List.of("Subsurface Weight", "Transmission Weight", "Coat Weight", "Sheen Weight")) {
                        if (e.has(name)) require(e.input(name).value().x() == 0, e.label() + ": unsupported surface component " + name);
                    }
                    yield "vec4(blend_surface(" + vector(e, "Base Color") + "," + scalar(e, "Metallic") + "," + scalar(e, "Roughness")
                        + ",viewNormal * (gl_FrontFacing ? 1.0 : -1.0),-viewPosition,mat3(ModelViewMat)*Light0_Direction,mat3(ModelViewMat)*Light1_Direction,lightMapColor) + "
                        + vector(e, "Emission Color") + " * " + scalar(e, "Emission Strength") + ", " + scalar(e, "Alpha") + ")";
                }
                case "ShaderNodeMixShader" -> "mix(" + surface(e.input("Shader")) + ", " + surface(e.input("Shader_001"))
                    + ", clamp(" + scalar(e, "Fac") + ", 0.0, 1.0))";
                case "ShaderNodeAddShader" -> "(" + surface(e.input("Shader")) + " + " + surface(e.input("Shader_001")) + ")";
                default -> throw e.unsupported("Unsupported surface shader");
            };
            String name = "surface" + surfaces.size();
            surfaces.put(e, name);
            body.append("vec4 ").append(name).append(" = ").append(value).append(";\n");
            return name;
        }

        private void rejectLinkedExcept(Expr e, Set<String> allowed) throws IOException {
            for (Input in : e.inputs()) require(allowed.contains(in.id()) || !in.linked(),
                e.label() + ": unsupported connected surface input " + in.id());
        }

        String expression(Expr e) throws IOException {
            String existing = expressions.get(e);
            if (existing != null) return existing;
            String value;
            switch (e.operation()) {
                case "Constant", "ShaderNodeValue", "ShaderNodeRGB" -> value = literal(e.value());
                case "TimeDriver" -> {
                    BlendTimeDriver driver = new BlendTimeDriver(e.value().x());
                    int index = drivers.indexOf(driver);
                    if (index < 0) {
                        index = drivers.size();
                        drivers.add(driver);
                    }
                    value = "vec4(vec3(EcaDriver" + index + "),1.0)";
                }
                case "ShaderNodeTexCoord" -> {
                    require(e.data().ptr("id") == 0, e.label() + ": texture coordinate object references are unsupported");
                    value = switch (e.output()) {
                        case "UV" -> "vec4(texCoord0, 0.0, 1.0)";
                        case "Generated" -> "vec4(generatedPosition, 1.0)";
                        case "Object" -> "vec4(localPosition, 1.0)";
                        default -> throw e.unsupported("Unsupported coordinate output " + e.output());
                    };
                }
                case "ShaderNodeTexImage" -> value = image(e);
                case "ShaderNodeCombineXYZ" -> value = "vec4(" + scalar(e, "X") + "," + scalar(e, "Y") + "," + scalar(e, "Z") + ",1.0)";
                case "ShaderNodeSeparateXYZ" -> value = "vec4(" + vector(e, "Vector") + "." + e.output().toLowerCase(Locale.ROOT) + ")";
                case "ShaderNodeMath" -> {
                    int op = e.option();
                    BlendGeometryNodes.math(op, 1, 1, 1);
                    String result = "blend_math(" + op + "," + scalar(e, "Value") + "," + optionalScalar(e, "Value_001", "0.0") + "," + optionalScalar(e, "Value_002", "0.0") + ")";
                    if ((e.data().integer("custom2") & 1) != 0) result = "clamp(" + result + ",0.0,1.0)";
                    value = "vec4(" + result + ")";
                }
                case "ShaderNodeVectorMath" -> value = vectorMath(e);
                case "ShaderNodeMapping" -> {
                    int kind = e.option();
                    require(kind == 0 || kind == 2, e.label() + ": supported mapping types are Point and Vector");
                    value = "vec4(blend_rotate(" + vector(e, "Vector") + " * " + vector(e, "Scale") + "," + vector(e, "Rotation")
                        + ")" + (kind == 0 ? " + " + vector(e, "Location") : "") + ",1.0)";
                }
                case "ShaderNodeMixRGB" -> {
                    require(e.option() == 0, e.label() + ": only Mix color blending is supported");
                    value = "mix(" + expression(e.input("Color1")) + "," + expression(e.input("Color2")) + ",clamp(" + scalar(e, "Fac") + ",0.0,1.0))";
                    if ((e.data().integer("custom2") & 2) != 0) value = "clamp(" + value + ",0.0,1.0)";
                }
                case "ShaderNodeMix" -> {
                    BlendFile.View storage = e.storage();
                    require(storage.integer("blend_type") == 0 && storage.integer("factor_mode") == 0, e.label() + ": only uniform Mix is supported");
                    require(storage.integer("data_type") >= 0 && storage.integer("data_type") <= 2, e.label() + ": unsupported Mix data type");
                    String a = null, b = null, factor = null;
                    for (Input in : e.inputs()) {
                        if (in.id().startsWith("A_")) a = expression(in.first());
                        else if (in.id().startsWith("B_")) b = expression(in.first());
                        else if (in.id().equals("Factor_Float")) factor = "(" + expression(in.first()) + ").x";
                    }
                    require(a != null && b != null && factor != null, e.label() + ": missing Mix inputs");
                    if (storage.integer("clamp_factor") != 0) factor = "clamp(" + factor + ",0.0,1.0)";
                    value = "mix(" + a + "," + b + "," + factor + ")";
                    if (storage.integer("clamp_result") != 0) value = "clamp(" + value + ",0.0,1.0)";
                }
                case "ShaderNodeValToRGB" -> value = ramp(e);
                case "ShaderNodeTexGradient" -> {
                    int kind = e.storage().integer("gradient_type");
                    require(kind >= 0 && kind <= 6, e.label() + ": invalid gradient type");
                    value = "vec4(vec3(blend_gradient(" + coordinates(e, false) + "," + kind + ")),1.0)";
                }
                case "ShaderNodeTexNoise" -> {
                    BlendFile.View storage = e.storage();
                    int dimensions = storage.integer("dimensions");
                    require((dimensions == 3 || dimensions == 4) && storage.integer("type") == 1,
                        e.label() + ": only 3D/4D fBM Noise is supported");
                    String point = dimensions == 4 ? "vec4(" + coordinates(e, false) + "," + scalar(e, "W") + ")" : coordinates(e, false);
                    value = "blend_noise(" + point + " * " + scalar(e, "Scale") + "," + scalar(e, "Detail") + ","
                        + scalar(e, "Roughness") + "," + scalar(e, "Lacunarity") + "," + scalar(e, "Distortion") + ","
                        + (storage.integer("normalize") != 0 ? "true" : "false") + ")";
                    if (e.output().equals("Fac")) value = "vec4((" + value + ").x)";
                }
                default -> throw e.unsupported("Unsupported material node");
            }
            String name = "n" + expressions.size();
            expressions.put(e, name);
            body.append("vec4 ").append(name).append(" = ").append(value).append(";\n");
            return name;
        }

        private String coordinates(Expr e, boolean image) throws IOException {
            return !e.socket("Vector").linked() ? image ? "vec3(texCoord0,0.0)" : "generatedPosition" : vector(e, "Vector");
        }

        private String image(Expr e) throws IOException {
            BlendFile.View storage = e.storage();
            require(storage.integer("projection") == 0, e.label() + ": only flat image projection is supported");
            int interpolation = storage.integer("interpolation"), extension = storage.integer("extension");
            require(interpolation == 0 || interpolation == 1, e.label() + ": only linear/closest image interpolation is supported");
            require(extension == 0 || extension == 1 || extension == 2, e.label() + ": unsupported image extension");
            BlendFile.View image = e.data().ref("id");
            ResourceLocation texture = images.resolve(image);
            int index = textures.indexOf(texture);
            if (index < 0) { index = textures.size(); textures.add(texture); }
            String colorSpace = image.embedded("colorspace_settings").text("name");
            require(Set.of("sRGB", "Non-Color", "Linear", "Linear Rec.709").contains(colorSpace), e.label() + ": unsupported image color space " + colorSpace);
            String sample = "blend_image(Image" + index + ",(" + coordinates(e, true) + ").xy," + interpolation + "," + extension + ")";
            if (e.output().equals("Alpha")) return "vec4((" + sample + ").a)";
            return colorSpace.equals("sRGB") ? "vec4(blend_to_linear((" + sample + ").rgb),(" + sample + ").a)" : sample;
        }

        private String ramp(Expr e) throws IOException {
            BlendFile.View ramp = e.storage();
            int count = ramp.integer("tot"), mode = ramp.integer("ipotype");
            require(count >= 1 && ramp.integer("color_mode") == 0 && (mode == 0 || mode == 1 || mode == 4),
                e.label() + ": supported Color Ramp modes are RGB Linear, Ease and Constant");
            String name = "ramp" + functions.length();
            functions.append("vec4 ").append(name).append("(float f) {\n");
            BlendFile.View first = ramp.embedded("data", 0);
            functions.append("if(f <= ").append(number(first.scalar("pos"))).append(") return ").append(color(first)).append(";\n");
            for (int i = 1; i < count; i++) {
                BlendFile.View a = ramp.embedded("data", i - 1), b = ramp.embedded("data", i);
                float low = a.scalar("pos"), high = b.scalar("pos");
                require(high >= low, "Unsorted Color Ramp");
                String factor = high == low ? "1.0" : "clamp((f-" + number(low) + ")/" + number(high - low) + ",0.0,1.0)";
                if (mode == 1) factor = "smoothstep(0.0,1.0," + factor + ")";
                String value = mode == 4 ? color(a) : "mix(" + color(a) + "," + color(b) + "," + factor + ")";
                functions.append("if(f < ").append(number(high)).append(") return ").append(value).append(";\n");
            }
            functions.append("return ").append(color(ramp.embedded("data", count - 1))).append(";\n}\n");
            String value = name + "(" + scalar(e, "Fac") + ")";
            return e.output().equals("Alpha") ? "vec4((" + value + ").a)" : value;
        }

        private String vectorMath(Expr e) throws IOException {
            String a = vector(e, "Vector"), b = e.has("Vector_001") ? vector(e, "Vector_001") : "vec3(0.0)";
            String value = switch (e.option()) {
                case 0 -> a + "+" + b; case 1 -> a + "-" + b; case 2 -> a + "*" + b;
                case 3 -> "blend_div(" + a + "," + b + ")"; case 4 -> "cross(" + a + "," + b + ")";
                case 7 -> "vec3(dot(" + a + "," + b + "))"; case 8 -> "vec3(distance(" + a + "," + b + "))";
                case 9 -> "vec3(length(" + a + "))"; case 10 -> a + "*" + scalar(e, "Scale");
                case 11 -> "blend_normalize(" + a + ")"; case 13 -> "floor(" + a + ")"; case 14 -> "ceil(" + a + ")";
                case 17 -> "abs(" + a + ")"; case 18 -> "min(" + a + "," + b + ")"; case 19 -> "max(" + a + "," + b + ")";
                default -> throw e.unsupported("Unsupported Vector Math operation " + e.option());
            };
            return "vec4(" + value + ",1.0)";
        }

        private String scalar(Expr e, String input) throws IOException {
            Expr source = e.input(input);
            String value = expression(source);
            if (source.outputType().equals("NodeSocketVector")) return "dot(" + value + ".xyz,vec3(0.3333333333))";
            if (source.outputType().equals("NodeSocketColor")) return "dot(" + value + ".rgb,vec3(0.2126,0.7152,0.0722))";
            return value + ".x";
        }
        private String optionalScalar(Expr e, String input, String fallback) throws IOException { return e.has(input) ? scalar(e, input) : fallback; }
        private String vector(Expr e, String input) throws IOException { return expression(e.input(input)) + ".xyz"; }
        private static String color(BlendFile.View value) throws IOException { return literal(new Value(value.scalar("r"), value.scalar("g"), value.scalar("b"), value.scalar("a"))); }
        private static String literal(Value value) throws IOException { return "vec4(" + number(value.x()) + "," + number(value.y()) + "," + number(value.z()) + "," + number(value.w()) + ")"; }
        private static String number(float value) throws IOException { require(Float.isFinite(value), "Non-finite shader constant"); return Float.toString(value); }
    }
}
