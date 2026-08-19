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
import net.eca.util.entity_extension.ForceLoadingManager;
import net.eca.util.entity_extension.GlobalSkyboxExtension;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Unique
    private boolean eca$forceLoadedFogActive;

    @Unique
    private float eca$savedFogStart;

    @Unique
    private float eca$savedFogEnd;

    /* 本帧存在横跨云层平面的可见强加载实体。云层绘制在实体之后并吃雾色，
       远处的云挡住巨型模型时看起来不像云，而像模型被挖掉一块。 */
    @Unique
    private boolean eca$cloudsOccludeForceLoaded;

    // 云层平板厚度：花式云的四边形在 y 方向占 4 格，快速云是平面，取上界统一处理
    @Unique
    private static final float ECA_CLOUD_SLAB_THICKNESS = 4.0f;

    // ==================== 强加载实体渲染 ====================

    /* 云层关闭时 renderClouds 根本不会被调用，用后复位会让标志跨帧残留，只能在帧首清零 */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void eca$resetCloudOcclusion(PoseStack poseStack, float partialTick, long finishNanoTime,
                                         boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                         LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        eca$cloudsOccludeForceLoaded = false;
    }

    /* shouldRender 每次进入都会登记当前实体，isChunkCompiled 是它在同一个判断表达式里的读取方。
       但 shouldRender 返回 false 时 isChunkCompiled 不会被求值，登记值没有配对的消费点，
       只能由帧边界回收。ReceivingLevelScreen 在 tick 里也调 isChunkCompiled，
       读到跨帧残留会让“正在加载地形”在地形就绪前提前关闭。 */
    @Inject(method = "renderLevel", at = {@At("HEAD"), @At("RETURN")})
    private void eca$clearForceLoadedRenderContext(PoseStack poseStack, float partialTick, long finishNanoTime,
                                                   boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                                   LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        ForceLoadingManager.clearCurrentRenderingEntity();
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void eca$skipOccludingClouds(PoseStack poseStack, Matrix4f frustumMatrix, float partialTick,
                                         double camX, double camY, double camZ, CallbackInfo ci) {
        if (eca$cloudsOccludeForceLoaded) {
            ci.cancel();
        }
    }

    @Inject(method = "isChunkCompiled", at = @At("HEAD"), cancellable = true)
    private void eca$forceLoadedChunkCheck(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Entity entity = ForceLoadingManager.getCurrentRenderingEntity();
        if (entity != null && ForceLoadingManager.shouldForceLoad(entity)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "renderEntity", at = @At("HEAD"))
    private void eca$beginForceLoadedEntityRender(Entity entity, double camX, double camY, double camZ,
                                                   float partialTick, PoseStack poseStack,
                                                   MultiBufferSource bufferSource, CallbackInfo ci) {
        eca$forceLoadedFogActive = false;
        if (!ForceLoadingManager.shouldForceLoad(entity)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();

        eca$markCloudOcclusion(minecraft, entity, camX, camY, camZ);

        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera == null || camera.getFluidInCamera() != FogType.NONE) {
            return;
        }

        flushEntityBuffers(minecraft);
        eca$savedFogStart = RenderSystem.getShaderFogStart();
        eca$savedFogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        eca$forceLoadedFogActive = true;
    }

    @Inject(method = "renderEntity", at = @At("RETURN"))
    private void eca$endForceLoadedEntityRender(Entity entity, double camX, double camY, double camZ,
                                                 float partialTick, PoseStack poseStack,
                                                 MultiBufferSource bufferSource, CallbackInfo ci) {
        if (!eca$forceLoadedFogActive) {
            return;
        }
        flushEntityBuffers(Minecraft.getInstance());
        RenderSystem.setShaderFogStart(eca$savedFogStart);
        RenderSystem.setShaderFogEnd(eca$savedFogEnd);
        eca$forceLoadedFogActive = false;
    }

    /* 强加载实体的 EntityRenderDispatcher.shouldRender 被改成了纯距离判断（绕过视锥），
       背后的实体照样会走到 renderEntity，所以这里要补一次视锥判断，否则看不见的实体也会把云关掉。
       判据取渲染器自身的 shouldRender：巨型模型的包围盒常小于模型，只有渲染器知道实际范围。 */
    @Unique
    private void eca$markCloudOcclusion(Minecraft minecraft, Entity entity,
                                        double camX, double camY, double camZ) {
        if (eca$cloudsOccludeForceLoaded || minecraft.level == null) {
            return;
        }
        if (!EcaConfiguration.getForceLoadingHideOccludingCloudsSafely()) {
            return;
        }
        float cloudBottom = minecraft.level.effects().getCloudHeight();
        if (Float.isNaN(cloudBottom)) {
            return;
        }

        AABB box = entity.getBoundingBoxForCulling();
        float cloudTop = cloudBottom + ECA_CLOUD_SLAB_THICKNESS;
        // 相机与实体的纵向跨度必须真的切过云层平板，否则视线不经过云
        if (Math.max(camY, box.maxY) <= cloudBottom || Math.min(camY, box.minY) >= cloudTop) {
            return;
        }

        Frustum frustum = ((LevelRenderer) (Object) this).getFrustum();
        if (frustum != null && !eca$isVisibleToRenderer(minecraft, entity, frustum, camX, camY, camZ)) {
            return;
        }
        eca$cloudsOccludeForceLoaded = true;
    }

    @Unique
    private static boolean eca$isVisibleToRenderer(Minecraft minecraft, Entity entity, Frustum frustum,
                                                   double camX, double camY, double camZ) {
        EntityRenderer<? super Entity> renderer = minecraft.getEntityRenderDispatcher().getRenderer(entity);
        if (renderer == null) {
            return true;
        }
        return renderer.shouldRender(entity, frustum, camX, camY, camZ);
    }

    @Unique
    private static void flushEntityBuffers(Minecraft minecraft) {
        minecraft.renderBuffers().bufferSource().endBatch();
        minecraft.renderBuffers().outlineBufferSource().endOutlineBatch();
    }

    // ==================== 全局天空盒渲染 ====================

    @Inject(method = "renderSky", at = @At("TAIL"))
    private void eca$renderGlobalSkybox(PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, Camera camera, boolean foggy, Runnable setupFog, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || camera == null) {
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
