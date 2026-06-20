package net.eca.util.shader_generator;

/* Photoshop 式图层混合模式，每种模式自带 GLSL 函数实现。
   混合计算在片段着色器内完成，不依赖 OpenGL blend state，导出产物可移植。 */
public enum ShaderLayerBlendMode {
    NORMAL("gui.eca.shader_generator.blend_mode.normal") {
        @Override
        public String glslFunction() {
            return "vec3 eca_blend_normal(vec3 base, vec3 layer) { return layer; }";
        }
    },
    MULTIPLY("gui.eca.shader_generator.blend_mode.multiply") {
        @Override
        public String glslFunction() {
            return "vec3 eca_blend_multiply(vec3 base, vec3 layer) { return base * layer; }";
        }
    },
    SCREEN("gui.eca.shader_generator.blend_mode.screen") {
        @Override
        public String glslFunction() {
            return "vec3 eca_blend_screen(vec3 base, vec3 layer) { return 1.0 - (1.0 - base) * (1.0 - layer); }";
        }
    },
    OVERLAY("gui.eca.shader_generator.blend_mode.overlay") {
        @Override
        public String glslFunction() {
            return "vec3 eca_blend_overlay(vec3 base, vec3 layer) { return mix(2.0 * base * layer, 1.0 - 2.0 * (1.0 - base) * (1.0 - layer), step(0.5, base)); }";
        }
    },
    ADD("gui.eca.shader_generator.blend_mode.add") {
        @Override
        public String glslFunction() {
            return "vec3 eca_blend_add(vec3 base, vec3 layer) { return base + layer; }";
        }
    },
    SUBTRACT("gui.eca.shader_generator.blend_mode.subtract") {
        @Override
        public String glslFunction() {
            return "vec3 eca_blend_subtract(vec3 base, vec3 layer) { return max(base - layer, 0.0); }";
        }
    },
    DARKEN("gui.eca.shader_generator.blend_mode.darken") {
        @Override
        public String glslFunction() {
            return "vec3 eca_blend_darken(vec3 base, vec3 layer) { return min(base, layer); }";
        }
    },
    LIGHTEN("gui.eca.shader_generator.blend_mode.lighten") {
        @Override
        public String glslFunction() {
            return "vec3 eca_blend_lighten(vec3 base, vec3 layer) { return max(base, layer); }";
        }
    };

    private final String translationKey;

    ShaderLayerBlendMode(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }

    public String glslFunctionName() {
        return "eca_blend_" + name().toLowerCase();
    }

    public abstract String glslFunction();

    public static String generateAllGlslFunctions() {
        StringBuilder result = new StringBuilder();
        for (ShaderLayerBlendMode mode : values()) {
            result.append(mode.glslFunction()).append('\n');
        }
        return result.append('\n').toString();
    }
}
