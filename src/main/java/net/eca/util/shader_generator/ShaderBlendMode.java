package net.eca.util.shader_generator;

/* 图层混合模式。source 为本图层颜色，backdrop 为下方图层累积色，最终按图层 alpha 加权。
   NORMAL 退化为直接 alpha 叠加，与历史行为一致。 */
public enum ShaderBlendMode {

    NORMAL("gui.eca.shader_generator.blend.normal"),
    ADD("gui.eca.shader_generator.blend.add"),
    MULTIPLY("gui.eca.shader_generator.blend.multiply"),
    SCREEN("gui.eca.shader_generator.blend.screen"),
    OVERLAY("gui.eca.shader_generator.blend.overlay");

    private final String displayKey;

    ShaderBlendMode(String displayKey) {
        this.displayKey = displayKey;
    }

    public String displayKey() {
        return displayKey;
    }

    public ShaderBlendMode next() {
        ShaderBlendMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /* 序列化名未知或缺失时回退 NORMAL，保证旧工程兼容。 */
    public static ShaderBlendMode fromName(String name) {
        if (name == null) {
            return NORMAL;
        }
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return NORMAL;
        }
    }
}
