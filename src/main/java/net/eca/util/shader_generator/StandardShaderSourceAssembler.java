package net.eca.util.shader_generator;

import java.util.Set;
import java.util.List;

public final class StandardShaderSourceAssembler {

    private static final String VERSION = "#version 150\n\n";

    public String assembleVertex(
        ShaderProject project,
        ShaderExportMode exportMode,
        ShaderTargetProfile targetProfile
    ) {
        if (project == null || exportMode == null || targetProfile == null) {
            throw new IllegalArgumentException("Shader vertex generation arguments must not be null");
        }

        StringBuilder source = new StringBuilder(VERSION)
            .append("in vec3 Position;\n")
            .append("in vec4 Color;\n")
            .append("in vec2 UV0;\n");
        if (targetProfile == ShaderTargetProfile.NEW_ENTITY) {
            source.append("in ivec2 UV1;\n");
        }
        source.append("in ivec2 UV2;\n")
            .append("in vec3 Normal;\n\n")
            .append("uniform mat4 ModelViewMat;\n")
            .append("uniform mat4 ProjMat;\n\n")
            .append("out vec4 vertexColor;\n")
            .append("out vec2 texCoord0;\n")
            .append("out vec2 texCoord2;\n")
            .append("out vec3 skyDir;\n\n")
            .append("void main() {\n")
            .append("    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n")
            .append("    vertexColor = Color;\n")
            .append("    texCoord0 = UV0;\n")
            .append("    texCoord2 = vec2(UV2) / 256.0;\n")
            .append("    skyDir = normalize(Position);\n")
            .append("}\n");
        return source.toString();
    }

    public String assembleFragment(ShaderProject project, ShaderExportMode exportMode) {
        if (project == null || exportMode == null) {
            throw new IllegalArgumentException("Shader fragment generation arguments must not be null");
        }

        Set<ShaderProject.Capability> capabilities = project.capabilities();
        boolean cameraOrientation = includes(
            exportMode,
            capabilities,
            ShaderProject.Capability.CAMERA_ORIENTATION
        );
        boolean colorKey = includes(exportMode, capabilities, ShaderProject.Capability.COLOR_KEY);
        boolean localUvBounds = includes(
            exportMode,
            capabilities,
            ShaderProject.Capability.LOCAL_UV_BOUNDS
        );

        StringBuilder source = new StringBuilder(VERSION)
            .append("uniform sampler2D Sampler0;\n")
            .append("uniform sampler2D Sampler2;\n")
            .append("uniform vec4 ColorModulator;\n")
            .append("uniform float GameTime;\n");

        if (cameraOrientation) {
            source.append("uniform float CameraYaw;\n")
                .append("uniform float CameraPitch;\n");
        }
        if (colorKey) {
            source.append("uniform vec4 ColorKeyColor;\n")
                .append("uniform float ColorKeyTolerance;\n");
        }
        if (localUvBounds) {
            source.append("uniform vec2 LocalUvMin;\n")
                .append("uniform vec2 LocalUvScale;\n");
        }
        for (ShaderProject.TextureBinding texture : project.textures()) {
            source.append("uniform sampler2D ").append(texture.samplerName()).append(";\n");
        }

        source.append("\nin vec4 vertexColor;\n")
            .append("in vec2 texCoord0;\n")
            .append("in vec2 texCoord2;\n")
            .append("in vec3 skyDir;\n\n")
            .append("out vec4 fragColor;\n\n")
            .append(project.fragmentBody().strip())
            .append("\n\n");

        if (usesEffect(project.outputEffects(), "hue_cycle")) {
            source.append("vec3 ecaHueRotate(vec3 color, float angle) {\n")
                .append("    float sine = sin(angle);\n")
                .append("    float cosine = cos(angle);\n")
                .append("    mat3 rotation = mat3(\n")
                .append("        0.299 + 0.701 * cosine + 0.168 * sine, 0.587 - 0.587 * cosine + 0.330 * sine, 0.114 - 0.114 * cosine - 0.497 * sine,\n")
                .append("        0.299 - 0.299 * cosine - 0.328 * sine, 0.587 + 0.413 * cosine + 0.035 * sine, 0.114 - 0.114 * cosine + 0.292 * sine,\n")
                .append("        0.299 - 0.300 * cosine + 1.250 * sine, 0.587 - 0.588 * cosine - 1.050 * sine, 0.114 + 0.886 * cosine - 0.203 * sine\n")
                .append("    );\n")
                .append("    return clamp(rotation * color, vec3(0.0), vec3(1.0));\n")
                .append("}\n\n");
        }

        source.append("void main() {\n");

        if (colorKey) {
            source.append("    if (ColorKeyColor.a > 0.5) {\n")
                .append("        vec4 baseColor = texture(Sampler0, texCoord0);\n")
                .append("        if (baseColor.a < 0.1 || distance(baseColor.rgb, ColorKeyColor.rgb) > ColorKeyTolerance) {\n")
                .append("            discard;\n")
                .append("        }\n")
                .append("    }\n\n");
        }

        source.append("    vec2 effectUv = texCoord0;\n");
        if (localUvBounds) {
            source.append("    effectUv = (texCoord0 - LocalUvMin) * LocalUvScale;\n");
        }

        appendEffects(source, project.outputEffects(), ShaderOutputEffectDefinition.Stage.UV);

        source.append("    vec3 effectDirection = normalize(skyDir);\n");
        if (cameraOrientation) {
            source.append("    float pitchSin = sin(-CameraPitch);\n")
                .append("    float pitchCos = cos(-CameraPitch);\n")
                .append("    effectDirection = vec3(\n")
                .append("        effectDirection.x,\n")
                .append("        effectDirection.y * pitchCos - effectDirection.z * pitchSin,\n")
                .append("        effectDirection.y * pitchSin + effectDirection.z * pitchCos\n")
                .append("    );\n")
                .append("    float yawSin = sin(-CameraYaw);\n")
                .append("    float yawCos = cos(-CameraYaw);\n")
                .append("    effectDirection = vec3(\n")
                .append("        effectDirection.z * yawSin + effectDirection.x * yawCos,\n")
                .append("        effectDirection.y,\n")
                .append("        effectDirection.z * yawCos - effectDirection.x * yawSin\n")
                .append("    );\n");
        }

        source.append("    vec4 effectColor = renderEffect(effectUv, effectDirection, GameTime);\n");
        appendEffects(source, project.outputEffects(), ShaderOutputEffectDefinition.Stage.RESAMPLE);
        appendEffects(source, project.outputEffects(), ShaderOutputEffectDefinition.Stage.COLOR);
        source.append("    fragColor = effectColor * vertexColor * ColorModulator;\n")
            .append("}\n");
        return source.toString();
    }

    private static void appendEffects(
        StringBuilder source,
        List<ShaderOutputEffectInstance> effects,
        ShaderOutputEffectDefinition.Stage stage
    ) {
        if (effects == null) {
            return;
        }
        for (int index = 0; index < effects.size(); index++) {
            ShaderOutputEffectInstance effect = effects.get(index);
            if (effect.enabled() && effect.definition().stage() == stage) {
                source.append(effect.definition().emitter().emit(effect, index));
            }
        }
    }

    private static boolean usesEffect(List<ShaderOutputEffectInstance> effects, String id) {
        if (effects == null) {
            return false;
        }
        for (ShaderOutputEffectInstance effect : effects) {
            if (effect.enabled() && effect.definition().id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean includes(
        ShaderExportMode exportMode,
        Set<ShaderProject.Capability> capabilities,
        ShaderProject.Capability capability
    ) {
        return exportMode.includesEcaUniforms() && capabilities.contains(capability);
    }
}
