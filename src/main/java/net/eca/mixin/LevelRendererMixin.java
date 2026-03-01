package net.eca.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.eca.client.render.shader.EcaShaderInstance;

import net.eca.util.entity_extension.EntityExtensionClientState;
import net.eca.util.entity_extension.ForceLoadingManager;
import net.eca.util.entity_extension.GlobalSkyboxExtension;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    // ==================== 强加载实体渲染 ====================

    @Inject(method = "isChunkCompiled", at = @At("HEAD"), cancellable = true)
    private void eca$forceLoadedChunkCheck(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Entity entity = ForceLoadingManager.getCurrentRenderingEntity();
        if (entity != null && ForceLoadingManager.isForceLoadedType(entity.getType())) {
            cir.setReturnValue(true);
        }
    }

    // ==================== 全局天空盒渲染 ====================

    @Inject(method = "renderSky", at = @At("TAIL"))
    private void eca$renderGlobalSkybox(PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, Camera camera, boolean foggy, Runnable setupFog, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || camera == null || foggy) {
            return;
        }
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }

        GlobalSkyboxExtension skybox = getGlobalSkyboxExtension(minecraft.level.dimension().location());
        if (skybox == null || !skybox.enabled()) {
            return;
        }

        float size = Math.max(20.0f, skybox.size());
        float alpha = Mth.clamp(skybox.alpha(), 0.0f, 1.0f);
        if (alpha <= 0.0f) {
            return;
        }

        if (skybox.enableTexture() && skybox.texture() != null) {
            drawTextureSkybox(poseStack, skybox, size, alpha);
        }

        // Oculus光影激活时shader skybox延迟到GameRendererPostLevelMixin（管线合成后）渲染
        if (!EcaShaderInstance.isOculusShadersActive() && skybox.enableShader() && skybox.shaderRenderType() != null) {
            drawShaderSkybox(poseStack, skybox.shaderRenderType(), size, alpha);
        }
    }

    @Unique
    private static GlobalSkyboxExtension getGlobalSkyboxExtension(ResourceLocation dimensionId) {
        return EntityExtensionClientState.getActiveSkybox(dimensionId);
    }

    @Unique
    private static void drawTextureSkybox(PoseStack poseStack, GlobalSkyboxExtension skybox, float size, float alpha) {
        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, skybox.texture());
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);

        float uvScale = Math.max(1.0f, skybox.textureUvScale());
        int red = (int)(Mth.clamp(skybox.textureRed(), 0.0f, 1.0f) * 255.0f);
        int green = (int)(Mth.clamp(skybox.textureGreen(), 0.0f, 1.0f) * 255.0f);
        int blue = (int)(Mth.clamp(skybox.textureBlue(), 0.0f, 1.0f) * 255.0f);
        int alphaInt = (int)(alpha * 255.0f);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();

        for (int i = 0; i < 6; ++i) {
            poseStack.pushPose();
            rotateToFace(poseStack, i);
            Matrix4f matrix = poseStack.last().pose();
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            bufferBuilder.vertex(matrix, -size, -size, -size).uv(0.0f, 0.0f).color(red, green, blue, alphaInt).endVertex();
            bufferBuilder.vertex(matrix, -size, -size, size).uv(0.0f, uvScale).color(red, green, blue, alphaInt).endVertex();
            bufferBuilder.vertex(matrix, size, -size, size).uv(uvScale, uvScale).color(red, green, blue, alphaInt).endVertex();
            bufferBuilder.vertex(matrix, size, -size, -size).uv(uvScale, 0.0f).color(red, green, blue, alphaInt).endVertex();
            BufferUploader.drawWithShader(bufferBuilder.end());
            poseStack.popPose();
        }

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    @Unique
    private static void drawShaderSkybox(PoseStack poseStack, RenderType renderType, float radius, float alpha) {
        int alphaInt = (int) (Mth.clamp(alpha, 0.0f, 1.0f) * 255.0f);
        int light = 15728880;
        int segments = 32;
        int rings = 16;

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        Matrix4f matrix = poseStack.last().pose();

        renderType.setupRenderState();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

        for (int ring = 0; ring < rings; ring++) {
            float phi1 = (float) Math.PI * ring / rings;
            float phi2 = (float) Math.PI * (ring + 1) / rings;
            float y1 = (float) Math.cos(phi1) * radius;
            float y2 = (float) Math.cos(phi2) * radius;
            float r1 = (float) Math.sin(phi1) * radius;
            float r2 = (float) Math.sin(phi2) * radius;

            for (int seg = 0; seg < segments; seg++) {
                float theta1 = (float) (2.0 * Math.PI * seg / segments);
                float theta2 = (float) (2.0 * Math.PI * (seg + 1) / segments);

                float x1 = (float) Math.cos(theta1);
                float z1 = (float) Math.sin(theta1);
                float x2 = (float) Math.cos(theta2);
                float z2 = (float) Math.sin(theta2);

                bufferBuilder.vertex(matrix, x1 * r1, y1, z1 * r1).color(255, 255, 255, alphaInt).uv(0.0f, 0.0f).uv2(light).normal(x1, y1 / radius, z1).endVertex();
                bufferBuilder.vertex(matrix, x1 * r2, y2, z1 * r2).color(255, 255, 255, alphaInt).uv(0.0f, 0.0f).uv2(light).normal(x1, y2 / radius, z1).endVertex();
                bufferBuilder.vertex(matrix, x2 * r2, y2, z2 * r2).color(255, 255, 255, alphaInt).uv(0.0f, 0.0f).uv2(light).normal(x2, y2 / radius, z2).endVertex();
                bufferBuilder.vertex(matrix, x2 * r1, y1, z2 * r1).color(255, 255, 255, alphaInt).uv(0.0f, 0.0f).uv2(light).normal(x2, y1 / radius, z2).endVertex();
            }
        }

        BufferUploader.drawWithShader(bufferBuilder.end());
        renderType.clearRenderState();
    }

    @Unique
    private static void rotateToFace(PoseStack poseStack, int face) {
        if (face == 1) {
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));
        } else if (face == 2) {
            poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f));
        } else if (face == 3) {
            poseStack.mulPose(Axis.XP.rotationDegrees(180.0f));
        } else if (face == 4) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f));
        } else if (face == 5) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(-90.0f));
        }
    }
}
