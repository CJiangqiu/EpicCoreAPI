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
        registerBasicEllipse();
        registerBasicStar();
        registerCrossStar();
        registerDotStar();
        registerImageElement();
        registerSpiral();
        registerEllipticalGalaxy();
        registerSupernova();
        registerEnergyRing();
        registerMeteor();
        registerNebulaHaze();
        registerBlackHole();
        registerLightning();
        registerAurora();
        registerFireflies();
        registerWaterBubbles();
        registerToxicBubbles();
        registerRainStreaks();
        registerSnowfall();
        registerFallingLeaves();
        registerMagmaDebris();
        registerDustHaze();
        registerDigitalRain();
        registerRune();
        registerPlanetSymbol();
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
                parameter("thickness", "gui.eca.shader_generator.parameter.thickness", 0.01F, 0.5F, 0.01F, 0.08F)
            ),
            (module, index, point, size) -> {
                float halfLength = size * module.value("length") * 0.5F;
                float thickness = size * module.value("thickness");
                return String.format(Locale.ROOT,
                    "smoothstep(%.4f, 0.0, ecaSegmentDistance(%s, vec2(-%.4f, 0.0), vec2(%.4f, 0.0)))",
                    thickness,
                    point,
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
                parameter("height", "gui.eca.shader_generator.parameter.height", 0.1F, 2.0F, 0.05F, 0.65F)
            ),
            (module, index, point, size) -> {
                return String.format(Locale.ROOT,
                    "smoothstep(%.4f, 0.0, ecaBoxDistance(%s, vec2(%.4f, %.4f)))",
                    size * 0.08F,
                    point,
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
                parameter("sides", "gui.eca.shader_generator.parameter.sides", 3.0F, 12.0F, 1.0F, 6.0F)
            ),
            (module, index, point, size) -> String.format(Locale.ROOT,
                "smoothstep(%.4f, 0.0, ecaPolygonDistance(%s, %.4f, %.1f, %.6f))",
                size * 0.08F,
                point,
                size,
                module.value("sides"),
                0.0F
            )
        ));
    }

    private static void registerBasicEllipse() {
        register(basic(
            "basic_ellipse",
            "gui.eca.shader_generator.module.basic_ellipse",
            List.of(
                parameter("width", "gui.eca.shader_generator.parameter.width", 0.1F, 3.0F, 0.05F, 1.0F),
                parameter("height", "gui.eca.shader_generator.parameter.height", 0.1F, 3.0F, 0.05F, 0.65F)
            ),
            (module, index, point, size) -> {
                return String.format(Locale.ROOT,
                    "smoothstep(1.0, 0.82, length(vec2(%s.x / (%.4f * %.4f), %s.y / (%.4f * %.4f))))",
                    point,
                    size,
                    module.value("width"),
                    point,
                    size,
                    module.value("height")
                );
            }
        ));
    }

    private static void registerBasicStar() {
        register(basic(
            "basic_star",
            "gui.eca.shader_generator.module.basic_star",
            List.of(
                parameter("points", "gui.eca.shader_generator.parameter.points", 3.0F, 12.0F, 1.0F, 5.0F),
                parameter("inner_ratio", "gui.eca.shader_generator.parameter.inner_ratio", 0.1F, 0.9F, 0.05F, 0.5F)
            ),
            (module, index, point, size) -> {
                int pts = Math.round(module.value("points"));
                return String.format(Locale.ROOT,
                    "smoothstep(%.4f, 0.0, ecaStarDistance(%s, %d, %.4f, %.4f))",
                    size * 0.08F,
                    point,
                    pts,
                    size,
                    size * module.value("inner_ratio")
                );
            }
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

    /* ========== 星空场模块发射器 ========== */

    @FunctionalInterface
    private interface FieldBody {
        void emitMask(StringBuilder out, String pointVar, String maskVar,
                      float instanceSize, ShaderModuleInstance module,
                      int moduleIndex, int instance);
    }

    /* 与 emitInstances 等效，但掩码由 FieldBody 多行生成，支持含 gameTime 的复杂场效果 */
    private static String emitFieldInstances(
        ShaderModuleInstance module,
        int moduleIndex,
        FieldBody body
    ) {
        int count = Math.max(1, Math.round(module.value("count")));
        float size = module.value("size");
        float centerX = module.value("center_x");
        float centerY = module.value("center_y");
        float spreadX = module.value("spread_x");
        float spreadY = module.value("spread_y");
        float seed = module.value("seed");
        float duration = module.value("duration");
        float rotation = (float) Math.toRadians(module.value("rotation"));
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
            float offsetX = 0.0F;
            float offsetY = 0.0F;
            if (instance > 0) {
                float spreadFactor = (float) instance / (count - 1);
                float effectiveSpreadX = spreadX > 0.001F ? spreadX : size;
                float effectiveSpreadY = spreadY > 0.001F ? spreadY : size;
                offsetX = signedRandom(seed, instance, 17.13F) * effectiveSpreadX * spreadFactor;
                offsetY = signedRandom(seed, instance, 71.91F) * effectiveSpreadY * spreadFactor;
            }
            float randomSize = 0.75F + unitRandom(seed, instance, 41.37F) * 0.5F;
            float instanceSize = size * randomSize;
            String pointVar = "point" + moduleIndex + "_" + instance;
            String maskVar = "mask" + moduleIndex + "_" + instance;
            source.append(String.format(Locale.ROOT,
                "        vec2 %s = ecaRotate(effectUv - vec2(%.4f, %.4f), %.6f);\n",
                pointVar,
                centerX + offsetX,
                centerY + offsetY,
                -rotation
            ));
            source.append("        float ").append(maskVar).append(";\n");
            body.emitMask(source, pointVar, maskVar, instanceSize, module, moduleIndex, instance);
            source.append(String.format(Locale.ROOT,
                "        %s *= effectAlphaScale%d;\n",
                maskVar,
                moduleIndex
            ));
            source.append(String.format(Locale.ROOT,
                "        color += vec3(%.4f, %.4f, %.4f) * %s;\n"
                    + "        alpha = max(alpha, %.4f * %s);\n",
                module.value("color_r"),
                module.value("color_g"),
                module.value("color_b"),
                maskVar,
                module.value("color_a"),
                maskVar
            ));
        }
        return source.toString();
    }

    private static ShaderModuleDefinition fieldModule(
        String id,
        String displayName,
        List<ShaderModuleDefinition.Parameter> specificParameters,
        FieldBody body
    ) {
        return fieldModule(
            id,
            displayName,
            ShaderModuleDefinition.Category.STARRY_SKY,
            specificParameters,
            body
        );
    }

    private static ShaderModuleDefinition environmentFieldModule(
        String id,
        String displayName,
        List<ShaderModuleDefinition.Parameter> specificParameters,
        FieldBody body
    ) {
        return fieldModule(
            id,
            displayName,
            ShaderModuleDefinition.Category.ENVIRONMENT,
            specificParameters,
            body
        );
    }

    private static ShaderModuleDefinition fieldModule(
        String id,
        String displayName,
        ShaderModuleDefinition.Category category,
        List<ShaderModuleDefinition.Parameter> specificParameters,
        FieldBody body
    ) {
        List<ShaderModuleDefinition.Parameter> parameters = new ArrayList<>(commonParameters());
        parameters.addAll(specificParameters);
        return new ShaderModuleDefinition(
            id,
            displayName,
            category,
            parameters,
            (module, moduleIndex) -> emitFieldInstances(module, moduleIndex, body)
        );
    }

    /* ========== 星空场模块注册 ========== */

    /* 螺旋：合并 starlight.fsh spiral() 与 cosmos.fsh spiralGalaxy()，极坐标螺旋臂 + 亮核 */
    private static void registerSpiral() {
        register(fieldModule(
            "spiral",
            "gui.eca.shader_generator.module.spiral",
            List.of(
                parameter("arm_count", "gui.eca.shader_generator.parameter.arm_count", 2.0F, 8.0F, 1.0F, 4.0F),
                parameter("twist", "gui.eca.shader_generator.parameter.twist", 1.0F, 5.0F, 0.1F, 2.5F),
                parameter("sharpness", "gui.eca.shader_generator.parameter.sharpness", 0.5F, 3.0F, 0.1F, 1.5F),
                parameter("core_brightness", "gui.eca.shader_generator.parameter.core_brightness", 0.2F, 2.0F, 0.1F, 0.8F)
            ),
            (out, pointVar, maskVar, instanceSize, module, moduleIndex, instance) -> {
                int armCount = Math.round(module.value("arm_count"));
                float twist = module.value("twist");
                float sharpness = module.value("sharpness");
                float coreBrightness = module.value("core_brightness");
                out.append(String.format(Locale.ROOT,
                    "        float r%d = length(%s) / max(%.4f, 0.001);\n"
                        + "        float a%d = atan(%s.y, %s.x);\n"
                        + "        float sa%d = a%d + r%d * %.4f;\n"
                        + "        float arm%d = sin(sa%d * %d.0) * 0.5 + 0.5;\n"
                        + "        arm%d = pow(max(arm%d, 0.0), %.4f);\n"
                        + "        float disk%d = exp(-r%d * 1.5);\n"
                        + "        float core%d = exp(-r%d * r%d * 8.0) * %.4f;\n"
                        + "        %s = clamp(arm%d * disk%d + core%d, 0.0, 1.0);\n",
                    instance, pointVar, instanceSize,
                    instance, pointVar, pointVar,
                    instance, instance, instance, twist,
                    instance, instance, armCount,
                    instance, instance, sharpness,
                    instance, instance,
                    instance, instance, instance, coreBrightness,
                    maskVar, instance, instance, instance
                ));
            }
        ));
    }

    /* 椭圆星系：cosmos.fsh ellipticalGalaxy()，各向异性椭圆光斑 */
    private static void registerEllipticalGalaxy() {
        register(fieldModule(
            "elliptical_galaxy",
            "gui.eca.shader_generator.module.elliptical_galaxy",
            List.of(
                parameter("axis_ratio", "gui.eca.shader_generator.parameter.axis_ratio", 0.2F, 1.0F, 0.05F, 0.6F),
                parameter("falloff", "gui.eca.shader_generator.parameter.falloff", 1.0F, 10.0F, 0.5F, 5.0F)
            ),
            (out, pointVar, maskVar, instanceSize, module, moduleIndex, instance) -> {
                float axisRatio = module.value("axis_ratio");
                float falloff = module.value("falloff");
                out.append(String.format(Locale.ROOT,
                    "        vec2 rp%d = %s;\n"
                        + "        rp%d.x /= %.4f;\n"
                        + "        float r%d = length(rp%d) / max(%.4f, 0.001);\n"
                        + "        %s = exp(-r%d * r%d * %.4f);\n",
                    instance, pointVar,
                    instance, axisRatio,
                    instance, instance, instanceSize,
                    maskVar, instance, instance, falloff
                ));
            }
        ));
    }

    /* 超新星：cosmos.fsh supernova()，爆发核心 + 射线 + 光晕 */
    private static void registerSupernova() {
        register(fieldModule(
            "supernova",
            "gui.eca.shader_generator.module.supernova",
            List.of(
                parameter("ray_count", "gui.eca.shader_generator.parameter.ray_count", 4.0F, 24.0F, 1.0F, 12.0F),
                parameter("ray_width", "gui.eca.shader_generator.parameter.ray_width", 0.02F, 0.2F, 0.01F, 0.08F),
                parameter("core_brightness", "gui.eca.shader_generator.parameter.core_brightness", 0.5F, 3.0F, 0.1F, 2.0F),
                parameter("halo_strength", "gui.eca.shader_generator.parameter.halo_strength", 0.0F, 1.0F, 0.05F, 0.5F)
            ),
            (out, pointVar, maskVar, instanceSize, module, moduleIndex, instance) -> {
                int rayCount = Math.round(module.value("ray_count"));
                float rayWidth = module.value("ray_width");
                float coreBrightness = module.value("core_brightness");
                float haloStrength = module.value("halo_strength");
                out.append(String.format(Locale.ROOT,
                    "        float r%d = length(%s) / max(%.4f, 0.001);\n"
                        + "        float a%d = atan(%s.y, %s.x);\n"
                        + "        float core%d = exp(-r%d * r%d * 50.0) * %.4f;\n"
                        + "        core%d *= 0.8 + 0.2 * sin(gameTime * 3600.0);\n"
                        + "        float rays%d = 0.0;\n",
                    instance, pointVar, instanceSize,
                    instance, pointVar, pointVar,
                    instance, instance, instance, coreBrightness,
                    instance,
                    instance
                ));
                out.append(String.format(Locale.ROOT,
                    "        for (int ri%d = 0; ri%d < %d; ri%d++) {\n"
                        + "            float ra%d = float(ri%d) * 6.28318530718 / %d.0;\n"
                        + "            float ad%d = abs(mod(a%d - ra%d + 3.14159265359, 6.28318530718) - 3.14159265359);\n"
                        + "            float ray%d = exp(-ad%d * ad%d / %.6f);\n"
                        + "            ray%d *= exp(-r%d * 2.0);\n"
                        + "            rays%d += ray%d;\n"
                        + "        }\n",
                    instance, instance, rayCount, instance,
                    instance, instance, rayCount,
                    instance, instance, instance,
                    instance, instance, instance, rayWidth * rayWidth,
                    instance, instance,
                    instance, instance
                ));
                out.append(String.format(Locale.ROOT,
                    "        float halo%d = exp(-r%d * 3.0) * %.4f;\n"
                        + "        %s = core%d + rays%d * 0.4 + halo%d;\n",
                    instance, instance, haloStrength,
                    maskVar, instance, instance, instance
                ));
            }
        ));
    }

    /* 能量环：cosmos.fsh energyWave()，从中心向外扩散的环形波 */
    private static void registerEnergyRing() {
        register(fieldModule(
            "energy_ring",
            "gui.eca.shader_generator.module.energy_ring",
            List.of(
                parameter("ring_speed", "gui.eca.shader_generator.parameter.ring_speed", 0.1F, 1.0F, 0.05F, 0.3F),
                parameter("ring_thickness", "gui.eca.shader_generator.parameter.ring_thickness", 0.01F, 0.15F, 0.01F, 0.05F),
                parameter("max_radius", "gui.eca.shader_generator.parameter.max_radius", 0.5F, 3.0F, 0.1F, 2.0F)
            ),
            (out, pointVar, maskVar, instanceSize, module, moduleIndex, instance) -> {
                float ringSpeed = module.value("ring_speed");
                float ringThickness = instanceSize * module.value("ring_thickness");
                float maxRadius = module.value("max_radius");
                out.append(String.format(Locale.ROOT,
                    "        float r%d = length(%s) / max(%.4f, 0.001);\n"
                        + "        float wr%d = mod(gameTime * 1200.0 * %.4f, %.4f);\n"
                        + "        float w%d = exp(-pow(r%d - wr%d, 2.0) / %.6f);\n"
                        + "        w%d *= smoothstep(%.4f, %.4f, wr%d);\n"
                        + "        float aa%d = atan(%s.y, %s.x);\n"
                        + "        w%d *= 1.0 + 0.1 * sin(aa%d * 8.0 + gameTime * 2400.0);\n"
                        + "        %s = w%d;\n",
                    instance, pointVar, instanceSize,
                    instance, ringSpeed, maxRadius,
                    instance, instance, instance, ringThickness * ringThickness,
                    instance, maxRadius, maxRadius * 0.25F, instance,
                    instance, pointVar, pointVar,
                    instance, instance,
                    maskVar, instance
                ));
            }
        ));
    }

    /* 流星：dream_sakura.fsh / the_last_end.fsh，定向拖尾 + 头部光晕 */
    private static void registerMeteor() {
        register(fieldModule(
            "meteor",
            "gui.eca.shader_generator.module.meteor",
            List.of(
                parameter("angle", "gui.eca.shader_generator.parameter.angle", 0.0F, 360.0F, 5.0F, 45.0F),
                parameter("trail_length", "gui.eca.shader_generator.parameter.trail_length", 0.05F, 0.4F, 0.02F, 0.2F),
                parameter("trail_width", "gui.eca.shader_generator.parameter.trail_width", 0.005F, 0.05F, 0.002F, 0.015F),
                parameter("head_size", "gui.eca.shader_generator.parameter.head_size", 0.01F, 0.1F, 0.005F, 0.04F)
            ),
            (out, pointVar, maskVar, instanceSize, module, moduleIndex, instance) -> {
                float angleRad = (float) Math.toRadians(module.value("angle"));
                float trailLength = instanceSize * module.value("trail_length");
                float trailWidth = instanceSize * module.value("trail_width");
                float headSize = instanceSize * module.value("head_size");
                out.append(String.format(Locale.ROOT,
                    "        vec2 dir%d = vec2(%.6f, %.6f);\n"
                        + "        float along%d = dot(%s, dir%d);\n"
                        + "        float perp%d = abs(dot(%s, vec2(-dir%d.y, dir%d.x)));\n"
                        + "        float trailMask%d = smoothstep(%.4f, 0.0, along%d) * smoothstep(%.4f, 0.0, perp%d);\n"
                        + "        float headMask%d = smoothstep(%.4f, 0.0, length(%s));\n"
                        + "        %s = max(trailMask%d, headMask%d * 1.2);\n",
                    instance, Math.cos(angleRad), Math.sin(angleRad),
                    instance, pointVar, instance,
                    instance, pointVar, instance, instance,
                    instance, -trailLength, instance, trailWidth, instance,
                    instance, headSize, pointVar,
                    maskVar, instance, instance
                ));
            }
        ));
    }

    /* 星云薄雾：cosmos.fsh nebulaDust() / filters/cosmos.fsh nebula()，FBM 噪声密度场 + 旋流 + 径向衰减 */
    private static void registerNebulaHaze() {
        register(fieldModule(
            "nebula_haze",
            "gui.eca.shader_generator.module.nebula_haze",
            List.of(
                parameter("density", "gui.eca.shader_generator.parameter.density", 0.3F, 0.8F, 0.05F, 0.5F),
                parameter("swirl_strength", "gui.eca.shader_generator.parameter.swirl_strength", 0.0F, 1.0F, 0.05F, 0.3F),
                parameter("swirl_freq", "gui.eca.shader_generator.parameter.swirl_freq", 1.0F, 8.0F, 0.5F, 3.0F),
                parameter("inner_radius", "gui.eca.shader_generator.parameter.inner_radius", 0.0F, 1.0F, 0.05F, 0.2F),
                parameter("outer_radius", "gui.eca.shader_generator.parameter.outer_radius", 0.5F, 3.0F, 0.1F, 1.5F)
            ),
            (out, pointVar, maskVar, instanceSize, module, moduleIndex, instance) -> {
                float density = module.value("density");
                float swirlStrength = module.value("swirl_strength");
                float swirlFreq = module.value("swirl_freq");
                float innerRadius = module.value("inner_radius");
                float outerRadius = module.value("outer_radius");
                out.append(String.format(Locale.ROOT,
                    "        vec2 fp%d = %s / max(%.4f, 0.001);\n"
                        + "        vec2 dp%d = fp%d + vec2(\n"
                        + "            ecaFbm(fp%d * 2.0 + gameTime * 10.0, 4) * 0.3,\n"
                        + "            ecaFbm(fp%d * 2.0 + gameTime * 6.0 + 10.0, 4) * 0.3\n"
                        + "        );\n"
                        + "        float d%d = ecaFbm(dp%d * 1.5, 5);\n"
                        + "        d%d = smoothstep(%.4f, %.4f, d%d);\n"
                        + "        float rf%d = length(fp%d);\n"
                        + "        d%d *= smoothstep(%.4f, %.4f, rf%d);\n"
                        + "        d%d *= smoothstep(%.4f, %.4f, rf%d);\n"
                        + "        float sw%d = sin(atan(fp%d.y, fp%d.x) * %.4f + rf%d * 2.0) * 0.5 + 0.5;\n"
                        + "        %s = d%d * (1.0 - %.4f + %.4f * sw%d);\n",
                    instance, pointVar, instanceSize,
                    instance, instance,
                    instance,
                    instance,
                    instance, instance,
                    instance, density - 0.2F, density + 0.2F, instance,
                    instance, instance,
                    instance, innerRadius, outerRadius, instance,
                    instance, outerRadius, outerRadius * 1.2F, instance,
                    instance, instance, instance, swirlFreq, instance,
                    maskVar, instance, swirlStrength, swirlStrength, instance
                ));
            }
        ));
    }

    private static void registerBlackHole() {
        List<ShaderModuleDefinition.Parameter> parameters = new ArrayList<>(blackHoleParameters());
        parameters.addAll(List.of(
            parameter("disk_r", "gui.eca.shader_generator.parameter.disk_r", 0.0F, 1.0F, 0.05F, 0.55F),
            parameter("disk_g", "gui.eca.shader_generator.parameter.disk_g", 0.0F, 1.0F, 0.05F, 0.20F),
            parameter("disk_b", "gui.eca.shader_generator.parameter.disk_b", 0.0F, 1.0F, 0.05F, 0.85F),
            parameter("photon_r", "gui.eca.shader_generator.parameter.photon_r", 0.0F, 1.0F, 0.05F, 0.85F),
            parameter("photon_g", "gui.eca.shader_generator.parameter.photon_g", 0.0F, 1.0F, 0.05F, 0.60F),
            parameter("photon_b", "gui.eca.shader_generator.parameter.photon_b", 0.0F, 1.0F, 0.05F, 1.00F),
            parameter("disk_thickness", "gui.eca.shader_generator.parameter.disk_thickness", 0.1F, 1.0F, 0.05F, 0.45F),
            parameter("disk_tilt", "gui.eca.shader_generator.parameter.disk_tilt", 1.0F, 8.0F, 0.1F, 3.5F),
            parameter("disk_rotation_speed", "gui.eca.shader_generator.parameter.disk_rotation_speed", -4.0F, 4.0F, 0.1F, 0.75F),
            parameter("edge_softness", "gui.eca.shader_generator.parameter.edge_softness", 0.01F, 0.5F, 0.01F, 0.10F)
        ));
        register(new ShaderModuleDefinition(
            "black_hole",
            "gui.eca.shader_generator.module.black_hole",
            ShaderModuleDefinition.Category.STARRY_SKY,
            parameters,
            ShaderModuleRegistry::emitBlackHoleInstances
        ));
    }

    /* ========== 环境模块 ========== */

    private static void registerLightning() {
        register(environmentFieldModule(
            "lightning",
            "gui.eca.shader_generator.module.lightning",
            List.of(
                parameter("segments", "gui.eca.shader_generator.parameter.segments", 4.0F, 16.0F, 1.0F, 10.0F),
                parameter("jitter", "gui.eca.shader_generator.parameter.jitter", 0.0F, 1.0F, 0.05F, 0.5F),
                parameter("branch_strength", "gui.eca.shader_generator.parameter.branch_strength", 0.0F, 1.0F, 0.05F, 0.45F)
            ),
            (out, point, mask, size, module, moduleIndex, instance) -> {
                int segments = Math.round(module.value("segments"));
                float jitter = module.value("jitter");
                float branchStrength = module.value("branch_strength");
                out.append(String.format(Locale.ROOT,
                    "        vec2 lightningPos%d_%d = vec2(0.0, %.4f);\n"
                        + "        float lightningMask%d_%d = 0.0;\n",
                    moduleIndex, instance, size * 0.7F, moduleIndex, instance
                ));
                for (int segment = 0; segment < segments; segment++) {
                    float progress = (float) segment / segments;
                    out.append(String.format(Locale.ROOT,
                        "        float lightningOffset%d_%d_%d = (ecaHash(vec2(%.4f, %.4f)) - 0.5) * %.4f;\n"
                            + "        vec2 lightningNext%d_%d_%d = lightningPos%d_%d + vec2(lightningOffset%d_%d_%d, -%.4f);\n"
                            + "        float lightningDistance%d_%d_%d = ecaSegmentDistance(%s, lightningPos%d_%d, lightningNext%d_%d_%d);\n"
                            + "        lightningMask%d_%d += (smoothstep(%.4f, 0.0, lightningDistance%d_%d_%d) * 0.75\n"
                            + "            + smoothstep(%.4f, 0.0, lightningDistance%d_%d_%d)) * %.4f;\n",
                        moduleIndex, instance, segment, module.value("seed") + instance * 13.7F, segment * 7.13F,
                        size * 0.85F * jitter,
                        moduleIndex, instance, segment, moduleIndex, instance, moduleIndex, instance, segment,
                        size * 0.17F,
                        moduleIndex, instance, segment, point, moduleIndex, instance, moduleIndex, instance, segment,
                        moduleIndex, instance, size * 0.11F, moduleIndex, instance, segment,
                        size * 0.035F, moduleIndex, instance, segment, 1.0F - progress * 0.45F
                    ));
                    if (segment > 1 && segment < segments - 2) {
                        out.append(String.format(Locale.ROOT,
                            "        if (ecaHash(vec2(%.4f, %.4f)) > 0.68) {\n"
                                + "            vec2 lightningBranch%d_%d_%d = lightningPos%d_%d + vec2(%.4f, -%.4f);\n"
                                + "            float lightningBranchDistance%d_%d_%d = ecaSegmentDistance(%s, lightningPos%d_%d, lightningBranch%d_%d_%d);\n"
                                + "            lightningMask%d_%d += smoothstep(%.4f, 0.0, lightningBranchDistance%d_%d_%d) * %.4f;\n"
                                + "        }\n",
                            module.value("seed") + instance * 3.1F, segment * 5.23F,
                            moduleIndex, instance, segment, moduleIndex, instance,
                            size * (segment % 2 == 0 ? 0.55F : -0.55F) * jitter,
                            size * 0.34F,
                            moduleIndex, instance, segment, point, moduleIndex, instance, moduleIndex, instance, segment,
                            moduleIndex, instance, size * 0.06F, moduleIndex, instance, segment, branchStrength
                        ));
                    }
                    out.append(String.format(Locale.ROOT,
                        "        lightningPos%d_%d = lightningNext%d_%d_%d;\n",
                        moduleIndex, instance, moduleIndex, instance, segment
                    ));
                }
                out.append(String.format(Locale.ROOT,
                    "        %s = clamp(lightningMask%d_%d, 0.0, 1.0);\n",
                    mask, moduleIndex, instance
                ));
            }
        ));
    }

    private static void registerAurora() {
        register(environmentFieldModule(
            "aurora",
            "gui.eca.shader_generator.module.aurora",
            List.of(
                parameter("frequency", "gui.eca.shader_generator.parameter.frequency", 0.5F, 12.0F, 0.1F, 3.0F),
                parameter("thickness", "gui.eca.shader_generator.parameter.thickness", 0.05F, 1.0F, 0.05F, 0.28F),
                parameter("flow_speed", "gui.eca.shader_generator.parameter.flow_speed", 0.0F, 4.0F, 0.05F, 0.45F)
            ),
            (out, point, mask, size, module, moduleIndex, instance) -> out.append(String.format(Locale.ROOT,
                "        vec2 auroraPoint%d_%d = %s / max(%.4f, 0.001);\n"
                    + "        float auroraTime%d_%d = gameTime * 1200.0 * %.4f;\n"
                    + "        float auroraWave%d_%d = sin(auroraPoint%d_%d.x * %.4f + auroraTime%d_%d) * 0.32\n"
                    + "            + sin(auroraPoint%d_%d.x * %.4f + auroraTime%d_%d * 1.7 + %.4f) * 0.16\n"
                    + "            + (ecaFbm(vec2(auroraPoint%d_%d.x + auroraTime%d_%d * 0.2, %.4f), 4) - 0.5) * 0.45;\n"
                    + "        float auroraBand%d_%d = exp(-pow(auroraPoint%d_%d.y - auroraWave%d_%d, 2.0) / %.6f);\n"
                    + "        float auroraShimmer%d_%d = 0.65 + ecaNoise(auroraPoint%d_%d * 7.0 + auroraTime%d_%d);\n"
                    + "        %s = auroraBand%d_%d * auroraShimmer%d_%d;\n",
                moduleIndex, instance, point, size,
                moduleIndex, instance, module.value("flow_speed"),
                moduleIndex, instance, moduleIndex, instance, module.value("frequency"), moduleIndex, instance,
                moduleIndex, instance, module.value("frequency") * 2.2F, moduleIndex, instance,
                module.value("seed") + instance * 2.7F,
                moduleIndex, instance, moduleIndex, instance, module.value("seed") + instance * 11.0F,
                moduleIndex, instance, moduleIndex, instance, moduleIndex, instance,
                module.value("thickness") * module.value("thickness") * 2.0F,
                moduleIndex, instance, moduleIndex, instance, moduleIndex, instance,
                mask, moduleIndex, instance, moduleIndex, instance
            ))
        ));
    }

    private static void registerFireflies() {
        register(environmentFieldModule(
            "fireflies",
            "gui.eca.shader_generator.module.fireflies",
            List.of(
                parameter("wander", "gui.eca.shader_generator.parameter.wander", 0.0F, 1.0F, 0.05F, 0.35F),
                parameter("glow_strength", "gui.eca.shader_generator.parameter.glow_strength", 0.1F, 3.0F, 0.1F, 1.2F)
            ),
            (out, point, mask, size, module, moduleIndex, instance) -> out.append(String.format(Locale.ROOT,
                "        float fireflyTime%d_%d = gameTime * 1200.0 * (0.8 + ecaHash(vec2(%.4f, %.4f)) * 0.6);\n"
                    + "        vec2 fireflyDrift%d_%d = vec2(sin(fireflyTime%d_%d * 0.7), cos(fireflyTime%d_%d * 0.5)) * %.4f;\n"
                    + "        float fireflyDistance%d_%d = length(%s - fireflyDrift%d_%d);\n"
                    + "        float fireflyBlink%d_%d = smoothstep(0.0, 1.0, 0.3 + 0.7 * sin(fireflyTime%d_%d * 1.3 + %.4f));\n"
                    + "        %s = smoothstep(%.4f, %.4f, fireflyDistance%d_%d) * fireflyBlink%d_%d * %.4f;\n",
                moduleIndex, instance, module.value("seed") + instance * 1.37F, instance * 7.13F,
                moduleIndex, instance, moduleIndex, instance, moduleIndex, instance,
                size * module.value("wander"),
                moduleIndex, instance, point, moduleIndex, instance,
                moduleIndex, instance, moduleIndex, instance, module.value("seed") + instance * 6.28F,
                mask, size * 1.1F, size * 0.18F, moduleIndex, instance, moduleIndex, instance,
                module.value("glow_strength")
            ))
        ));
    }

    private static void registerWaterBubbles() {
        register(environmentFieldModule(
            "water_bubbles",
            "gui.eca.shader_generator.module.water_bubbles",
            List.of(
                parameter("rise_speed", "gui.eca.shader_generator.parameter.rise_speed", 0.0F, 3.0F, 0.05F, 0.55F),
                parameter("sway_amount", "gui.eca.shader_generator.parameter.sway_amount", 0.0F, 1.0F, 0.05F, 0.3F),
                parameter("ring_thickness", "gui.eca.shader_generator.parameter.ring_thickness", 0.02F, 0.5F, 0.01F, 0.12F)
            ),
            (out, point, mask, size, module, moduleIndex, instance) -> out.append(String.format(Locale.ROOT,
                "        float waterBubblePhase%d_%d = fract(gameTime * 1200.0 * %.4f + ecaHash(vec2(%.4f, %.4f)));\n"
                    + "        vec2 waterBubbleCenter%d_%d = vec2(sin(gameTime * 1200.0 * 1.5 + %.4f) * %.4f,\n"
                    + "            (waterBubblePhase%d_%d - 0.5) * %.4f);\n"
                    + "        float waterBubbleRadius%d_%d = %.4f;\n"
                    + "        float waterBubbleDistance%d_%d = length(%s - waterBubbleCenter%d_%d);\n"
                    + "        float waterBubbleRing%d_%d = smoothstep(%.4f, 0.0, abs(waterBubbleDistance%d_%d - waterBubbleRadius%d_%d));\n"
                    + "        %s = waterBubbleRing%d_%d * (0.65 + 0.35 * smoothstep(waterBubbleRadius%d_%d, 0.0, waterBubbleDistance%d_%d));\n",
                moduleIndex, instance, module.value("rise_speed") * 0.001F,
                module.value("seed") + instance * 3.3F, instance * 5.7F,
                moduleIndex, instance, module.value("seed") + instance * 10.0F,
                size * module.value("sway_amount"), moduleIndex, instance, size * 2.6F,
                moduleIndex, instance, size * 0.48F,
                moduleIndex, instance, point, moduleIndex, instance,
                moduleIndex, instance, size * module.value("ring_thickness"), moduleIndex, instance, moduleIndex, instance,
                mask, moduleIndex, instance, moduleIndex, instance, moduleIndex, instance
            ))
        ));
    }

    private static void registerToxicBubbles() {
        register(environmentFieldModule(
            "toxic_bubbles",
            "gui.eca.shader_generator.module.toxic_bubbles",
            List.of(
                parameter("rise_speed", "gui.eca.shader_generator.parameter.rise_speed", 0.0F, 3.0F, 0.05F, 0.45F),
                parameter("sway_amount", "gui.eca.shader_generator.parameter.sway_amount", 0.0F, 1.0F, 0.05F, 0.25F),
                parameter("glow_strength", "gui.eca.shader_generator.parameter.glow_strength", 0.1F, 3.0F, 0.1F, 1.4F)
            ),
            (out, point, mask, size, module, moduleIndex, instance) -> {
                float seed = module.value("seed");
                out.append(String.format(Locale.ROOT,
                    "        float toxicLife%d_%d = 3.0 + ecaHash(vec2(%.4f, %.4f)) * 3.0;\n",
                    moduleIndex, instance, seed + instance * 7.1F, instance * 2.9F
                ));
                out.append(String.format(Locale.ROOT,
                    "        float toxicAge%d_%d = fract(gameTime * 1200.0 * %.4f / toxicLife%d_%d + ecaHash(vec2(%.4f, %.4f)));\n",
                    moduleIndex, instance, module.value("rise_speed") * 0.001F, moduleIndex, instance,
                    seed + instance * 4.3F, instance * 8.2F
                ));
                out.append(String.format(Locale.ROOT,
                    "        float toxicFade%d_%d = smoothstep(0.0, 0.12, toxicAge%d_%d)\n"
                        + "            * (1.0 - smoothstep(0.7, 1.0, toxicAge%d_%d));\n",
                    moduleIndex, instance, moduleIndex, instance, moduleIndex, instance
                ));
                out.append(String.format(Locale.ROOT,
                    "        vec2 toxicCenter%d_%d = vec2(sin(toxicAge%d_%d * 6.2831853 + %.4f) * %.4f,\n"
                        + "            (toxicAge%d_%d - 0.5) * %.4f);\n",
                    moduleIndex, instance, moduleIndex, instance, seed + instance,
                    size * module.value("sway_amount"), moduleIndex, instance, size * 2.4F
                ));
                out.append(String.format(Locale.ROOT,
                    "        float toxicDistance%d_%d = length(%s - toxicCenter%d_%d);\n"
                        + "        %s = pow(smoothstep(%.4f, 0.0, toxicDistance%d_%d), 2.0) * toxicFade%d_%d * %.4f;\n",
                    moduleIndex, instance, point, moduleIndex, instance,
                    mask, size * 0.7F, moduleIndex, instance, moduleIndex, instance, module.value("glow_strength")
                ));
            }
        ));
    }

    private static void registerRainStreaks() {
        register(environmentFieldModule(
            "rain_streaks",
            "gui.eca.shader_generator.module.rain_streaks",
            List.of(
                parameter("fall_speed", "gui.eca.shader_generator.parameter.fall_speed", 0.0F, 4.0F, 0.05F, 1.2F),
                parameter("streak_length", "gui.eca.shader_generator.parameter.streak_length", 0.1F, 2.0F, 0.05F, 0.65F),
                parameter("streak_width", "gui.eca.shader_generator.parameter.streak_width", 0.01F, 0.5F, 0.01F, 0.08F)
            ),
            (out, point, mask, size, module, moduleIndex, instance) -> out.append(String.format(Locale.ROOT,
                "        float rainPhase%d_%d = fract(gameTime * 1200.0 * %.4f + %.4f);\n"
                    + "        vec2 rainCenter%d_%d = vec2(sin(rainPhase%d_%d * 9.0 + %.4f) * %.4f,\n"
                    + "            (rainPhase%d_%d - 0.5) * %.4f);\n"
                    + "        %s = smoothstep(%.4f, 0.0, ecaSegmentDistance(%s, rainCenter%d_%d,\n"
                    + "            rainCenter%d_%d + vec2(%.4f, %.4f)));\n",
                moduleIndex, instance, module.value("fall_speed") * 0.001F, module.value("seed") + instance * 3.7F,
                moduleIndex, instance, moduleIndex, instance, module.value("seed") + instance, size * 0.20F,
                moduleIndex, instance, size * 2.5F,
                mask, size * module.value("streak_width"), point, moduleIndex, instance,
                moduleIndex, instance, size * 0.05F, size * module.value("streak_length")
            ))
        ));
    }

    private static void registerSnowfall() {
        register(environmentFieldModule(
            "snowfall",
            "gui.eca.shader_generator.module.snowfall",
            List.of(
                parameter("fall_speed", "gui.eca.shader_generator.parameter.fall_speed", 0.0F, 3.0F, 0.05F, 0.38F),
                parameter("wind_amount", "gui.eca.shader_generator.parameter.wind_amount", 0.0F, 1.0F, 0.05F, 0.22F),
                parameter("flake_softness", "gui.eca.shader_generator.parameter.flake_softness", 0.1F, 1.0F, 0.05F, 0.65F)
            ),
            (out, point, mask, size, module, moduleIndex, instance) -> out.append(String.format(Locale.ROOT,
                "        float snowPhase%d_%d = fract(gameTime * 1200.0 * %.4f + %.4f);\n"
                    + "        vec2 snowCenter%d_%d = vec2(sin(snowPhase%d_%d * 8.0 + %.4f) * %.4f,\n"
                    + "            (snowPhase%d_%d - 0.5) * %.4f);\n"
                    + "        float snowDistance%d_%d = length(%s - snowCenter%d_%d);\n"
                    + "        %s = pow(smoothstep(%.4f, %.4f, snowDistance%d_%d), %.4f);\n",
                moduleIndex, instance, module.value("fall_speed") * 0.001F, module.value("seed") + instance * 6.1F,
                moduleIndex, instance, moduleIndex, instance, module.value("seed") + instance, size * module.value("wind_amount"),
                moduleIndex, instance, size * 2.4F,
                moduleIndex, instance, point, moduleIndex, instance,
                mask, size, size * (1.0F - module.value("flake_softness") * 0.75F), moduleIndex, instance,
                1.0F + module.value("flake_softness") * 2.0F
            ))
        ));
    }

    private static void registerFallingLeaves() {
        register(environmentFieldModule(
            "falling_leaves",
            "gui.eca.shader_generator.module.falling_leaves",
            List.of(
                parameter("fall_speed", "gui.eca.shader_generator.parameter.fall_speed", 0.0F, 3.0F, 0.05F, 0.30F),
                parameter("flutter", "gui.eca.shader_generator.parameter.flutter", 0.0F, 1.0F, 0.05F, 0.55F),
                parameter("leaf_width", "gui.eca.shader_generator.parameter.leaf_width", 0.2F, 2.0F, 0.05F, 0.75F)
            ),
            (out, point, mask, size, module, moduleIndex, instance) -> out.append(String.format(Locale.ROOT,
                "        float leafPhase%d_%d = fract(gameTime * 1200.0 * %.4f + %.4f);\n"
                    + "        float leafAngle%d_%d = leafPhase%d_%d * 10.0 + %.4f;\n"
                    + "        vec2 leafCenter%d_%d = vec2(sin(leafAngle%d_%d) * %.4f, (leafPhase%d_%d - 0.5) * %.4f);\n"
                    + "        vec2 leafPoint%d_%d = ecaRotate(%s - leafCenter%d_%d, leafAngle%d_%d * %.4f);\n"
                    + "        %s = smoothstep(1.0, 0.72, length(vec2(leafPoint%d_%d.x / %.4f, leafPoint%d_%d.y / %.4f)));\n",
                moduleIndex, instance, module.value("fall_speed") * 0.001F, module.value("seed") + instance * 8.3F,
                moduleIndex, instance, moduleIndex, instance, module.value("seed") + instance,
                moduleIndex, instance, moduleIndex, instance, size * module.value("flutter"), moduleIndex, instance, size * 2.3F,
                moduleIndex, instance, point, moduleIndex, instance, moduleIndex, instance, module.value("flutter"),
                mask, moduleIndex, instance, size * module.value("leaf_width"), moduleIndex, instance, size
            ))
        ));
    }

    private static void registerMagmaDebris() {
        register(environmentFieldModule(
            "magma_debris",
            "gui.eca.shader_generator.module.magma_debris",
            List.of(
                parameter("drift_speed", "gui.eca.shader_generator.parameter.drift_speed", 0.0F, 3.0F, 0.05F, 0.35F),
                parameter("fragment_sides", "gui.eca.shader_generator.parameter.fragment_sides", 3.0F, 8.0F, 1.0F, 5.0F),
                parameter("glow_strength", "gui.eca.shader_generator.parameter.glow_strength", 0.1F, 3.0F, 0.1F, 1.25F)
            ),
            (out, point, mask, size, module, moduleIndex, instance) -> out.append(String.format(Locale.ROOT,
                "        vec2 magmaPoint%d_%d = %s - vec2(sin(gameTime * 1200.0 * %.4f + %.4f) * %.4f,\n"
                    + "            cos(gameTime * 1200.0 * %.4f + %.4f) * %.4f);\n"
                    + "        float magmaDistance%d_%d = ecaPolygonDistance(magmaPoint%d_%d, %.4f, %.1f, %.4f);\n"
                    + "        %s = smoothstep(%.4f, 0.0, magmaDistance%d_%d) * %.4f;\n",
                moduleIndex, instance, point, module.value("drift_speed") * 0.001F, module.value("seed") + instance,
                size * 0.35F, module.value("drift_speed") * 0.0007F, module.value("seed") + instance * 2.3F, size * 0.25F,
                moduleIndex, instance, moduleIndex, instance, size, module.value("fragment_sides"), module.value("seed"),
                mask, size * 0.12F, moduleIndex, instance, module.value("glow_strength")
            ))
        ));
    }

    private static void registerDustHaze() {
        register(environmentFieldModule(
            "dust_haze",
            "gui.eca.shader_generator.module.dust_haze",
            List.of(
                parameter("flow_speed", "gui.eca.shader_generator.parameter.flow_speed", 0.0F, 4.0F, 0.05F, 0.42F),
                parameter("density", "gui.eca.shader_generator.parameter.density", 0.1F, 3.0F, 0.05F, 1.0F),
                parameter("haze_softness", "gui.eca.shader_generator.parameter.haze_softness", 0.1F, 1.0F, 0.05F, 0.65F)
            ),
            (out, point, mask, size, module, moduleIndex, instance) -> out.append(String.format(Locale.ROOT,
                "        vec2 dustPoint%d_%d = %s + vec2(gameTime * 1200.0 * %.4f, 0.0);\n"
                    + "        float dustNoise%d_%d = ecaFbm(dustPoint%d_%d / max(%.4f, 0.001) + %.4f, 4);\n"
                    + "        float dustCloud%d_%d = smoothstep(%.4f, %.4f, dustNoise%d_%d);\n"
                    + "        %s = dustCloud%d_%d * %.4f;\n",
                moduleIndex, instance, point, module.value("flow_speed") * 0.001F,
                moduleIndex, instance, moduleIndex, instance, size * 2.2F, module.value("seed") + instance,
                moduleIndex, instance, 1.0F - module.value("haze_softness"), 1.0F - module.value("haze_softness") * 0.35F,
                moduleIndex, instance, mask, moduleIndex, instance, module.value("density")
            ))
        ));
    }

    private static void registerDigitalRain() {
        register(environmentFieldModule(
            "digital_rain",
            "gui.eca.shader_generator.module.digital_rain",
            List.of(
                parameter("fall_speed", "gui.eca.shader_generator.parameter.fall_speed", 0.0F, 4.0F, 0.05F, 0.85F),
                parameter("trail_length", "gui.eca.shader_generator.parameter.trail_length", 0.1F, 2.0F, 0.05F, 0.70F),
                parameter("glyph_width", "gui.eca.shader_generator.parameter.glyph_width", 0.01F, 0.5F, 0.01F, 0.10F)
            ),
            (out, point, mask, size, module, moduleIndex, instance) -> out.append(String.format(Locale.ROOT,
                "        float digitalPhase%d_%d = fract(gameTime * 1200.0 * %.4f + %.4f);\n"
                    + "        vec2 digitalHead%d_%d = vec2(0.0, (digitalPhase%d_%d - 0.5) * %.4f);\n"
                    + "        float digitalLine%d_%d = smoothstep(%.4f, 0.0, ecaSegmentDistance(%s, digitalHead%d_%d,\n"
                    + "            digitalHead%d_%d + vec2(0.0, %.4f)));\n"
                    + "        float digitalBlink%d_%d = 0.45 + 0.55 * sin(gameTime * 1200.0 * 4.0 + %.4f);\n"
                    + "        %s = digitalLine%d_%d * digitalBlink%d_%d;\n",
                moduleIndex, instance, module.value("fall_speed") * 0.001F, module.value("seed") + instance * 4.1F,
                moduleIndex, instance, moduleIndex, instance, size * 2.5F,
                moduleIndex, instance, size * module.value("glyph_width"), point, moduleIndex, instance,
                moduleIndex, instance, size * module.value("trail_length"),
                moduleIndex, instance, module.value("seed") + instance,
                mask, moduleIndex, instance, moduleIndex, instance
            ))
        ));
    }

    /* ========== 魔法模块数据 ========== */

    /* 24 Elder Futhark 卢恩字母线段数据（arcane.fsh 2.5x 坐标空间） */
    private static final float[][][] RUNE_SEGMENTS = {
        { // 0 Fehu ᚠ
            {0, -0.5f, 0, 0.5f},
            {0, 0.5f, 0.3f, 0.25f},
            {0, 0.15f, 0.25f, -0.05f}
        },
        { // 1 Uruz ᚢ
            {-0.15f, 0.5f, -0.15f, -0.3f},
            {-0.15f, -0.3f, 0.15f, -0.5f},
            {0.15f, -0.5f, 0.15f, 0.5f}
        },
        { // 2 Thurisaz ᚦ
            {0, -0.5f, 0, 0.5f},
            {0, 0.3f, 0.3f, 0.0f},
            {0.3f, 0.0f, 0, -0.15f}
        },
        { // 3 Ansuz ᚨ
            {0, -0.5f, 0, 0.5f},
            {0, 0.3f, 0.3f, 0.0f},
            {0, 0.0f, 0.3f, -0.3f}
        },
        { // 4 Raidho ᚱ
            {0, -0.5f, 0, 0.5f},
            {0, 0.5f, 0.25f, 0.25f},
            {0.25f, 0.25f, 0, 0.05f},
            {0, 0.05f, 0.3f, -0.5f}
        },
        { // 5 Kenaz ᚲ
            {0.2f, 0.5f, -0.1f, 0.0f},
            {-0.1f, 0.0f, 0.2f, -0.5f}
        },
        { // 6 Gebo ᚷ
            {-0.3f, -0.4f, 0.3f, 0.4f},
            {-0.3f, 0.4f, 0.3f, -0.4f}
        },
        { // 7 Wunjo ᚹ
            {0, -0.5f, 0, 0.5f},
            {0, 0.5f, 0.25f, 0.25f},
            {0.25f, 0.25f, 0, 0.1f}
        },
        { // 8 Hagalaz ᚺ
            {-0.15f, -0.5f, -0.15f, 0.5f},
            {0.15f, -0.5f, 0.15f, 0.5f},
            {-0.15f, 0.1f, 0.15f, -0.1f}
        },
        { // 9 Nauthiz ᚾ
            {0, -0.5f, 0, 0.5f},
            {-0.2f, 0.2f, 0.2f, -0.2f}
        },
        { // 10 Isa ᛁ
            {0, -0.5f, 0, 0.5f}
        },
        { // 11 Jera ᛃ
            {-0.05f, 0.5f, 0.2f, 0.15f},
            {0.2f, 0.15f, -0.05f, 0.0f},
            {0.05f, 0.0f, -0.2f, -0.15f},
            {-0.2f, -0.15f, 0.05f, -0.5f}
        },
        { // 12 Eihwaz ᛇ
            {0, -0.5f, 0, 0.5f},
            {0, 0.2f, 0.25f, 0.45f},
            {0, -0.2f, -0.25f, -0.45f}
        },
        { // 13 Perthro ᛈ
            {-0.1f, -0.5f, -0.1f, 0.5f},
            {-0.1f, 0.5f, 0.2f, 0.2f},
            {0.2f, 0.2f, 0.2f, -0.2f},
            {0.2f, -0.2f, -0.1f, -0.5f}
        },
        { // 14 Algiz ᛉ
            {0, -0.5f, 0, 0.3f},
            {0, 0.3f, 0.25f, 0.5f},
            {0, 0.3f, -0.25f, 0.5f}
        },
        { // 15 Sowilo ᛊ
            {-0.15f, 0.5f, 0.15f, 0.15f},
            {0.15f, 0.15f, -0.15f, -0.15f},
            {-0.15f, -0.15f, 0.15f, -0.5f}
        },
        { // 16 Tiwaz ᛏ
            {0, -0.5f, 0, 0.5f},
            {-0.25f, 0.25f, 0, 0.5f},
            {0, 0.5f, 0.25f, 0.25f}
        },
        { // 17 Berkano ᛒ
            {0, -0.5f, 0, 0.5f},
            {0, 0.5f, 0.25f, 0.25f},
            {0.25f, 0.25f, 0, 0.0f},
            {0, 0.0f, 0.25f, -0.25f},
            {0.25f, -0.25f, 0, -0.5f}
        },
        { // 18 Ehwaz ᛖ
            {-0.15f, -0.5f, -0.15f, 0.5f},
            {-0.15f, 0.5f, 0.15f, 0.0f},
            {0.15f, 0.0f, -0.15f, -0.5f}
        },
        { // 19 Mannaz ᛗ
            {-0.15f, -0.5f, -0.15f, 0.5f},
            {0.15f, -0.5f, 0.15f, 0.5f},
            {-0.15f, 0.5f, 0.0f, 0.2f},
            {0.0f, 0.2f, 0.15f, 0.5f},
            {-0.15f, 0.0f, 0.15f, 0.0f}
        },
        { // 20 Laguz ᛚ
            {0, -0.5f, 0, 0.5f},
            {0, 0.5f, 0.25f, 0.2f}
        },
        { // 21 Ingwaz ᛜ
            {0, 0.4f, 0.25f, 0.0f},
            {0.25f, 0.0f, 0, -0.4f},
            {0, -0.4f, -0.25f, 0.0f},
            {-0.25f, 0.0f, 0, 0.4f}
        },
        { // 22 Dagaz ᛞ
            {-0.2f, 0.4f, 0.2f, 0.4f},
            {-0.2f, -0.4f, 0.2f, -0.4f},
            {-0.2f, 0.4f, 0.2f, -0.4f},
            {0.2f, 0.4f, -0.2f, -0.4f}
        },
        { // 23 Othala ᛟ
            {-0.15f, -0.5f, -0.15f, 0.0f},
            {0.15f, -0.5f, 0.15f, 0.0f},
            {-0.15f, 0.0f, 0, 0.25f},
            {0.15f, 0.0f, 0, 0.25f},
            {0, 0.25f, 0, 0.5f}
        }
    };

    /* 8 行星/炼金符号 SDF 表达式（arcane.fsh 2.0x 坐标空间），%1$s 为点变量 */
    private static final String[] PLANET_SDF = {
        /* 0 Sun ☉ */
        "min(abs(length(%1$s) - 0.35) - 0.02, length(%1$s) - 0.08)",
        /* 1 Moon ☽ */
        "max(abs(length(%1$s) - 0.3) - 0.02, -(length(%1$s - vec2(0.15, 0.0)) - 0.25))",
        /* 2 Mercury ☿ */
        "min(min(min(abs(length(%1$s - vec2(0.0, 0.05)) - 0.2) - 0.02,"
            + " ecaSegmentDistance(%1$s, vec2(0.0, -0.15), vec2(0.0, -0.45))),"
            + " ecaSegmentDistance(%1$s, vec2(-0.15, -0.3), vec2(0.15, -0.3))),"
            + " ecaArcDistance(%1$s - vec2(0.0, 0.25), 0.15, 0.3, 2.84159265359))",
        /* 3 Venus ♀ */
        "min(min(abs(length(%1$s - vec2(0.0, 0.15)) - 0.22) - 0.02,"
            + " ecaSegmentDistance(%1$s, vec2(0.0, -0.07), vec2(0.0, -0.45))),"
            + " ecaSegmentDistance(%1$s, vec2(-0.15, -0.25), vec2(0.15, -0.25)))",
        /* 4 Mars ♂ */
        "min(min(min(abs(length(%1$s - vec2(-0.08, -0.08)) - 0.22) - 0.02,"
            + " ecaSegmentDistance(%1$s, vec2(0.08, 0.08), vec2(0.35, 0.35))),"
            + " ecaSegmentDistance(%1$s, vec2(0.35, 0.35), vec2(0.35, 0.15))),"
            + " ecaSegmentDistance(%1$s, vec2(0.35, 0.35), vec2(0.15, 0.35)))",
        /* 5 Jupiter ♃ */
        "min(min(ecaSegmentDistance(%1$s, vec2(-0.3, 0.0), vec2(0.3, 0.0)),"
            + " ecaSegmentDistance(%1$s, vec2(0.15, 0.4), vec2(0.15, -0.4))),"
            + " ecaArcDistance(%1$s - vec2(-0.1, 0.2), 0.2, -1.57079632679, 1.57079632679))",
        /* 6 Saturn ♄ */
        "min(min(min(ecaSegmentDistance(%1$s, vec2(-0.1, 0.45), vec2(0.15, 0.45)),"
            + " ecaSegmentDistance(%1$s, vec2(0.0, 0.45), vec2(0.0, -0.1))),"
            + " ecaArcDistance(%1$s - vec2(0.15, -0.1), 0.15, -1.57079632679, 1.57079632679)),"
            + " ecaSegmentDistance(%1$s, vec2(0.15, -0.25), vec2(-0.1, -0.45)))",
        /* 7 Uranus ♅ */
        "min(min(min(min(min(abs(length(%1$s - vec2(0.0, -0.25)) - 0.12) - 0.02,"
            + " ecaSegmentDistance(%1$s, vec2(0.0, -0.13), vec2(0.0, 0.35))),"
            + " ecaSegmentDistance(%1$s, vec2(-0.2, 0.35), vec2(0.2, 0.35))),"
            + " ecaSegmentDistance(%1$s, vec2(-0.2, 0.35), vec2(-0.2, 0.2))),"
            + " ecaSegmentDistance(%1$s, vec2(0.2, 0.35), vec2(0.2, 0.2))),"
            + " length(%1$s - vec2(0.0, 0.45)) - 0.05)"
    };

    private static String buildRuneSdf(String pointVar, int runeIndex) {
        float[][] segments = RUNE_SEGMENTS[runeIndex];
        String expr = String.format(Locale.ROOT,
            "ecaSegmentDistance(%s, vec2(%.4f, %.4f), vec2(%.4f, %.4f))",
            pointVar, segments[segments.length - 1][0], segments[segments.length - 1][1],
            segments[segments.length - 1][2], segments[segments.length - 1][3]);
        for (int i = segments.length - 2; i >= 0; i--) {
            expr = String.format(Locale.ROOT,
                "min(ecaSegmentDistance(%s, vec2(%.4f, %.4f), vec2(%.4f, %.4f)), %s)",
                pointVar, segments[i][0], segments[i][1], segments[i][2], segments[i][3], expr);
        }
        return expr;
    }

    /* 卢恩字母模块：rune_index (0-23) 选择 Elder Futhark 卢恩字符 */
    private static void registerRune() {
        register(shaped(
            "rune",
            "gui.eca.shader_generator.module.rune",
            ShaderModuleDefinition.Category.MAGIC,
            List.of(parameter("rune_index", "gui.eca.shader_generator.parameter.rune_index",
                0.0F, 23.0F, 1.0F, 0.0F)),
            (module, index, point, size) -> {
                int runeIdx = Math.round(module.value("rune_index"));
                String rp = String.format(Locale.ROOT, "(%s / %.4f)", point, size * 2.0F);
                String sdf = buildRuneSdf(rp, runeIdx);
                return String.format(Locale.ROOT, "smoothstep(0.05, 0.0, %s)", sdf);
            }
        ));
    }

    /* 行星符号模块：symbol_index (0-7) 选择炼金/行星符号 */
    private static void registerPlanetSymbol() {
        register(shaped(
            "planet_symbol",
            "gui.eca.shader_generator.module.planet_symbol",
            ShaderModuleDefinition.Category.MAGIC,
            List.of(parameter("symbol_index", "gui.eca.shader_generator.parameter.symbol_index",
                0.0F, 7.0F, 1.0F, 0.0F)),
            (module, index, point, size) -> {
                int symIdx = Math.round(module.value("symbol_index"));
                String rp = String.format(Locale.ROOT, "(%s / %.4f)", point, size * 2.5F);
                String sdf = String.format(PLANET_SDF[symIdx], rp);
                return String.format(Locale.ROOT, "smoothstep(0.05, 0.0, %s)", sdf);
            }
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
        float rotation = (float) Math.toRadians(module.value("rotation"));
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
            float offsetX = 0.0F;
            float offsetY = 0.0F;
            if (instance > 0) {
                float spreadFactor = (float) instance / (count - 1);
                float effectiveSpreadX = spreadX > 0.001F ? spreadX : size;
                float effectiveSpreadY = spreadY > 0.001F ? spreadY : size;
                offsetX = signedRandom(seed, instance, 17.13F) * effectiveSpreadX * spreadFactor;
                offsetY = signedRandom(seed, instance, 71.91F) * effectiveSpreadY * spreadFactor;
            }
            float randomSize = 0.75F + unitRandom(seed, instance, 41.37F) * 0.5F;
            float instanceSize = size * randomSize;
            String point = "point" + moduleIndex + "_" + instance;
            String mask = "mask" + moduleIndex + "_" + instance;
            source.append(String.format(Locale.ROOT,
                "        vec2 %s = ecaRotate(effectUv - vec2(%.4f, %.4f), %.6f);\n",
                point,
                centerX + offsetX,
                centerY + offsetY,
                -rotation
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

    private static String emitBlackHoleInstances(ShaderModuleInstance module, int moduleIndex) {
        int count = Math.max(1, Math.round(module.value("count")));
        float size = module.value("size");
        float centerX = module.value("center_x");
        float centerY = module.value("center_y");
        float spreadX = module.value("spread_x");
        float spreadY = module.value("spread_y");
        float seed = module.value("seed");
        float rotation = (float) Math.toRadians(module.value("rotation"));
        StringBuilder source = new StringBuilder();
        source.append(String.format(Locale.ROOT,
            "        float effectProgress%d = ecaEffectProgress(gameTime, %.4f, %.4f);\n"
                + "        float effectAlphaScale%d = effectProgress%d < 0.0 ? 0.0 : %.4f + %.4f * effectProgress%d;\n",
            moduleIndex, module.value("duration"), module.value("repeat_interval"),
            moduleIndex, moduleIndex, module.value("start_alpha"),
            module.value("end_alpha") - module.value("start_alpha"), moduleIndex
        ));
        for (int instance = 0; instance < count; instance++) {
            float spreadFactor = count == 1 ? 0.0F : (float) instance / (count - 1);
            float offsetX = signedRandom(seed, instance, 17.13F)
                * (spreadX > 0.001F ? spreadX : size) * spreadFactor;
            float offsetY = signedRandom(seed, instance, 71.91F)
                * (spreadY > 0.001F ? spreadY : size) * spreadFactor;
            float instanceSize = size * (0.75F + unitRandom(seed, instance, 41.37F) * 0.5F);
            float horizonRadius = instanceSize * 0.68F;
            float outerRadius = instanceSize * (1.4F + module.value("disk_thickness"));
            String suffix = moduleIndex + "_" + instance;
            source.append(String.format(Locale.ROOT,
                "        vec2 blackHolePoint%s = ecaRotate(effectUv - vec2(%.4f, %.4f), %.6f);\n"
                    + "        float blackHoleDistance%s = length(blackHolePoint%s);\n"
                    + "        float eventHorizon%s = 1.0 - smoothstep(%.4f, %.4f, blackHoleDistance%s);\n"
                    + "        vec2 diskPoint%s = vec2(blackHolePoint%s.x, blackHolePoint%s.y * %.4f);\n"
                    + "        float diskDistance%s = length(diskPoint%s);\n"
                    + "        float accretionDisk%s = smoothstep(%.4f, %.4f, diskDistance%s)\n"
                    + "            * (1.0 - smoothstep(%.4f, %.4f, diskDistance%s));\n"
                    + "        float diskFlow%s = 0.65 + 0.35 * sin(atan(diskPoint%s.y, diskPoint%s.x) * 6.0\n"
                    + "            + diskDistance%s * 8.0 + gameTime * 1200.0 * %.4f);\n"
                    + "        float photonRing%s = exp(-pow(blackHoleDistance%s - %.4f, 2.0) / %.6f);\n"
                    + "        float blackHoleAlpha%s = effectAlphaScale%d * %.4f;\n"
                    + "        color += vec3(%.4f, %.4f, %.4f) * eventHorizon%s * blackHoleAlpha%s;\n"
                    + "        color += vec3(%.4f, %.4f, %.4f) * accretionDisk%s * diskFlow%s * blackHoleAlpha%s;\n"
                    + "        color += vec3(%.4f, %.4f, %.4f) * photonRing%s * blackHoleAlpha%s;\n"
                    + "        alpha = max(alpha, max(eventHorizon%s, max(accretionDisk%s, photonRing%s)) * blackHoleAlpha%s);\n",
                suffix, centerX + offsetX, centerY + offsetY, -rotation,
                suffix, suffix,
                suffix, horizonRadius, horizonRadius + instanceSize * module.value("edge_softness"), suffix,
                suffix, suffix, suffix, module.value("disk_tilt"),
                suffix, suffix,
                suffix, horizonRadius, horizonRadius + instanceSize * 0.12F, suffix,
                outerRadius - instanceSize * 0.18F, outerRadius, suffix,
                suffix, suffix, suffix, suffix, module.value("disk_rotation_speed"),
                suffix, suffix, horizonRadius, instanceSize * 0.012F,
                suffix, moduleIndex, module.value("color_a"),
                module.value("color_r"), module.value("color_g"), module.value("color_b"), suffix, suffix,
                module.value("disk_r"), module.value("disk_g"), module.value("disk_b"), suffix, suffix, suffix,
                module.value("photon_r"), module.value("photon_g"), module.value("photon_b"), suffix, suffix,
                suffix, suffix, suffix, suffix
            ));
        }
        return source.toString();
    }

    private static List<ShaderModuleDefinition.Parameter> blackHoleParameters() {
        List<ShaderModuleDefinition.Parameter> parameters = new ArrayList<>(commonParameters());
        parameters.set(0, parameter("color_r", "gui.eca.shader_generator.parameter.horizon_r", 0.0F, 1.0F, 0.05F, 0.01F));
        parameters.set(1, parameter("color_g", "gui.eca.shader_generator.parameter.horizon_g", 0.0F, 1.0F, 0.05F, 0.005F));
        parameters.set(2, parameter("color_b", "gui.eca.shader_generator.parameter.horizon_b", 0.0F, 1.0F, 0.05F, 0.02F));
        parameters.set(3, parameter("color_a", "gui.eca.shader_generator.parameter.horizon_a", 0.0F, 1.0F, 0.05F, 1.0F));
        return parameters;
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
            parameter("rotation", "gui.eca.shader_generator.parameter.rotation", 0.0F, 360.0F, 5.0F, 0.0F),
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
