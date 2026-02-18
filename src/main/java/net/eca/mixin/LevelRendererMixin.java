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
import net.eca.config.EcaConfiguration;
import net.eca.util.entity_extension.EntityExtensionClientState;
import net.eca.util.entity_extension.EntityExtension;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.entity_extension.ForceLoadingManager;
import net.eca.util.entity_extension.GlobalSkyboxExtension;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Shadow
    public abstract boolean isChunkCompiled(BlockPos pos);

    @Unique
    private Entity eca$currentEntity;

    // ==================== 强加载实体渲染 ====================

    // 强加载实体：绕过 frustum culling，改用配置的最大渲染距离限制
    @Redirect(method = "renderLevel",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
    private boolean eca$captureShouldRender(EntityRenderDispatcher dispatcher, Entity entity, Frustum frustum, double x, double y, double z) {
        this.eca$currentEntity = entity;
        if (ForceLoadingManager.isForceLoadedType(entity.getType())) {
            double maxDist = EcaConfiguration.getForceLoadingMaxRenderDistanceSafely();
            double distSqr = entity.distanceToSqr(x, y, z);
            return distSqr <= maxDist * maxDist;
        }
        return dispatcher.shouldRender(entity, frustum, x, y, z);
    }

    // 强加载实体：绕过区块编译检查，允许在未加载区块中渲染
    @Redirect(method = "renderLevel",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/renderer/LevelRenderer;isChunkCompiled(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean eca$forceLoadedChunkCheck(LevelRenderer instance, BlockPos blockPos) {
        if (this.eca$currentEntity != null && ForceLoadingManager.isForceLoadedType(this.eca$currentEntity.getType())) {
            return true;
        }
        return isChunkCompiled(blockPos);
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
        EntityType<?> activeType = EntityExtensionClientState.getActiveType(dimensionId);
        if (activeType == null) {
            return null;
        }

        EntityExtension extension = EntityExtensionManager.getExtension(activeType);
        if (extension == null) {
            return null;
        }

        return extension.globalSkyboxExtension();
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
    private static void drawShaderSkybox(PoseStack poseStack, RenderType renderType, float size, float alpha) {
        int alphaInt = (int) (Mth.clamp(alpha, 0.0f, 1.0f) * 255.0f);
        int light = 15728880;

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();

        renderType.setupRenderState();

        for (int i = 0; i < 6; ++i) {
            poseStack.pushPose();
            rotateToFace(poseStack, i);
            Matrix4f matrix = poseStack.last().pose();
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            bufferBuilder.vertex(matrix, -size, -size, -size).color(255, 255, 255, alphaInt).uv(0.0f, 0.0f).uv2(light).normal(0.0f, 1.0f, 0.0f).endVertex();
            bufferBuilder.vertex(matrix, -size, -size, size).color(255, 255, 255, alphaInt).uv(0.0f, 0.0f).uv2(light).normal(0.0f, 1.0f, 0.0f).endVertex();
            bufferBuilder.vertex(matrix, size, -size, size).color(255, 255, 255, alphaInt).uv(0.0f, 0.0f).uv2(light).normal(0.0f, 1.0f, 0.0f).endVertex();
            bufferBuilder.vertex(matrix, size, -size, -size).color(255, 255, 255, alphaInt).uv(0.0f, 0.0f).uv2(light).normal(0.0f, 1.0f, 0.0f).endVertex();
            BufferUploader.drawWithShader(bufferBuilder.end());
            poseStack.popPose();
        }

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
