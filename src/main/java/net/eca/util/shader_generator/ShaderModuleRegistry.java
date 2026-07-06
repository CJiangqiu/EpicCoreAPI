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
        registerBasicCircle();
        registerBasicRing();
        registerBasicLine();
        registerBasicRectangle();
        registerBasicPolygon();
        registerCrossStar();
        registerDotStar();
        registerImageElement();
    }

    public static ShaderModuleDefinition get(String id) {
        return MODULES.get(id);
    }

    public static List<ShaderModuleDefinition> all() {
        return Collections.unmodifiableList(new ArrayList<>(MODULES.values()));
    }

    public static List<ShaderModuleDefinition> byCategory(ShaderModuleDefinition.Category category) {
        return MODULES.values().stream()
            .filter(definition -> definition.category() == category)
            .toList();
    }

    private static void register(ShaderModuleDefinition definition) {
        MODULES.put(definition.id(), definition);
    }

    private static void registerBasicCircle() {
        register(basic(
            "basic_circle",
            "gui.eca.shader_generator.module.basic_circle",
            List.of(),
            (module, index, point, size) -> String.format(Locale.ROOT,
                "smoothstep(%.4f, %.4f, length(%s))",
                size,
                size * 0.82F,
                point)
        ));
    }

    private static void registerBasicRing() {
        register(basic(
            "basic_ring",
            "gui.eca.shader_generator.module.basic_ring",
            List.of(parameter(
                "thickness",
                "gui.eca.shader_generator.parameter.thickness",
                0.01F,
                0.5F,
                0.01F,
                0.12F
            )),
            (module, index, point, size) -> {
                float thickness = size * module.value("thickness");
                return String.format(Locale.ROOT,
                    "smoothstep(%.4f, 0.0, abs(length(%s) - %.4f))",
                    thickness,
                    point,
                    size
                );
            }
        ));
    }

    private static void registerBasicLine() {
        register(basic(
            "basic_line",
            "gui.eca.shader_generator.module.basic_line",
            List.of(
                parameter("length", "gui.eca.shader_generator.parameter.length", 0.1F, 2.0F, 0.05F, 0.8F),
                parameter("thickness", "gui.eca.shader_generator.parameter.thickness", 0.01F, 0.5F, 0.01F, 0.08F),
                parameter("rotation", "gui.eca.shader_generator.parameter.rotation", 0.0F, 360.0F, 5.0F, 0.0F)
            ),
            (module, index, point, size) -> {
                float halfLength = size * module.value("length") * 0.5F;
                float thickness = size * module.value("thickness");
                float radians = (float) Math.toRadians(module.value("rotation"));
                String rotated = String.format(Locale.ROOT,
                    "ecaRotate(%s, %.6f)",
                    point,
                    -radians
                );
                return String.format(Locale.ROOT,
                    "smoothstep(%.4f, 0.0, ecaSegmentDistance(%s, vec2(-%.4f, 0.0), vec2(%.4f, 0.0)))",
                    thickness,
                    rotated,
                    halfLength,
                    halfLength
                );
            }
        ));
    }

    private static void registerBasicRectangle() {
        register(basic(
            "basic_rectangle",
            "gui.eca.shader_generator.module.basic_rectangle",
            List.of(
                parameter("width", "gui.eca.shader_generator.parameter.width", 0.1F, 2.0F, 0.05F, 1.0F),
                parameter("height", "gui.eca.shader_generator.parameter.height", 0.1F, 2.0F, 0.05F, 0.65F),
                parameter("rotation", "gui.eca.shader_generator.parameter.rotation", 0.0F, 360.0F, 5.0F, 0.0F)
            ),
            (module, index, point, size) -> {
                float radians = (float) Math.toRadians(module.value("rotation"));
                String rotated = String.format(Locale.ROOT,
                    "ecaRotate(%s, %.6f)",
                    point,
                    -radians
                );
                return String.format(Locale.ROOT,
                    "smoothstep(%.4f, 0.0, ecaBoxDistance(%s, vec2(%.4f, %.4f)))",
                    size * 0.08F,
                    rotated,
                    size * module.value("width") * 0.5F,
                    size * module.value("height") * 0.5F
                );
            }
        ));
    }

    private static void registerBasicPolygon() {
        register(basic(
            "basic_polygon",
            "gui.eca.shader_generator.module.basic_polygon",
            List.of(
                parameter("sides", "gui.eca.shader_generator.parameter.sides", 3.0F, 12.0F, 1.0F, 6.0F),
                parameter("rotation", "gui.eca.shader_generator.parameter.rotation", 0.0F, 360.0F, 5.0F, 0.0F)
            ),
            (module, index, point, size) -> String.format(Locale.ROOT,
                "smoothstep(%.4f, 0.0, ecaPolygonDistance(%s, %.4f, %.1f, %.6f))",
                size * 0.08F,
                point,
                size,
                module.value("sides"),
                Math.toRadians(module.value("rotation"))
            )
        ));
    }

    /* 十字星：收割自 starlight.fsh 的 crossStar，两条交叉柔臂 + 中心柔核 */
    private static void registerCrossStar() {
        register(shaped(
            "cross_star",
            "gui.eca.shader_generator.module.cross_star",
            ShaderModuleDefinition.Category.STARRY_SKY,
            List.of(),
            (module, index, point, size) -> String.format(Locale.ROOT,
                "max(smoothstep(%1$.4f, 0.0, abs(%2$s).x) * smoothstep(%3$.4f, 0.0, abs(%2$s).y)"
                    + " + smoothstep(%1$.4f, 0.0, abs(%2$s).y) * smoothstep(%3$.4f, 0.0, abs(%2$s).x),"
                    + " smoothstep(%4$.4f, 0.0, length(%2$s)))",
                size, point, size * 0.15F, size * 0.3F)
        ));
    }

    /* 圆点星星：收割自 starlight.fsh 的 circleStar，中心亮、向外柔化 */
    private static void registerDotStar() {
        register(shaped(
            "dot_star",
            "gui.eca.shader_generator.module.dot_star",
            ShaderModuleDefinition.Category.STARRY_SKY,
            List.of(),
            (module, index, point, size) -> String.format(Locale.ROOT,
                "smoothstep(%1$.4f, %2$.4f, length(%3$s))",
                size, size * 0.3F, point)
        ));
    }

    private static void registerImageElement() {
        register(new ShaderModuleDefinition(
            "image_element",
            "gui.eca.shader_generator.module.image_element",
            ShaderModuleDefinition.Category.IMAGE,
            commonParameters(),
            (module, moduleIndex) -> ""
        ));
    }

    private static ShaderModuleDefinition basic(
        String id,
        String displayName,
        List<ShaderModuleDefinition.Parameter> specificParameters,
        ShapeEmitter shapeEmitter
    ) {
        return shaped(id, displayName, ShaderModuleDefinition.Category.BASIC, specificParameters, shapeEmitter);
    }

    /* 与 basic() 相同的实例化/上色/动画通道，但可指定分类，用于星空、魔法等 */
    private static ShaderModuleDefinition shaped(
        String id,
        String displayName,
        ShaderModuleDefinition.Category category,
        List<ShaderModuleDefinition.Parameter> specificParameters,
        ShapeEmitter shapeEmitter
    ) {
        List<ShaderModuleDefinition.Parameter> parameters = new ArrayList<>(commonParameters());
        parameters.addAll(specificParameters);
        return new ShaderModuleDefinition(
            id,
            displayName,
            category,
            parameters,
            (module, moduleIndex) -> emitInstances(module, moduleIndex, shapeEmitter)
        );
    }

    private static String emitInstances(
        ShaderModuleInstance module,
        int moduleIndex,
        ShapeEmitter shapeEmitter
    ) {
        int count = Math.max(1, Math.round(module.value("count")));
        float size = module.value("size");
        float centerX = module.value("center_x");
        float centerY = module.value("center_y");
        float spreadX = module.value("spread_x");
        float spreadY = module.value("spread_y");
        float seed = module.value("seed");
        float duration = module.value("duration");
        StringBuilder source = new StringBuilder();
        source.append(String.format(Locale.ROOT,
            "        float effectProgress%d = ecaEffectProgress(gameTime, %.4f, %.4f);\n"
                + "        float effectAlphaScale%d = effectProgress%d < 0.0 ? 0.0 : %.4f + %.4f * effectProgress%d;\n",
            moduleIndex,
            duration,
            module.value("repeat_interval"),
            moduleIndex,
            moduleIndex,
            module.value("start_alpha"),
            module.value("end_alpha") - module.value("start_alpha"),
            moduleIndex
        ));
        for (int instance = 0; instance < count; instance++) {
            float randomX = signedRandom(seed, instance, 17.13F);
            float randomY = signedRandom(seed, instance, 71.91F);
            float randomSize = 0.75F + unitRandom(seed, instance, 41.37F) * 0.5F;
            float instanceSize = size * randomSize;
            String point = "point" + moduleIndex + "_" + instance;
            String mask = "mask" + moduleIndex + "_" + instance;
            source.append(String.format(Locale.ROOT,
                "        vec2 %s = effectUv - vec2(%.4f, %.4f);\n",
                point,
                centerX + randomX * spreadX,
                centerY + randomY * spreadY
            ));
            source.append("        float ").append(mask).append(" = ")
                .append(shapeEmitter.emit(module, moduleIndex, point, instanceSize))
                .append(";\n");
            source.append(String.format(Locale.ROOT,
                "        %s *= effectAlphaScale%d;\n",
                mask,
                moduleIndex
            ));
            source.append(String.format(Locale.ROOT,
                "        color += vec3(%.4f, %.4f, %.4f) * %s;\n"
                    + "        alpha = max(alpha, %.4f * %s);\n",
                module.value("color_r"),
                module.value("color_g"),
                module.value("color_b"),
                mask,
                module.value("color_a"),
                mask
            ));
        }
        return source.toString();
    }

    private static List<ShaderModuleDefinition.Parameter> commonParameters() {
        return List.of(
            parameter("color_r", "gui.eca.shader_generator.parameter.color_r", 0.0F, 1.0F, 0.05F, 0.35F),
            parameter("color_g", "gui.eca.shader_generator.parameter.color_g", 0.0F, 1.0F, 0.05F, 0.65F),
            parameter("color_b", "gui.eca.shader_generator.parameter.color_b", 0.0F, 1.0F, 0.05F, 1.0F),
            parameter("color_a", "gui.eca.shader_generator.parameter.color_a", 0.0F, 1.0F, 0.05F, 1.0F),
            parameter("size", "gui.eca.shader_generator.parameter.size", 0.02F, 1.5F, 0.02F, 0.2F),
            parameter("count", "gui.eca.shader_generator.parameter.count", 1.0F, 32.0F, 1.0F, 1.0F),
            parameter("center_x", "gui.eca.shader_generator.parameter.center_x", 0.0F, 1.0F, 0.02F, 0.5F),
            parameter("center_y", "gui.eca.shader_generator.parameter.center_y", 0.0F, 1.0F, 0.02F, 0.5F),
            parameter("spread_x", "gui.eca.shader_generator.parameter.spread_x", 0.0F, 1.0F, 0.02F, 0.0F),
            parameter("spread_y", "gui.eca.shader_generator.parameter.spread_y", 0.0F, 1.0F, 0.02F, 0.0F),
            parameter("seed", "gui.eca.shader_generator.parameter.seed", 0.0F, 1000.0F, 1.0F, 1.0F),
            parameter("duration", "gui.eca.shader_generator.parameter.duration", 0.0F, 60.0F, 0.25F, 0.0F),
            parameter("repeat_interval", "gui.eca.shader_generator.parameter.repeat_interval", 0.0F, 60.0F, 0.25F, 0.0F),
            parameter("start_alpha", "gui.eca.shader_generator.parameter.start_alpha", 0.0F, 1.0F, 0.05F, 1.0F),
            parameter("end_alpha", "gui.eca.shader_generator.parameter.end_alpha", 0.0F, 1.0F, 0.05F, 1.0F)
        );
    }

    private static float unitRandom(float seed, int index, float salt) {
        double value = Math.sin(seed * 12.9898 + index * 78.233 + salt) * 43758.5453;
        return (float) (value - Math.floor(value));
    }

    private static float signedRandom(float seed, int index, float salt) {
        return unitRandom(seed, index, salt) * 2.0F - 1.0F;
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

    @FunctionalInterface
    private interface ShapeEmitter {
        String emit(ShaderModuleInstance module, int moduleIndex, String point, float size);
    }

    private ShaderModuleRegistry() {}
}
