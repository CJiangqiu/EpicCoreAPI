package net.eca.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.eca.client.render.EntityLayerRenderQueue;
import net.eca.client.render.shader.EcaShaderInstance;
import net.eca.util.entity_extension.EntityExtensionClientState;
import net.eca.util.entity_extension.GlobalSkyboxExtension;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyIn(Dist.CLIENT)
@Mixin(GameRenderer.class)
public class GameRendererPostLevelMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private Camera mainCamera;

    // 在renderLevel()返回后注入，此时Oculus延迟渲染管线已完成合成，主帧缓冲区活跃
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void eca$renderPostLevel(float partialTick, long nanoTime, PoseStack poseStack, CallbackInfo ci) {
        if (minecraft.level == null) {
            return;
        }

        Camera camera = mainCamera;
        boolean cameraReady = camera != null && camera.getFluidInCamera() == FogType.NONE;

        // Oculus光影激活时才在此渲染shader skybox（管线合成后主帧缓冲区活跃）
        if (cameraReady && EcaShaderInstance.isOculusShadersActive()) {
            renderPostSkybox(poseStack);
        }

        // 刷新延迟的实体渲染层（Oculus光影模式下缓存的实体效果层）
        EntityLayerRenderQueue.flush();
    }

    private void renderPostSkybox(PoseStack poseStack) {
        GlobalSkyboxExtension skybox = getGlobalSkyboxExtension(minecraft.level.dimension().location());
        if (skybox == null || !skybox.enabled()) {
            return;
        }

        if (!skybox.enableShader() || skybox.shaderRenderType() == null) {
            return;
        }

        float size = Math.max(20.0f, skybox.size());
        float alpha = Mth.clamp(skybox.alpha(), 0.0f, 1.0f);
        if (alpha <= 0.0f) {
            return;
        }

        drawPostLevelSkybox(poseStack, skybox.shaderRenderType(), size, alpha);
    }

    private GlobalSkyboxExtension getGlobalSkyboxExtension(ResourceLocation dimensionId) {
        return EntityExtensionClientState.getActiveSkybox(dimensionId);
    }

    private void drawPostLevelSkybox(PoseStack poseStack, RenderType renderType, float size, float alpha) {
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
