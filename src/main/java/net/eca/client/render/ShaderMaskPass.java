package net.eca.client.render;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Describes one ordered shader overlay draw with an optional color-selected mask.
 * A model may be redrawn with multiple passes to assign different shaders to different mask colors.
 *
 * @param renderType shader RenderType whose vertex format must match the rendering domain
 * @param maskSource source sampled by {@code MaskSampler}
 * @param maskTexture external mask texture when {@code maskSource} is {@link ShaderMaskSource#TEXTURE}
 * @param maskColor packed RGB target color; alpha bits are ignored
 * @param maskTolerance maximum normalized RGB distance from {@code maskColor}
 * @param alpha overlay opacity in the inclusive range {@code 0..1}
 */
@OnlyIn(Dist.CLIENT)
public record ShaderMaskPass(
    RenderType renderType,
    ShaderMaskSource maskSource,
    ResourceLocation maskTexture,
    int maskColor,
    float maskTolerance,
    float alpha
) {

    public ShaderMaskPass {
        if (renderType == null) {
            throw new IllegalArgumentException("RenderType cannot be null");
        }
        maskSource = maskSource == null ? ShaderMaskSource.NONE : maskSource;
        if (maskSource == ShaderMaskSource.TEXTURE && maskTexture == null) {
            throw new IllegalArgumentException("Texture mask source requires a texture");
        }
        maskColor &= 0xFFFFFF;
        maskTolerance = Math.max(0.0f, maskTolerance);
        alpha = Math.max(0.0f, Math.min(1.0f, alpha));
    }

    /**
     * Creates a pass without color selection.
     *
     * @param renderType shader RenderType
     * @param alpha overlay opacity
     * @return unmasked shader pass
     */
    public static ShaderMaskPass unmasked(RenderType renderType, float alpha) {
        return new ShaderMaskPass(renderType, ShaderMaskSource.NONE, null, 0x000000, 0.05f, alpha);
    }

    /**
     * Creates a pass that samples an external UV-aligned mask texture.
     * Baked item and block atlas UVs are converted to sprite-local coordinates automatically.
     *
     * @param renderType shader RenderType
     * @param texture mask texture, or {@code null} for an unmasked pass
     * @param color packed RGB target color
     * @param tolerance normalized RGB distance tolerance
     * @param alpha overlay opacity
     * @return texture-masked shader pass
     */
    public static ShaderMaskPass masked(RenderType renderType, ResourceLocation texture,
                                        int color, float tolerance, float alpha) {
        if (texture == null) {
            return unmasked(renderType, alpha);
        }
        return new ShaderMaskPass(renderType, ShaderMaskSource.TEXTURE, texture, color, tolerance, alpha);
    }

    /**
     * Creates a pass that selects pixels directly from the model's base texture.
     * This is primarily the compatibility replacement for legacy Color-Key masking.
     *
     * @param renderType shader RenderType
     * @param color packed RGB target color
     * @param tolerance normalized RGB distance tolerance
     * @param alpha overlay opacity
     * @return base-texture-masked shader pass
     */
    public static ShaderMaskPass baseTexture(RenderType renderType, int color,
                                             float tolerance, float alpha) {
        return new ShaderMaskPass(renderType, ShaderMaskSource.BASE_TEXTURE, null,
            color, tolerance, alpha);
    }
}
