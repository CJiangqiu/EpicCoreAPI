package net.eca.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Compatibility facade that converts legacy item Color-Key state into shader mask passes.
 *
 * @deprecated Use {@link ShaderMaskRenderQueue} and {@link ShaderMaskPass}.
 */
@Deprecated
@OnlyIn(Dist.CLIENT)
public final class ItemLayerRenderQueue {

    private ItemLayerRenderQueue() {
    }

    public static BufferBuilder acquireBuilder() {
        return ShaderMaskRenderQueue.acquireBuilder();
    }

    public static void enqueue(RenderType renderType, BufferBuilder builder,
                               BufferBuilder.RenderedBuffer renderedBuffer, float colorKeyR,
                               float colorKeyG, float colorKeyB, float colorKeyTolerance,
                               float uvMinU, float uvMinV, float uvScaleU, float uvScaleV,
                               float alpha) {
        ShaderMaskPass pass = createPass(renderType, colorKeyR, colorKeyG, colorKeyB,
            colorKeyTolerance, alpha);
        MaskUvTransform uvTransform = new MaskUvTransform(uvMinU, uvMinV, uvScaleU, uvScaleV);
        ShaderMaskRenderQueue.enqueue(pass, builder, renderedBuffer, uvTransform);
    }

    public static void flush() {
        ShaderMaskRenderQueue.flush();
    }

    private static ShaderMaskPass createPass(RenderType renderType, float red, float green, float blue,
                                             float tolerance, float alpha) {
        if (Float.isNaN(red)) {
            return ShaderMaskPass.unmasked(renderType, alpha);
        }
        int color = channel(red) << 16 | channel(green) << 8 | channel(blue);
        return ShaderMaskPass.baseTexture(renderType, color, tolerance, alpha);
    }

    private static int channel(float value) {
        return Math.round(Math.max(0.0f, Math.min(1.0f, value)) * 255.0f);
    }
}
