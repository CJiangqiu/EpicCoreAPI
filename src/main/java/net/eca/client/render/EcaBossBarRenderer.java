package net.eca.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.eca.client.render.shader.EcaShaderInstance;
import net.eca.util.EcaLogger;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/*
 * ECA 自定义 Boss 血条绘制器 —— 实体扩展与袭击系统共用。
 *
 * 绘制分两层：外框满宽渲染作为底层，填充按 progress 横向裁剪覆盖其上。
 * 同时提供贴图与 RenderType 时，着色器通过 alpha 通道遮罩叠加在贴图的非透明像素上。
 */
@OnlyIn(Dist.CLIENT)
public final class EcaBossBarRenderer {

    // 原版血条尺寸，未提供任何尺寸信息时作为回退
    public static final int VANILLA_BAR_WIDTH = 182;
    public static final int VANILLA_BAR_HEIGHT = 5;

    private EcaBossBarRenderer() {}

    /*
     * 一条血条的完整外观参数。
     *
     * 尺寸为 0 时按贴图实际尺寸解析；仅提供 RenderType 而不提供贴图时必须显式给出尺寸，
     * 否则无法确定绘制范围。
     */
    public static final class BarAppearance {
        public ResourceLocation frameTexture;
        public ResourceLocation fillTexture;
        public RenderType frameRenderType;
        public RenderType fillRenderType;
        public int frameWidth;
        public int frameHeight;
        public int fillWidth;
        public int fillHeight;
        public int frameOffsetX;
        public int frameOffsetY;
        public int fillOffsetX;
        public int fillOffsetY;
        public float frameAlpha = 1.0f;
        public float fillAlpha = 1.0f;

        // 是否未设置任何可绘制内容
        public boolean isEmpty() {
            return frameTexture == null && fillTexture == null
                    && frameRenderType == null && fillRenderType == null;
        }
    }

    // 绘制一条自定义血条
    /**
     * Draw a custom boss bar, resolving layout from the supplied appearance.
     * <p>
     * The bar is centered horizontally and scaled down when it would exceed the screen width.
     *
     * @param graphics   the GUI graphics context
     * @param y          the vertical position handed over by the vanilla bar layout
     * @param progress   fill ratio in range [0, 1]
     * @param appearance the resolved appearance
     * @param debugName  identifier used in warnings about missing sizes
     * @return true if the bar was drawn; false when required sizes are missing and the caller
     *         should fall back to the vanilla bar
     */
    public static boolean draw(GuiGraphics graphics, int y, float progress,
                               BarAppearance appearance, String debugName) {
        if (appearance == null || appearance.isEmpty()) {
            return false;
        }

        int barWidth = VANILLA_BAR_WIDTH;
        int barHeight = VANILLA_BAR_HEIGHT;
        int fillTextureWidth;
        int fillTextureHeight;

        if (appearance.frameTexture != null) {
            TextureSizeCache.Size frameSize = TextureSizeCache.get(appearance.frameTexture);
            barWidth = frameSize.width();
            barHeight = frameSize.height();
        } else if (appearance.frameRenderType != null) {
            barWidth = appearance.frameWidth;
            barHeight = appearance.frameHeight;
        }

        if (appearance.fillTexture != null) {
            TextureSizeCache.Size fillSize = TextureSizeCache.get(appearance.fillTexture);
            fillTextureWidth = fillSize.width();
            fillTextureHeight = fillSize.height();
        } else if (appearance.fillRenderType != null) {
            fillTextureWidth = appearance.fillWidth;
            fillTextureHeight = appearance.fillHeight;
        } else {
            fillTextureWidth = barWidth;
            fillTextureHeight = barHeight;
        }

        if (appearance.frameRenderType != null && (barWidth <= 0 || barHeight <= 0)) {
            EcaLogger.warn("Custom boss bar frame size must be set for {}", debugName);
            return false;
        }
        if (appearance.fillRenderType != null && (fillTextureWidth <= 0 || fillTextureHeight <= 0)) {
            EcaLogger.warn("Custom boss bar fill size must be set for {}", debugName);
            return false;
        }

        float clamped = progress < 0.0f ? 0.0f : (progress > 1.0f ? 1.0f : progress);
        int fillWidth = (int) (clamped * (float) fillTextureWidth);

        int layoutWidth = barWidth;
        if (appearance.frameTexture == null && appearance.frameRenderType == null && fillTextureWidth > 0) {
            layoutWidth = fillTextureWidth;
        }

        float scale = 1.0f;
        int guiWidth = graphics.guiWidth();
        if (layoutWidth > 0) {
            float availableWidth = Math.max(1.0f, (float) guiWidth - 20.0f);
            scale = Math.min(1.0f, availableWidth / (float) layoutWidth);
        }

        float scaledWidth = layoutWidth * scale;
        float renderX = (guiWidth - scaledWidth) * 0.5f;

        graphics.pose().pushPose();
        graphics.pose().translate(renderX, y, 0.0f);
        graphics.pose().scale(scale, scale, 1.0f);

        int baseFillOffsetX = Math.max(0, (barWidth - fillTextureWidth) / 2);
        int baseFillOffsetY = Math.max(0, (barHeight - fillTextureHeight) / 2);
        int fillDrawX = baseFillOffsetX + appearance.fillOffsetX;
        int fillDrawY = baseFillOffsetY + appearance.fillOffsetY;

        // 外框：满宽渲染（先渲染作为底层）
        renderLayer(graphics, appearance.frameTexture, appearance.frameRenderType,
                appearance.frameOffsetX, appearance.frameOffsetY,
                barWidth, barHeight, barWidth, barHeight, appearance.frameAlpha);

        // 填充：按 progress 裁剪渲染（后渲染覆盖在外框上方）
        if (fillWidth > 0) {
            renderLayer(graphics, appearance.fillTexture, appearance.fillRenderType,
                    fillDrawX, fillDrawY,
                    fillWidth, fillTextureHeight, fillTextureWidth, fillTextureHeight, appearance.fillAlpha);
        }

        graphics.pose().popPose();
        return true;
    }

    private static void renderLayer(GuiGraphics graphics, ResourceLocation texture, RenderType renderType,
                                    int x, int y, int drawWidth, int drawHeight, int fullWidth, int fullHeight,
                                    float alpha) {
        if (texture == null && renderType == null) {
            return;
        }
        EcaShaderInstance.setOpacity(alpha);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        try {
            if (texture != null && renderType != null) {
                drawTextureWithShaderMask(graphics, texture, renderType,
                        x, y, drawWidth, drawHeight, fullWidth, fullHeight);
            } else if (texture != null) {
                graphics.blit(texture, x, y, 0, 0, drawWidth, drawHeight, fullWidth, fullHeight);
            } else {
                drawRenderType(graphics, renderType, x, y, drawWidth, drawHeight, fullWidth);
            }
        } finally {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            EcaShaderInstance.clearOpacity();
        }
    }

    private static void drawTextureWithShaderMask(GuiGraphics graphics, ResourceLocation texture, RenderType renderType,
                                                  int x, int y, int drawWidth, int drawHeight,
                                                  int fullWidth, int fullHeight) {
        graphics.flush();
        Matrix4f matrix = graphics.pose().last().pose();

        // 清除渲染区域的 alpha 通道为 0
        RenderSystem.colorMask(false, false, false, true);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ZERO,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ZERO
        );
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder clearBuilder = Tesselator.getInstance().getBuilder();
        clearBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        clearBuilder.vertex(matrix, x, y + drawHeight, 0).color(0, 0, 0, 255).endVertex();
        clearBuilder.vertex(matrix, x + drawWidth, y + drawHeight, 0).color(0, 0, 0, 255).endVertex();
        clearBuilder.vertex(matrix, x + drawWidth, y, 0).color(0, 0, 0, 255).endVertex();
        clearBuilder.vertex(matrix, x, y, 0).color(0, 0, 0, 255).endVertex();
        BufferUploader.drawWithShader(clearBuilder.end());
        RenderSystem.colorMask(true, true, true, true);

        // 渲染贴图，alpha 通道直接写入帧缓冲区
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO
        );
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        float texU1 = fullWidth <= 0 ? 0.0f : (float) drawWidth / (float) fullWidth;
        float texV1 = fullHeight <= 0 ? 0.0f : (float) drawHeight / (float) fullHeight;
        BufferBuilder texBuilder = Tesselator.getInstance().getBuilder();
        texBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        texBuilder.vertex(matrix, x, y + drawHeight, 0).uv(0.0f, texV1).endVertex();
        texBuilder.vertex(matrix, x + drawWidth, y + drawHeight, 0).uv(texU1, texV1).endVertex();
        texBuilder.vertex(matrix, x + drawWidth, y, 0).uv(texU1, 0.0f).endVertex();
        texBuilder.vertex(matrix, x, y, 0).uv(0.0f, 0.0f).endVertex();
        BufferUploader.drawWithShader(texBuilder.end());

        // 将 alpha 缩放到 0.5，使着色器半透明叠加在贴图上
        RenderSystem.colorMask(false, false, false, true);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.SRC_ALPHA
        );
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder scaleBuilder = Tesselator.getInstance().getBuilder();
        scaleBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        scaleBuilder.vertex(matrix, x, y + drawHeight, 0).color(0, 0, 0, 127).endVertex();
        scaleBuilder.vertex(matrix, x + drawWidth, y + drawHeight, 0).color(0, 0, 0, 127).endVertex();
        scaleBuilder.vertex(matrix, x + drawWidth, y, 0).color(0, 0, 0, 127).endVertex();
        scaleBuilder.vertex(matrix, x, y, 0).color(0, 0, 0, 127).endVertex();
        BufferUploader.drawWithShader(scaleBuilder.end());
        RenderSystem.colorMask(true, true, true, true);

        // 渲染着色器，使用 DST_ALPHA 混合（只在贴图非透明区域显示）
        renderType.setupRenderState();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.DST_ALPHA, GlStateManager.DestFactor.ONE_MINUS_DST_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE
        );
        float shaderU1 = fullWidth <= 0 ? 0.0f : (float) drawWidth / (float) fullWidth;
        int light = LightTexture.FULL_BRIGHT;
        BufferBuilder shaderBuilder = Tesselator.getInstance().getBuilder();
        shaderBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        shaderBuilder.vertex(matrix, x, y + drawHeight, 0).color(1.0f, 1.0f, 1.0f, 1.0f).uv(0.0f, 1.0f).uv2(light).normal(0.0f, 0.0f, 1.0f).endVertex();
        shaderBuilder.vertex(matrix, x + drawWidth, y + drawHeight, 0).color(1.0f, 1.0f, 1.0f, 1.0f).uv(shaderU1, 1.0f).uv2(light).normal(0.0f, 0.0f, 1.0f).endVertex();
        shaderBuilder.vertex(matrix, x + drawWidth, y, 0).color(1.0f, 1.0f, 1.0f, 1.0f).uv(shaderU1, 0.0f).uv2(light).normal(0.0f, 0.0f, 1.0f).endVertex();
        shaderBuilder.vertex(matrix, x, y, 0).color(1.0f, 1.0f, 1.0f, 1.0f).uv(0.0f, 0.0f).uv2(light).normal(0.0f, 0.0f, 1.0f).endVertex();
        BufferUploader.drawWithShader(shaderBuilder.end());
        renderType.clearRenderState();

        RenderSystem.defaultBlendFunc();
    }

    private static void drawRenderType(GuiGraphics graphics, RenderType renderType,
                                       int x, int y, int width, int height, int fullWidth) {
        float u1 = fullWidth <= 0 ? 0.0f : (float) width / (float) fullWidth;
        Matrix4f matrix = graphics.pose().last().pose();
        VertexConsumer consumer = graphics.bufferSource().getBuffer(renderType);
        int light = LightTexture.FULL_BRIGHT;
        consumer.vertex(matrix, x, y + height, 0).color(1.0f, 1.0f, 1.0f, 1.0f).uv(0.0f, 1.0f).uv2(light).normal(0.0f, 0.0f, 1.0f).endVertex();
        consumer.vertex(matrix, x + width, y + height, 0).color(1.0f, 1.0f, 1.0f, 1.0f).uv(u1, 1.0f).uv2(light).normal(0.0f, 0.0f, 1.0f).endVertex();
        consumer.vertex(matrix, x + width, y, 0).color(1.0f, 1.0f, 1.0f, 1.0f).uv(u1, 0.0f).uv2(light).normal(0.0f, 0.0f, 1.0f).endVertex();
        consumer.vertex(matrix, x, y, 0).color(1.0f, 1.0f, 1.0f, 1.0f).uv(0.0f, 0.0f).uv2(light).normal(0.0f, 0.0f, 1.0f).endVertex();
        graphics.flush();
    }
}
