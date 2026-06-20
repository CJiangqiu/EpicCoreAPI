package net.eca.util.shader_generator;

import java.util.List;
import java.util.Locale;

public final class ShaderLayerComposer {

    public static String compose(List<ShaderLayer> layers) {
        StringBuilder source = new StringBuilder()
            .append(ShaderLayerBlendMode.generateAllGlslFunctions())
            .append("float ecaHash(vec2 value) {\n")
            .append("    return fract(sin(dot(value, vec2(127.1, 311.7))) * 43758.5453123);\n")
            .append("}\n\n")
            .append("float ecaNoise(vec2 value) {\n")
            .append("    vec2 cell = floor(value);\n")
            .append("    vec2 local = fract(value);\n")
            .append("    local = local * local * (3.0 - 2.0 * local);\n")
            .append("    return mix(mix(ecaHash(cell), ecaHash(cell + vec2(1.0, 0.0)), local.x),\n")
            .append("        mix(ecaHash(cell + vec2(0.0, 1.0)), ecaHash(cell + vec2(1.0, 1.0)), local.x), local.y);\n")
            .append("}\n\n")
            .append("vec4 renderEffect(vec2 effectUv, vec3 direction, float gameTime) {\n")
            .append("    vec3 finalColor = vec3(0.02, 0.03, 0.08);\n\n");

        int elementCounter = 0;
        if (layers != null) {
            int layerCount = 0;
            for (ShaderLayer layer : layers) {
                if (!layer.visible() || layer.elements().isEmpty()) {
                    continue;
                }
                boolean hasEnabledElements = false;
                for (ShaderModuleInstance element : layer.elements()) {
                    if (element.enabled()) {
                        hasEnabledElements = true;
                        break;
                    }
                }
                if (!hasEnabledElements) {
                    continue;
                }

                source.append("    // layer ").append(layerCount)
                    .append(": ").append(layer.name()).append('\n')
                    .append("    {\n")
                    .append("        vec3 color = vec3(0.0);\n")
                    .append("        float alpha = 1.0;\n");

                for (ShaderModuleInstance element : layer.elements()) {
                    if (element.enabled()) {
                        source.append(element.definition().emitter().emit(element, elementCounter));
                        elementCounter++;
                    }
                }

                float opacity = layer.opacity();
                source.append("        vec3 blended = ")
                    .append(layer.blendMode().glslFunctionName())
                    .append("(finalColor, color);\n");
                /* 不透明度=1 时完全使用混合结果；不透明度=0 时保持 finalColor。
                   NORMAL 模式的不透明度即标准的 alpha 合成。 */
                source.append(String.format(Locale.ROOT,
                    "        finalColor = mix(finalColor, blended, %.4f);\n",
                    opacity))
                    .append("    }\n\n");

                layerCount++;
            }
        }

        source.append("    return vec4(max(finalColor, vec3(0.0)), 1.0);\n")
            .append("}\n");
        return source.toString();
    }

    private ShaderLayerComposer() {}
}
