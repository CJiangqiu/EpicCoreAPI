package net.eca.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

@OnlyIn(Dist.CLIENT)
public final class SpriteBatchingVertexConsumer implements VertexConsumer {

    private final VertexFormat format;
    private final Map<TextureAtlasSprite, BufferBuilder> builders = new IdentityHashMap<>();
    private BufferBuilder fallback;

    public SpriteBatchingVertexConsumer(VertexFormat format) {
        this.format = format;
    }

    public void finish(Consumer<SpriteBatch> consumer) {
        builders.forEach((sprite, builder) ->
            consumer.accept(new SpriteBatch(builder, MaskUvTransform.fromSprite(sprite))));
        if (fallback != null) {
            consumer.accept(new SpriteBatch(fallback, MaskUvTransform.IDENTITY));
        }
        builders.clear();
        fallback = null;
    }

    @Override
    public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float[] brightness,
                            float red, float green, float blue, float alpha,
                            int[] lights, int overlay, boolean readExistingColor) {
        builder(quad.getSprite()).putBulkData(pose, quad, brightness, red, green, blue,
            alpha, lights, overlay, readExistingColor);
    }

    private BufferBuilder builder(TextureAtlasSprite sprite) {
        if (sprite == null) {
            if (fallback == null) {
                fallback = newBuilder();
            }
            return fallback;
        }
        return builders.computeIfAbsent(sprite, ignored -> newBuilder());
    }

    private BufferBuilder newBuilder() {
        BufferBuilder builder = ShaderMaskRenderQueue.acquireBuilder();
        builder.begin(VertexFormat.Mode.QUADS, format);
        return builder;
    }

    private BufferBuilder direct() {
        return builder(null);
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        direct().vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        direct().color(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        direct().uv(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        direct().overlayCoords(u, v);
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        direct().uv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        direct().normal(x, y, z);
        return this;
    }

    @Override
    public void endVertex() {
        direct().endVertex();
    }

    @Override
    public void defaultColor(int red, int green, int blue, int alpha) {
        direct().defaultColor(red, green, blue, alpha);
    }

    @Override
    public void unsetDefaultColor() {
        direct().unsetDefaultColor();
    }

    public record SpriteBatch(BufferBuilder builder, MaskUvTransform uvTransform) {
    }
}
