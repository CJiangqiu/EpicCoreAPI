package net.eca.util.entity_extension;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

public interface GlobalSkyboxExtension {

    default boolean enabled() {
        return false;
    }

    default boolean enableTexture() {
        return false;
    }

    default ResourceLocation texture() {
        return null;
    }

    default boolean enableShader() {
        return false;
    }

    default RenderType shaderRenderType() {
        return null;
    }

    default float alpha() {
        return 0.6f;
    }

    default float size() {
        return 100.0f;
    }

    default float textureUvScale() {
        return 16.0f;
    }

    default float textureRed() {
        return 1.0f;
    }

    default float textureGreen() {
        return 1.0f;
    }

    default float textureBlue() {
        return 1.0f;
    }
}
