package net.eca.client.render.shader_generator;

public enum ShaderPreviewTarget {
    PLANE("gui.eca.shader_generator.target.plane"),
    ITEM("gui.eca.shader_generator.target.item"),
    ENTITY("gui.eca.shader_generator.target.entity"),
    SKYBOX("gui.eca.shader_generator.target.skybox"),
    BOSS_BAR("gui.eca.shader_generator.target.boss_bar");

    private final String translationKey;

    ShaderPreviewTarget(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }

    public ShaderPreviewTarget next() {
        ShaderPreviewTarget[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
