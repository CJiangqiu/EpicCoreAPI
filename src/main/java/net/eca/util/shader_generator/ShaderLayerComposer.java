package net.eca.util.shader_generator;

import java.util.List;
import java.util.Locale;

public final class ShaderLayerComposer {

    public static String compose(List<ShaderLayer> layers) {
        StringBuilder source = new StringBuilder();
        if (usesOverlay(layers)) {
            source.append("vec3 ecaOverlay(vec3 backdrop, vec3 source) {\n")
                .append("    return mix(2.0 * backdrop * source,\n")
                .append("        1.0 - 2.0 * (1.0 - backdrop) * (1.0 - source), step(vec3(0.5), backdrop));\n")
                .append("}\n\n");
        }
        source
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
            .append("vec2 ecaRotate(vec2 point, float angle) {\n")
            .append("    float sine = sin(angle);\n")
            .append("    float cosine = cos(angle);\n")
            .append("    return mat2(cosine, -sine, sine, cosine) * point;\n")
            .append("}\n\n")
            .append("float ecaSegmentDistance(vec2 point, vec2 start, vec2 end) {\n")
            .append("    vec2 offset = point - start;\n")
            .append("    vec2 segment = end - start;\n")
            .append("    float factor = clamp(dot(offset, segment) / max(dot(segment, segment), 0.000001), 0.0, 1.0);\n")
            .append("    return length(offset - segment * factor);\n")
            .append("}\n\n")
            .append("float ecaBoxDistance(vec2 point, vec2 halfSize) {\n")
            .append("    vec2 delta = abs(point) - halfSize;\n")
            .append("    return length(max(delta, 0.0)) + min(max(delta.x, delta.y), 0.0);\n")
            .append("}\n\n")
            .append("float ecaPolygonDistance(vec2 point, float radius, float sides, float rotation) {\n")
            .append("    float angle = atan(point.y, point.x) + rotation;\n")
            .append("    float sector = 6.2831853 / sides;\n")
            .append("    return cos(floor(0.5 + angle / sector) * sector - angle) * length(point) - radius;\n")
            .append("}\n\n")
            .append("float ecaEffectProgress(float gameTime, float duration, float repeatInterval) {\n")
            .append("    if (duration <= 0.0) return 1.0;\n")
            .append("    float elapsedSeconds = gameTime * 1200.0;\n")
            .append("    float cycle = max(duration + max(repeatInterval, 0.0), 0.000001);\n")
            .append("    float cycleTime = mod(elapsedSeconds, cycle);\n")
            .append("    if (cycleTime > duration) return -1.0;\n")
            .append("    return clamp(cycleTime / duration, 0.0, 1.0);\n")
            .append("}\n\n")
            .append("vec4 renderEffect(vec2 effectUv, vec3 direction, float gameTime) {\n")
            .append("    vec3 finalColor = vec3(0.0);\n")
            .append("    float finalAlpha = 0.0;\n\n");

        int elementCounter = 0;
        if (layers != null) {
            int layerCount = 0;
            for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
                ShaderLayer layer = layers.get(layerIndex);
                boolean hasBase = layer.baseAlpha() > 0.0F || layer.backgroundImagePath() != null;
                if (!layer.visible() || layer.elements().isEmpty() && !hasBase) {
                    continue;
                }
                boolean hasEnabledElements = false;
                for (ShaderModuleInstance element : layer.elements()) {
                    if (element.enabled()) {
                        hasEnabledElements = true;
                        break;
                    }
                }
                if (!hasEnabledElements && !hasBase) {
                    continue;
                }

                source.append("    // layer ").append(layerCount)
                    .append(": ").append(layer.name()).append('\n')
                    .append("    {\n")
                    .append(String.format(Locale.ROOT,
                        "        vec3 color = vec3(%.4f, %.4f, %.4f);\n"
                            + "        float alpha = %.4f;\n",
                        layer.baseRed(),
                        layer.baseGreen(),
                        layer.baseBlue(),
                        layer.baseAlpha()
                    ));

                if (layer.backgroundImagePath() != null) {
                    source.append("        vec4 layerImage = texture(")
                        .append(layerSamplerName(layerIndex))
                        .append(", effectUv);\n")
                        .append("        color = mix(color, layerImage.rgb, layerImage.a);\n")
                        .append("        alpha = max(alpha, layerImage.a);\n");
                }

                for (int elementIndex = 0; elementIndex < layer.elements().size(); elementIndex++) {
                    ShaderModuleInstance element = layer.elements().get(elementIndex);
                    if (element.enabled()) {
                        if (element.imagePath() != null) {
                            source.append(emitImageElement(
                                element,
                                elementCounter,
                                elementSamplerName(layerIndex, elementIndex)
                            ));
                        } else {
                            source.append(element.definition().emitter().emit(element, elementCounter));
                        }
                        elementCounter++;
                    }
                }

                source.append("        finalColor = ").append(blendExpression(layer.blendMode())).append(";\n")
                    .append("        finalAlpha = max(finalAlpha, alpha);\n")
                    .append("    }\n\n");

                layerCount++;
            }
        }

        source.append("    return vec4(max(finalColor, vec3(0.0)), finalAlpha);\n")
            .append("}\n");
        return source.toString();
    }

    /* 按混合模式生成 finalColor 更新表达式。finalColor 为下方累积色(backdrop)，color 为本图层色(source)，
       结果统一按图层 alpha 加权，NORMAL 退化为历史的直接 alpha 叠加。 */
    private static String blendExpression(ShaderBlendMode mode) {
        return switch (mode) {
            case ADD -> "finalColor + color * alpha";
            case MULTIPLY -> "mix(finalColor, finalColor * color, alpha)";
            case SCREEN -> "mix(finalColor, 1.0 - (1.0 - finalColor) * (1.0 - color), alpha)";
            case OVERLAY -> "mix(finalColor, ecaOverlay(finalColor, color), alpha)";
            default -> "mix(finalColor, color, alpha)";
        };
    }

    private static boolean usesOverlay(List<ShaderLayer> layers) {
        if (layers == null) {
            return false;
        }
        for (ShaderLayer layer : layers) {
            if (layer.visible() && layer.blendMode() == ShaderBlendMode.OVERLAY) {
                return true;
            }
        }
        return false;
    }

    public static String layerSamplerName(int layerIndex) {
        return "EcaLayerImage" + layerIndex;
    }

    public static String elementSamplerName(int layerIndex, int elementIndex) {
        return "EcaElementImage" + layerIndex + "_" + elementIndex;
    }

    private static String emitImageElement(
        ShaderModuleInstance module,
        int moduleIndex,
        String samplerName
    ) {
        int count = Math.max(1, Math.round(module.value("count")));
        float size = module.value("size");
        float centerX = module.value("center_x");
        float centerY = module.value("center_y");
        float spreadX = module.value("spread_x");
        float spreadY = module.value("spread_y");
        float seed = module.value("seed");
        StringBuilder source = new StringBuilder();
        source.append(String.format(Locale.ROOT,
            "        float effectProgress%d = ecaEffectProgress(gameTime, %.4f, %.4f);\n"
                + "        float effectAlphaScale%d = effectProgress%d < 0.0 ? 0.0 : %.4f + %.4f * effectProgress%d;\n",
            moduleIndex,
            module.value("duration"),
            module.value("repeat_interval"),
            moduleIndex,
            moduleIndex,
            module.value("start_alpha"),
            module.value("end_alpha") - module.value("start_alpha"),
            moduleIndex
        ));
        for (int instance = 0; instance < count; instance++) {
            float offsetX = signedRandom(seed, instance, 17.13F) * spreadX;
            float offsetY = signedRandom(seed, instance, 71.91F) * spreadY;
            float instanceSize = size * (0.75F + unitRandom(seed, instance, 41.37F) * 0.5F);
            source.append(String.format(Locale.ROOT,
                "        vec2 imageUv%d_%d = (effectUv - vec2(%.4f, %.4f)) / %.4f + vec2(0.5);\n"
                    + "        if (all(greaterThanEqual(imageUv%d_%d, vec2(0.0))) && all(lessThanEqual(imageUv%d_%d, vec2(1.0)))) {\n"
                    + "            vec4 imageSample%d_%d = texture(%s, imageUv%d_%d);\n"
                    + "            imageSample%d_%d *= vec4(%.4f, %.4f, %.4f, %.4f * effectAlphaScale%d);\n"
                    + "            color = mix(color, imageSample%d_%d.rgb, imageSample%d_%d.a);\n"
                    + "            alpha = max(alpha, imageSample%d_%d.a);\n"
                    + "        }\n",
                moduleIndex, instance, centerX + offsetX, centerY + offsetY, instanceSize,
                moduleIndex, instance, moduleIndex, instance,
                moduleIndex, instance, samplerName, moduleIndex, instance,
                moduleIndex, instance,
                module.value("color_r"), module.value("color_g"),
                module.value("color_b"), module.value("color_a"),
                moduleIndex,
                moduleIndex, instance, moduleIndex, instance,
                moduleIndex, instance
            ));
        }
        return source.toString();
    }

    private static float unitRandom(float seed, int index, float salt) {
        double value = Math.sin(seed * 12.9898 + index * 78.233 + salt) * 43758.5453;
        return (float) (value - Math.floor(value));
    }

    private static float signedRandom(float seed, int index, float salt) {
        return unitRandom(seed, index, salt) * 2.0F - 1.0F;
    }

    private ShaderLayerComposer() {}
}
