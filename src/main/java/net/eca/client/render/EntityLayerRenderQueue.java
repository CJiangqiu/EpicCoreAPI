package net.eca.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Compatibility facade for the former entity-only delayed queue.
 *
 * @deprecated Use {@link ShaderMaskRenderQueue}; all extension domains now share that queue.
 */
@Deprecated
@OnlyIn(Dist.CLIENT)
public final class EntityLayerRenderQueue {

    private EntityLayerRenderQueue() {
    }

    public static BufferBuilder acquireBuilder() {
        return ShaderMaskRenderQueue.acquireBuilder();
    }

    public static void enqueue(RenderType renderType, BufferBuilder builder,
                               BufferBuilder.RenderedBuffer renderedBuffer) {
        ShaderMaskRenderQueue.enqueue(ShaderMaskPass.unmasked(renderType, 1.0f), builder, renderedBuffer,
            MaskUvTransform.IDENTITY);
    }

    public static void enqueue(RenderType renderType, BufferBuilder builder,
                               BufferBuilder.RenderedBuffer renderedBuffer, ResourceLocation maskTexture,
                               int maskColor, float maskTolerance) {
        ShaderMaskPass pass = ShaderMaskPass.masked(renderType, maskTexture, maskColor, maskTolerance, 1.0f);
        ShaderMaskRenderQueue.enqueue(pass, builder, renderedBuffer, MaskUvTransform.IDENTITY);
    }

    public static void drawNow(RenderType renderType, BufferBuilder builder,
                               BufferBuilder.RenderedBuffer renderedBuffer, ResourceLocation maskTexture,
                               int maskColor, float maskTolerance) {
        ShaderMaskPass pass = ShaderMaskPass.masked(renderType, maskTexture, maskColor, maskTolerance, 1.0f);
        ShaderMaskRenderQueue.drawNow(pass, builder, renderedBuffer, MaskUvTransform.IDENTITY);
    }

    public static void flush() {
        ShaderMaskRenderQueue.flush();
    }
}
