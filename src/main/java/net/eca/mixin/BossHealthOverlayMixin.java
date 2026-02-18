package net.eca.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.eca.util.entity_extension.EntityExtensionClientState;
import net.eca.client.render.TextureSizeCache;
import net.eca.util.entity_extension.EntityExtension;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.EcaLogger;
import net.eca.util.entity_extension.BossBarExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyIn(Dist.CLIENT)
@Mixin(BossHealthOverlay.class)
public class BossHealthOverlayMixin {

    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;

    @Shadow
    @Final
    private static ResourceLocation GUI_BARS_LOCATION;

    @Inject(method = "drawBar(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/world/BossEvent;)V", at = @At("HEAD"), cancellable = true)
    private void eca$drawCustomBossBar(GuiGraphics graphics, int x, int y, BossEvent event, CallbackInfo ci) {
        if (tryRenderCustomBossBar(graphics, x, y, event)) {
            ci.cancel();
        }
    }

    private boolean tryRenderCustomBossBar(GuiGraphics graphics, int x, int y, BossEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }

        EntityType<?> mappedType = EntityExtensionClientState.getBossEventType(event.getId());
        if (mappedType == null) {
            return false;
        }

        EntityExtension extension = EntityExtensionManager.getExtension(mappedType);
        if (extension == null) {
            return false;
        }

        BossBarExtension bossBar = extension.bossBarExtension();
        if (bossBar == null || !bossBar.enabled()) {
            return false;
        }

        ResourceLocation frameTexture = bossBar.getFrameTexture();
        ResourceLocation fillTexture = bossBar.getFillTexture();
        RenderType frameType = bossBar.getFrameRenderType();
        RenderType fillType = bossBar.getFillRenderType();

        // 启用了 bossBarExtension 但未设置任何自定义渲染 → 隐藏原版 bar，不渲染任何内容
        if (frameTexture == null && fillTexture == null && frameType == null && fillType == null) {
            return true;
        }

        int barWidth = BAR_WIDTH;
        int barHeight = BAR_HEIGHT;
        int fillTextureWidth = BAR_WIDTH;
        int fillTextureHeight = BAR_HEIGHT;

        if (frameTexture != null) {
            TextureSizeCache.Size frameSize = TextureSizeCache.get(frameTexture);
            barWidth = frameSize.width();
            barHeight = frameSize.height();
        } else if (frameType != null) {
            barWidth = bossBar.getFrameWidth();
            barHeight = bossBar.getFrameHeight();
        }

        if (fillTexture != null) {
            TextureSizeCache.Size fillSize = TextureSizeCache.get(fillTexture);
            fillTextureWidth = fillSize.width();
            fillTextureHeight = fillSize.height();
        } else if (fillType != null) {
            fillTextureWidth = bossBar.getFillWidth();
            fillTextureHeight = bossBar.getFillHeight();
        } else {
            fillTextureWidth = barWidth;
            fillTextureHeight = barHeight;
        }

        if (frameType != null && (barWidth <= 0 || barHeight <= 0)) {
            EcaLogger.warn("Custom boss bar frame size must be set for {}", extension.getClass().getName());
            return false;
        }

        if (fillType != null && (fillTextureWidth <= 0 || fillTextureHeight <= 0)) {
            EcaLogger.warn("Custom boss bar fill size must be set for {}", extension.getClass().getName());
            return false;
        }

        int fillWidth = (int)(event.getProgress() * (float)fillTextureWidth);

        int layoutWidth = barWidth;
        if (frameTexture == null && frameType == null && fillTextureWidth > 0) {
            layoutWidth = fillTextureWidth;
        }

        float scale = 1.0f;
        int guiWidth = graphics.guiWidth();
        if (layoutWidth > 0) {
            float availableWidth = Math.max(1.0f, (float)guiWidth - 20.0f);
            scale = Math.min(1.0f, availableWidth / (float)layoutWidth);
        }

        float scaledWidth = layoutWidth * scale;
        float renderX = (guiWidth - scaledWidth) * 0.5f;

        graphics.pose().pushPose();
        graphics.pose().translate(renderX, y, 0.0f);
        graphics.pose().scale(scale, scale, 1.0f);

        int frameOffsetX = bossBar.getFrameOffsetX();
        int frameOffsetY = bossBar.getFrameOffsetY();
        int fillOffsetX = bossBar.getFillOffsetX();
        int fillOffsetY = bossBar.getFillOffsetY();

        int baseFillOffsetX = Math.max(0, (barWidth - fillTextureWidth) / 2);
        int baseFillOffsetY = Math.max(0, (barHeight - fillTextureHeight) / 2);
        int fillDrawX = baseFillOffsetX + fillOffsetX;
        int fillDrawY = baseFillOffsetY + fillOffsetY;

        int frameDrawX = frameOffsetX;
        int frameDrawY = frameOffsetY;
        if (fillWidth > 0) {
            if (fillTexture != null && fillType != null) {
                drawTextureWithShaderMask(graphics, fillTexture, fillType,
                    fillDrawX, fillDrawY, fillWidth, fillTextureHeight, fillTextureWidth, fillTextureHeight);
            } else if (fillTexture != null) {
                graphics.blit(fillTexture, fillDrawX, fillDrawY, 0, 0, fillWidth, fillTextureHeight, fillTextureWidth, fillTextureHeight);
            } else if (fillType != null) {
                drawRenderType(graphics, fillType, fillDrawX, fillDrawY, fillWidth, fillTextureHeight, fillTextureWidth);
            }
        }

        if (frameTexture != null && frameType != null) {
            drawTextureWithShaderMask(graphics, frameTexture, frameType,
                frameDrawX, frameDrawY, barWidth, barHeight, barWidth, barHeight);
        } else if (frameTexture != null) {
            graphics.blit(frameTexture, frameDrawX, frameDrawY, 0, 0, barWidth, barHeight, barWidth, barHeight);
        } else if (frameType != null) {
            drawRenderType(graphics, frameType, frameDrawX, frameDrawY, barWidth, barHeight, barWidth);
        }

        graphics.pose().popPose();

        return true;
    }

    private void drawDefaultBar(GuiGraphics graphics, int x, int y, BossEvent event, int width, int offset) {
        graphics.blit(GUI_BARS_LOCATION, x, y, 0, event.getColor().ordinal() * 5 * 2 + offset, width, BAR_HEIGHT);
        if (event.getOverlay() != BossEvent.BossBarOverlay.PROGRESS) {
            RenderSystem.enableBlend();
            graphics.blit(GUI_BARS_LOCATION, x, y, 0, 80 + (event.getOverlay().ordinal() - 1) * 5 * 2 + offset, width, BAR_HEIGHT);
            RenderSystem.disableBlend();
        }
    }

    @Unique
    private void drawTextureWithShaderMask(GuiGraphics graphics, ResourceLocation texture, RenderType renderType,
                                           int x, int y, int drawWidth, int drawHeight, int fullWidth, int fullHeight) {
        graphics.flush();
        Matrix4f matrix = graphics.pose().last().pose();

        // 清除渲染区域的 alpha 通道为 0（使用非零顶点 alpha 避免 position_color.fsh 的 discard，通过混合函数强制输出 0）
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

        // 将 alpha 缩放到 0.5，使着色器半透明叠加在贴图上（而非完全覆盖）
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

        // 渲染着色器，使用 DST_ALPHA 混合（只在贴图非透明区域显示，50% 透明可见底层贴图）
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

    private void drawRenderType(GuiGraphics graphics, RenderType renderType, int x, int y, int width, int height, int fullWidth) {
        float u1 = fullWidth <= 0 ? 0.0f : (float)width / (float)fullWidth;
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
