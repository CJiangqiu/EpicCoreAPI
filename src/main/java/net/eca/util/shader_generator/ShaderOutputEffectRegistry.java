package net.eca.util.shader_generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ShaderOutputEffectRegistry {

    private static final Map<String, ShaderOutputEffectDefinition> EFFECTS = new LinkedHashMap<>();

    static {
        registerChromaticAberration();
        registerWaveDistortion();
        registerHeatHaze();
        registerBrightnessPulse();
        registerHueCycle();
        registerScanlines();
        registerVignette();
    }

    public static ShaderOutputEffectDefinition get(String id) {
        return EFFECTS.get(id);
    }

    public static List<ShaderOutputEffectDefinition> all() {
        return Collections.unmodifiableList(new ArrayList<>(EFFECTS.values()));
    }

    private static void register(ShaderOutputEffectDefinition definition) {
        EFFECTS.put(definition.id(), definition);
    }

    private static void registerChromaticAberration() {
        register(new ShaderOutputEffectDefinition(
            "chromatic_aberration",
            "gui.eca.shader_generator.output_effect.chromatic_aberration",
            ShaderOutputEffectDefinition.Stage.RESAMPLE,
            List.of(
                parameter("strength", "gui.eca.shader_generator.parameter.effect_strength", 0.0F, 0.05F, 0.001F, 0.004F),
                parameter("angle", "gui.eca.shader_generator.parameter.angle", 0.0F, 360.0F, 5.0F, 0.0F),
                parameter("pulse_amount", "gui.eca.shader_generator.parameter.pulse_amount", 0.0F, 1.0F, 0.05F, 0.0F),
                parameter("pulse_speed", "gui.eca.shader_generator.parameter.pulse_speed", 0.0F, 10.0F, 0.1F, 1.0F)
            ),
            (effect, index) -> {
                float angle = (float) Math.toRadians(effect.value("angle"));
                return String.format(Locale.ROOT,
                    "    float chromaticPulse%d = 1.0 + sin(GameTime * 1200.0 * %.4f) * %.4f;\n"
                        + "    vec2 chromaticOffset%d = vec2(%.6f, %.6f) * %.5f * chromaticPulse%d;\n"
                        + "    vec4 chromaticPositive%d = renderEffect(effectUv + chromaticOffset%d, effectDirection, GameTime);\n"
                        + "    vec4 chromaticNegative%d = renderEffect(effectUv - chromaticOffset%d, effectDirection, GameTime);\n"
                        + "    effectColor.rgb = vec3(chromaticPositive%d.r, effectColor.g, chromaticNegative%d.b);\n",
                    index, effect.value("pulse_speed"), effect.value("pulse_amount"),
                    index, Math.cos(angle), Math.sin(angle), effect.value("strength"), index,
                    index, index, index, index, index, index, index
                );
            }
        ));
    }

    private static void registerWaveDistortion() {
        register(new ShaderOutputEffectDefinition(
            "wave_distortion",
            "gui.eca.shader_generator.output_effect.wave_distortion",
            ShaderOutputEffectDefinition.Stage.UV,
            List.of(
                parameter("amplitude", "gui.eca.shader_generator.parameter.amplitude", 0.0F, 0.10F, 0.001F, 0.008F),
                parameter("frequency", "gui.eca.shader_generator.parameter.frequency", 0.1F, 30.0F, 0.1F, 8.0F),
                parameter("speed", "gui.eca.shader_generator.parameter.speed", 0.0F, 10.0F, 0.1F, 1.5F)
            ),
            (effect, index) -> String.format(Locale.ROOT,
                "    effectUv += vec2(sin(effectUv.y * %.4f + GameTime * 1200.0 * %.4f),\n"
                    + "        cos(effectUv.x * %.4f + GameTime * 1200.0 * %.4f)) * %.5f;\n",
                effect.value("frequency"), effect.value("speed"),
                effect.value("frequency") * 0.83F, effect.value("speed") * 1.17F,
                effect.value("amplitude")
            )
        ));
    }

    private static void registerHeatHaze() {
        register(new ShaderOutputEffectDefinition(
            "heat_haze",
            "gui.eca.shader_generator.output_effect.heat_haze",
            ShaderOutputEffectDefinition.Stage.UV,
            List.of(
                parameter("strength", "gui.eca.shader_generator.parameter.effect_strength", 0.0F, 0.10F, 0.001F, 0.006F),
                parameter("density", "gui.eca.shader_generator.parameter.density", 0.1F, 30.0F, 0.1F, 7.0F),
                parameter("speed", "gui.eca.shader_generator.parameter.speed", -5.0F, 5.0F, 0.1F, 0.8F),
                parameter("vertical_bias", "gui.eca.shader_generator.parameter.vertical_bias", 0.0F, 1.0F, 0.05F, 0.65F)
            ),
            (effect, index) -> String.format(Locale.ROOT,
                "    float heatHazeMask%d = smoothstep(%.4f, 1.0, effectUv.y);\n"
                    + "    vec2 heatHazeNoise%d = vec2(ecaNoise(effectUv * %.4f + vec2(GameTime * 1200.0 * %.4f, 0.0)),\n"
                    + "        ecaNoise(effectUv.yx * %.4f + vec2(2.7, GameTime * 1200.0 * %.4f))) - 0.5;\n"
                    + "    effectUv += heatHazeNoise%d * %.5f * heatHazeMask%d;\n",
                index, effect.value("vertical_bias"),
                index, effect.value("density"), effect.value("speed"), effect.value("density") * 0.83F, effect.value("speed") * 0.71F,
                index, effect.value("strength"), index
            )
        ));
    }

    private static void registerBrightnessPulse() {
        register(new ShaderOutputEffectDefinition(
            "brightness_pulse",
            "gui.eca.shader_generator.output_effect.brightness_pulse",
            ShaderOutputEffectDefinition.Stage.COLOR,
            List.of(
                parameter("base_brightness", "gui.eca.shader_generator.parameter.base_brightness", 0.0F, 2.0F, 0.05F, 1.0F),
                parameter("pulse_amount", "gui.eca.shader_generator.parameter.pulse_amount", 0.0F, 1.0F, 0.05F, 0.2F),
                parameter("pulse_speed", "gui.eca.shader_generator.parameter.pulse_speed", 0.0F, 10.0F, 0.1F, 1.0F)
            ),
            (effect, index) -> String.format(Locale.ROOT,
                "    effectColor.rgb *= max(0.0, %.4f + sin(GameTime * 1200.0 * %.4f) * %.4f);\n",
                effect.value("base_brightness"), effect.value("pulse_speed"), effect.value("pulse_amount")
            )
        ));
    }

    private static void registerHueCycle() {
        register(new ShaderOutputEffectDefinition(
            "hue_cycle",
            "gui.eca.shader_generator.output_effect.hue_cycle",
            ShaderOutputEffectDefinition.Stage.COLOR,
            List.of(
                parameter("speed", "gui.eca.shader_generator.parameter.speed", -2.0F, 2.0F, 0.05F, 0.2F),
                parameter("strength", "gui.eca.shader_generator.parameter.effect_strength", 0.0F, 1.0F, 0.05F, 1.0F)
            ),
            (effect, index) -> String.format(Locale.ROOT,
                "    effectColor.rgb = mix(effectColor.rgb, ecaHueRotate(effectColor.rgb, GameTime * 1200.0 * %.4f), %.4f);\n",
                effect.value("speed"), effect.value("strength")
            )
        ));
    }

    private static void registerScanlines() {
        register(new ShaderOutputEffectDefinition(
            "scanlines",
            "gui.eca.shader_generator.output_effect.scanlines",
            ShaderOutputEffectDefinition.Stage.COLOR,
            List.of(
                parameter("density", "gui.eca.shader_generator.parameter.density", 10.0F, 500.0F, 5.0F, 120.0F),
                parameter("strength", "gui.eca.shader_generator.parameter.effect_strength", 0.0F, 1.0F, 0.05F, 0.25F),
                parameter("speed", "gui.eca.shader_generator.parameter.speed", -5.0F, 5.0F, 0.1F, 0.0F)
            ),
            (effect, index) -> String.format(Locale.ROOT,
                "    float scanline%d = 1.0 - (sin((effectUv.y + GameTime * 1200.0 * %.4f) * %.4f * 6.2831853) * 0.5 + 0.5) * %.4f;\n"
                    + "    effectColor.rgb *= scanline%d;\n",
                index, effect.value("speed"), effect.value("density"), effect.value("strength"), index
            )
        ));
    }

    private static void registerVignette() {
        register(new ShaderOutputEffectDefinition(
            "vignette",
            "gui.eca.shader_generator.output_effect.vignette",
            ShaderOutputEffectDefinition.Stage.COLOR,
            List.of(
                parameter("radius", "gui.eca.shader_generator.parameter.radius", 0.0F, 1.5F, 0.05F, 0.7F),
                parameter("softness", "gui.eca.shader_generator.parameter.softness", 0.01F, 1.0F, 0.01F, 0.25F),
                parameter("strength", "gui.eca.shader_generator.parameter.effect_strength", 0.0F, 1.0F, 0.05F, 0.35F)
            ),
            (effect, index) -> String.format(Locale.ROOT,
                "    float vignette%d = smoothstep(%.4f, %.4f, length(effectUv - vec2(0.5)) * 1.41421356);\n"
                    + "    effectColor.rgb *= 1.0 - vignette%d * %.4f;\n",
                index, effect.value("radius"), effect.value("radius") + effect.value("softness"), index, effect.value("strength")
            )
        ));
    }

    private static ShaderModuleDefinition.Parameter parameter(
        String key,
        String displayName,
        float minimum,
        float maximum,
        float step,
        float defaultValue
    ) {
        return new ShaderModuleDefinition.Parameter(key, displayName, minimum, maximum, step, defaultValue);
    }

    private ShaderOutputEffectRegistry() {}
}
