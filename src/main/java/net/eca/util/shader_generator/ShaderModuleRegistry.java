package net.eca.util.shader_generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ShaderModuleRegistry {

    private static final Map<String, ShaderModuleDefinition> MODULES = new LinkedHashMap<>();

    static {
        registerGradient();
        registerRings();
        registerNoise();
        registerPulse();
    }

    public static ShaderModuleDefinition get(String id) {
        return MODULES.get(id);
    }

    public static List<ShaderModuleDefinition> all() {
        return Collections.unmodifiableList(new ArrayList<>(MODULES.values()));
    }

    private static void register(ShaderModuleDefinition definition) {
        MODULES.put(definition.id(), definition);
    }

    private static void registerGradient() {
        register(new ShaderModuleDefinition(
            "gradient",
            "gui.eca.shader_generator.module.gradient",
            List.of(
                parameter("top_r", "gui.eca.shader_generator.parameter.top_r", 0.0F, 1.0F, 0.05F, 0.45F),
                parameter("top_g", "gui.eca.shader_generator.parameter.top_g", 0.0F, 1.0F, 0.05F, 0.15F),
                parameter("top_b", "gui.eca.shader_generator.parameter.top_b", 0.0F, 1.0F, 0.05F, 0.85F),
                parameter("bottom_r", "gui.eca.shader_generator.parameter.bottom_r", 0.0F, 1.0F, 0.05F, 0.02F),
                parameter("bottom_g", "gui.eca.shader_generator.parameter.bottom_g", 0.0F, 1.0F, 0.05F, 0.08F),
                parameter("bottom_b", "gui.eca.shader_generator.parameter.bottom_b", 0.0F, 1.0F, 0.05F, 0.20F)
            ),
            (module, index) -> String.format(Locale.ROOT,
                "    color = mix(vec3(%.4f, %.4f, %.4f), vec3(%.4f, %.4f, %.4f), clamp(effectUv.y, 0.0, 1.0));\n",
                module.value("bottom_r"), module.value("bottom_g"), module.value("bottom_b"),
                module.value("top_r"), module.value("top_g"), module.value("top_b"))
        ));
    }

    private static void registerRings() {
        register(new ShaderModuleDefinition(
            "rings",
            "gui.eca.shader_generator.module.rings",
            List.of(
                parameter("frequency", "gui.eca.shader_generator.parameter.frequency", 1.0F, 30.0F, 1.0F, 9.0F),
                parameter("speed", "gui.eca.shader_generator.parameter.speed", -8.0F, 8.0F, 0.25F, 1.5F),
                parameter("strength", "gui.eca.shader_generator.parameter.strength", 0.0F, 2.0F, 0.05F, 0.75F)
            ),
            (module, index) -> String.format(Locale.ROOT,
                "    float ring%d = 0.5 + 0.5 * cos(length(effectUv - vec2(0.5)) * %.4f - gameTime * 6.2831853 * %.4f);\n"
                    + "    color += vec3(0.25, 0.55, 1.0) * pow(ring%d, 8.0) * %.4f;\n",
                index, module.value("frequency"), module.value("speed"),
                index, module.value("strength"))
        ));
    }

    private static void registerNoise() {
        register(new ShaderModuleDefinition(
            "noise",
            "gui.eca.shader_generator.module.noise",
            List.of(
                parameter("scale", "gui.eca.shader_generator.parameter.scale", 1.0F, 80.0F, 1.0F, 24.0F),
                parameter("speed", "gui.eca.shader_generator.parameter.speed", -8.0F, 8.0F, 0.25F, 0.75F),
                parameter("strength", "gui.eca.shader_generator.parameter.strength", 0.0F, 1.0F, 0.05F, 0.18F)
            ),
            (module, index) -> String.format(Locale.ROOT,
                "    float noise%d = ecaNoise(effectUv * %.4f + vec2(gameTime * %.4f));\n"
                    + "    color += vec3(noise%d) * %.4f;\n",
                index, module.value("scale"), module.value("speed"),
                index, module.value("strength"))
        ));
    }

    private static void registerPulse() {
        register(new ShaderModuleDefinition(
            "pulse",
            "gui.eca.shader_generator.module.pulse",
            List.of(
                parameter("speed", "gui.eca.shader_generator.parameter.speed", 0.0F, 12.0F, 0.25F, 2.0F),
                parameter("depth", "gui.eca.shader_generator.parameter.depth", 0.0F, 1.0F, 0.05F, 0.25F)
            ),
            (module, index) -> String.format(Locale.ROOT,
                "    float pulse%d = 1.0 - %.4f + %.4f * (0.5 + 0.5 * sin(gameTime * 6.2831853 * %.4f));\n"
                    + "    color *= pulse%d;\n",
                index, module.value("depth"), module.value("depth"), module.value("speed"), index)
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

    private ShaderModuleRegistry() {}
}
