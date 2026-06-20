package net.eca.util.shader_generator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Set;

public final class StandardShaderJsonGenerator {

    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create();
    private static final float[] IDENTITY_MATRIX_4 = {
        1.0F, 0.0F, 0.0F, 0.0F,
        0.0F, 1.0F, 0.0F, 0.0F,
        0.0F, 0.0F, 1.0F, 0.0F,
        0.0F, 0.0F, 0.0F, 1.0F
    };

    @Override
    public String generate(
        ShaderProject project,
        ShaderExportMode exportMode,
        ShaderTargetProfile targetProfile,
        String vertexProgram,
        String fragmentProgram
    ) {
        if (project == null || exportMode == null || targetProfile == null) {
            throw new IllegalArgumentException("Shader JSON generation arguments must not be null");
        }
        if (vertexProgram == null || vertexProgram.isBlank()) {
            throw new IllegalArgumentException("Vertex program id must not be blank");
        }
        if (fragmentProgram == null || fragmentProgram.isBlank()) {
            throw new IllegalArgumentException("Fragment program id must not be blank");
        }

        JsonObject root = new JsonObject();
        root.add("blend", createBlend());
        root.addProperty("vertex", vertexProgram);
        root.addProperty("fragment", fragmentProgram);
        root.add("attributes", createAttributes(targetProfile));
        root.add("samplers", createSamplers());
        root.add("uniforms", createUniforms(project, exportMode));
        return GSON.toJson(root) + "\n";
    }

    private static JsonObject createBlend() {
        JsonObject blend = new JsonObject();
        blend.addProperty("func", "add");
        blend.addProperty("srcrgb", "srcalpha");
        blend.addProperty("dstrgb", "1-srcalpha");
        return blend;
    }

    private static JsonArray createAttributes(ShaderTargetProfile targetProfile) {
        JsonArray attributes = new JsonArray();
        for (String attribute : targetProfile.attributes()) {
            attributes.add(attribute);
        }
        return attributes;
    }

    private static JsonArray createSamplers() {
        JsonArray samplers = new JsonArray();
        samplers.add(namedObject("Sampler0"));
        samplers.add(namedObject("Sampler2"));
        return samplers;
    }

    private static JsonArray createUniforms(ShaderProject project, ShaderExportMode exportMode) {
        JsonArray uniforms = new JsonArray();
        uniforms.add(uniform("ModelViewMat", "matrix4x4", IDENTITY_MATRIX_4));
        uniforms.add(uniform("ProjMat", "matrix4x4", IDENTITY_MATRIX_4));
        uniforms.add(uniform("ColorModulator", "float", 1.0F, 1.0F, 1.0F, 1.0F));
        uniforms.add(uniform("GameTime", "float", 0.0F));

        if (!exportMode.includesEcaUniforms()) {
            return uniforms;
        }

        Set<ShaderProject.Capability> capabilities = project.capabilities();
        if (capabilities.contains(ShaderProject.Capability.CAMERA_ORIENTATION)) {
            uniforms.add(uniform("CameraYaw", "float", 0.0F));
            uniforms.add(uniform("CameraPitch", "float", 0.0F));
        }
        if (capabilities.contains(ShaderProject.Capability.COLOR_KEY)) {
            uniforms.add(uniform("ColorKeyColor", "float", 0.0F, 0.0F, 0.0F, 0.0F));
            uniforms.add(uniform("ColorKeyTolerance", "float", 0.1F));
        }
        if (capabilities.contains(ShaderProject.Capability.LOCAL_UV_BOUNDS)) {
            uniforms.add(uniform("LocalUvMin", "float", 0.0F, 0.0F));
            uniforms.add(uniform("LocalUvScale", "float", 1.0F, 1.0F));
        }
        return uniforms;
    }

    private static JsonObject namedObject(String name) {
        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        return object;
    }

    private static JsonObject uniform(String name, String type, float... values) {
        JsonObject uniform = namedObject(name);
        uniform.addProperty("type", type);
        uniform.addProperty("count", values.length);
        JsonArray valueArray = new JsonArray();
        for (float value : values) {
            valueArray.add(value);
        }
        uniform.add("values", valueArray);
        return uniform;
    }
}
