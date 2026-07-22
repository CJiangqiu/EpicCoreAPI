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
        List<ShaderModuleDefinition.Parameter> parameters = new ArrayList<>(commonParameters());
        parameters.addAll(specificParameters);
        return new ShaderModuleDefinition(
            id,
            displayName,
            ShaderModuleDefinition.Category.STARRY_SKY,
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
