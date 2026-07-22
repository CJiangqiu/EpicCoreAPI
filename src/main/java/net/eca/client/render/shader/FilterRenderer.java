package net.eca.client.render.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.eca.EcaMod;
import net.eca.util.filter.FilterType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;
@SuppressWarnings("removal")
@Mod.EventBusSubscriber(modid = EcaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class FilterRenderer {

    private static ShaderInstance sketchShader;
    private static ShaderInstance spotlightShader;
    private static ShaderInstance matrixShader;
    private static ShaderInstance rainShader;
    private static ShaderInstance desertShader;
    private static ShaderInstance snowShader;
    private static ShaderInstance toxicShader;
    private static ShaderInstance cosmosShader;
    private static long matrixStartNanos;
    private static long rainStartNanos;
    private static long desertStartNanos;
    private static long snowStartNanos;
    private static long toxicStartNanos;
    private static long cosmosStartNanos;
    private static final Set<FilterType> activeFilters = EnumSet.noneOf(FilterType.class);

    private static int copyFbo = -1;
    private static int depthCopyTexture = -1;
    private static int colorCopyTexture = -1;
    private static int copyWidth;
    private static int copyHeight;

    private static int spotlightFbo = -1;
    private static int spotlightDepthTexture = -1;
    private static int spotlightColorTexture = -1;
    private static int spotlightWidth;
    private static int spotlightHeight;

    /* 宇宙滤镜专用：在实体绘制前（AFTER_CUTOUT_BLOCKS_LAYER）快照纯地形深度，
       用于 AFTER_LEVEL 合成时区分实体像素与方块像素（阶段 AFTER_CUTOUT_BLOCKS） */
    private static int cosmosTerrainFbo = -1;
    private static int cosmosTerrainDepthTexture = -1;
    private static int cosmosTerrainWidth;
    private static int cosmosTerrainHeight;

    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(
                EcaShaderInstance.create(
                        event.getResourceProvider(),
                        new ResourceLocation(EcaMod.MOD_ID, "filters/sketch"),
                        DefaultVertexFormat.POSITION_TEX
                ),
                instance -> sketchShader = instance
        );
        event.registerShader(
                EcaShaderInstance.create(
                        event.getResourceProvider(),
                        new ResourceLocation(EcaMod.MOD_ID, "filters/spotlight"),
                        DefaultVertexFormat.POSITION_TEX
                ),
                instance -> spotlightShader = instance
        );
        event.registerShader(
                EcaShaderInstance.create(
                        event.getResourceProvider(),
                        new ResourceLocation(EcaMod.MOD_ID, "filters/matrix"),
                        DefaultVertexFormat.POSITION_TEX
                ),
                instance -> matrixShader = instance
        );
        event.registerShader(
                EcaShaderInstance.create(
                        event.getResourceProvider(),
                        new ResourceLocation(EcaMod.MOD_ID, "filters/rain"),
                        DefaultVertexFormat.POSITION_TEX
                ),
                instance -> rainShader = instance
        );
        event.registerShader(
                EcaShaderInstance.create(
                        event.getResourceProvider(),
                        new ResourceLocation(EcaMod.MOD_ID, "filters/desert"),
                        DefaultVertexFormat.POSITION_TEX
                ),
                instance -> desertShader = instance
        );
        event.registerShader(
                EcaShaderInstance.create(
                        event.getResourceProvider(),
                        new ResourceLocation(EcaMod.MOD_ID, "filters/snow"),
                        DefaultVertexFormat.POSITION_TEX
                ),
                instance -> snowShader = instance
        );
        event.registerShader(
                EcaShaderInstance.create(
                        event.getResourceProvider(),
                        new ResourceLocation(EcaMod.MOD_ID, "filters/toxic"),
                        DefaultVertexFormat.POSITION_TEX
                ),
                instance -> toxicShader = instance
        );
        event.registerShader(
                EcaShaderInstance.create(
                        event.getResourceProvider(),
                        new ResourceLocation(EcaMod.MOD_ID, "filters/cosmos"),
                        DefaultVertexFormat.POSITION_TEX
                ),
                instance -> cosmosShader = instance
        );
    }

    public static void enable(FilterType filter) {
        activeFilters.add(filter);
    }

    public static void disable(FilterType filter) {
        activeFilters.remove(filter);
    }

    public static boolean isActive(FilterType filter) {
        return activeFilters.contains(filter);
    }

    public static Set<FilterType> getActiveFilters() {
        return EnumSet.copyOf(activeFilters);
    }

    public static void clearAll() {
        activeFilters.clear();
        destroyCopyTargets();
        destroySpotlightTargets();
        destroyCosmosTerrainTarget();
        matrixStartNanos = 0;
        rainStartNanos = 0;
        desertStartNanos = 0;
        snowStartNanos = 0;
        toxicStartNanos = 0;
        cosmosStartNanos = 0;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (activeFilters.isEmpty()) return;

        if (activeFilters.contains(FilterType.SPOTLIGHT) && spotlightShader != null) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
                captureSpotlightEntity(event);
                return;
            }
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                renderSpotlight(event);
                return;
            }
            return;
        }
        if (activeFilters.contains(FilterType.MATRIX) && matrixShader != null) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                renderMatrix();
                return;
            }
            return;
        }
        if (activeFilters.contains(FilterType.RAIN) && rainShader != null) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                renderRain();
                return;
            }
            return;
        }
        if (activeFilters.contains(FilterType.DESERT) && desertShader != null) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                renderDesert();
                return;
            }
            return;
        }
        if (activeFilters.contains(FilterType.SNOW) && snowShader != null) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                renderSnow();
                return;
            }
            return;
        }
        if (activeFilters.contains(FilterType.TOXIC) && toxicShader != null) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                renderToxic();
                return;
            }
            return;
        }
        if (activeFilters.contains(FilterType.COSMOS)) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
                captureCosmosTerrainDepth();
                return;
            }
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL && cosmosShader != null) {
                renderCosmos(event);
                return;
            }
            return;
        }
        if (activeFilters.contains(FilterType.SKETCH) && sketchShader != null) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                renderSketch();
            }
        }
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearAll();
    }

    private static void ensureCopyTargets(int width, int height) {
        if (copyFbo != -1 && copyWidth == width && copyHeight == height) return;
        destroyCopyTargets();

        depthCopyTexture = GlStateManager._genTexture();
        GlStateManager._bindTexture(depthCopyTexture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, width, height, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, null);

        colorCopyTexture = GlStateManager._genTexture();
        GlStateManager._bindTexture(colorCopyTexture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null);

        copyFbo = GL30.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, copyFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthCopyTexture, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, colorCopyTexture, 0);

        copyWidth = width;
        copyHeight = height;
    }

    private static void destroyCopyTargets() {
        if (depthCopyTexture != -1) {
            GlStateManager._deleteTexture(depthCopyTexture);
            depthCopyTexture = -1;
        }
        if (colorCopyTexture != -1) {
            GlStateManager._deleteTexture(colorCopyTexture);
            colorCopyTexture = -1;
        }
        if (copyFbo != -1) {
            GL30.glDeleteFramebuffers(copyFbo);
            copyFbo = -1;
        }
    }

    private static void ensureSpotlightTargets(int width, int height) {
        if (spotlightFbo != -1 && spotlightWidth == width && spotlightHeight == height) return;
        destroySpotlightTargets();

        spotlightDepthTexture = GlStateManager._genTexture();
        GlStateManager._bindTexture(spotlightDepthTexture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, width, height, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, null);

        spotlightColorTexture = GlStateManager._genTexture();
        GlStateManager._bindTexture(spotlightColorTexture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null);

        spotlightFbo = GL30.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, spotlightFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, spotlightDepthTexture, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, spotlightColorTexture, 0);

        spotlightWidth = width;
        spotlightHeight = height;
    }

    private static void destroySpotlightTargets() {
        if (spotlightDepthTexture != -1) {
            GlStateManager._deleteTexture(spotlightDepthTexture);
            spotlightDepthTexture = -1;
        }
        if (spotlightColorTexture != -1) {
            GlStateManager._deleteTexture(spotlightColorTexture);
            spotlightColorTexture = -1;
        }
        if (spotlightFbo != -1) {
            GL30.glDeleteFramebuffers(spotlightFbo);
            spotlightFbo = -1;
        }
    }

    private static void ensureCosmosTerrainTarget(int width, int height) {
        if (cosmosTerrainFbo != -1 && cosmosTerrainWidth == width && cosmosTerrainHeight == height) return;
        destroyCosmosTerrainTarget();

        cosmosTerrainDepthTexture = GlStateManager._genTexture();
        GlStateManager._bindTexture(cosmosTerrainDepthTexture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, width, height, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, null);

        cosmosTerrainFbo = GL30.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, cosmosTerrainFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, cosmosTerrainDepthTexture, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        cosmosTerrainWidth = width;
        cosmosTerrainHeight = height;
    }

    private static void destroyCosmosTerrainTarget() {
        if (cosmosTerrainDepthTexture != -1) {
            GlStateManager._deleteTexture(cosmosTerrainDepthTexture);
            cosmosTerrainDepthTexture = -1;
        }
        if (cosmosTerrainFbo != -1) {
            GL30.glDeleteFramebuffers(cosmosTerrainFbo);
            cosmosTerrainFbo = -1;
        }
    }

    private static void captureCosmosTerrainDepth() {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        int width = mainTarget.width;
        int height = mainTarget.height;

        ensureCosmosTerrainTarget(width, height);

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, cosmosTerrainFbo);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

        mainTarget.bindWrite(false);
    }

    @SuppressWarnings("deprecation")
    private static void renderSketch() {
        renderFilterPass(sketchShader, shader -> {
            if (shader.getUniform("ScreenSize") != null) {
                Minecraft mc = Minecraft.getInstance();
                shader.getUniform("ScreenSize").set((float) mc.getMainRenderTarget().width, (float) mc.getMainRenderTarget().height);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static void renderMatrix() {
        if (matrixStartNanos == 0) {
            matrixStartNanos = System.nanoTime();
        }
        float time = (System.nanoTime() - matrixStartNanos) / 1_000_000_000.0f;
        renderFilterPass(matrixShader, shader -> {
            Minecraft mc = Minecraft.getInstance();
            if (shader.getUniform("ScreenSize") != null) {
                shader.getUniform("ScreenSize").set((float) mc.getMainRenderTarget().width, (float) mc.getMainRenderTarget().height);
            }
            if (shader.getUniform("Time") != null) {
                shader.getUniform("Time").set(time);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static void renderRain() {
        if (rainStartNanos == 0) {
            rainStartNanos = System.nanoTime();
        }
        float time = (System.nanoTime() - rainStartNanos) / 1_000_000_000.0f;
        renderFilterPass(rainShader, shader -> {
            Minecraft mc = Minecraft.getInstance();
            if (shader.getUniform("ScreenSize") != null) {
                shader.getUniform("ScreenSize").set((float) mc.getMainRenderTarget().width, (float) mc.getMainRenderTarget().height);
            }
            if (shader.getUniform("Time") != null) {
                shader.getUniform("Time").set(time);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static void renderDesert() {
        if (desertStartNanos == 0) {
            desertStartNanos = System.nanoTime();
        }
        float time = (System.nanoTime() - desertStartNanos) / 1_000_000_000.0f;
        renderFilterPass(desertShader, shader -> {
            Minecraft mc = Minecraft.getInstance();
            if (shader.getUniform("ScreenSize") != null) {
                shader.getUniform("ScreenSize").set((float) mc.getMainRenderTarget().width, (float) mc.getMainRenderTarget().height);
            }
            if (shader.getUniform("Time") != null) {
                shader.getUniform("Time").set(time);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static void renderSnow() {
        if (snowStartNanos == 0) {
            snowStartNanos = System.nanoTime();
        }
        float time = (System.nanoTime() - snowStartNanos) / 1_000_000_000.0f;
        renderFilterPass(snowShader, shader -> {
            Minecraft mc = Minecraft.getInstance();
            if (shader.getUniform("ScreenSize") != null) {
                shader.getUniform("ScreenSize").set((float) mc.getMainRenderTarget().width, (float) mc.getMainRenderTarget().height);
            }
            if (shader.getUniform("Time") != null) {
                shader.getUniform("Time").set(time);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static void renderToxic() {
        if (toxicStartNanos == 0) {
            toxicStartNanos = System.nanoTime();
        }
        float time = (System.nanoTime() - toxicStartNanos) / 1_000_000_000.0f;
        renderFilterPass(toxicShader, shader -> {
            Minecraft mc = Minecraft.getInstance();
            if (shader.getUniform("ScreenSize") != null) {
                shader.getUniform("ScreenSize").set((float) mc.getMainRenderTarget().width, (float) mc.getMainRenderTarget().height);
            }
            if (shader.getUniform("Time") != null) {
                shader.getUniform("Time").set(time);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static void renderSpotlight(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        int width = mainTarget.width;
        int height = mainTarget.height;

        ensureCopyTargets(width, height);

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFbo);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

        mainTarget.bindWrite(false);

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();

        Matrix4f savedProj = RenderSystem.getProjectionMatrix();
        Matrix4f ortho = new Matrix4f().setOrtho(0.0f, (float) width, (float) height, 0.0f, -1000.0f, 1000.0f);
        RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);

        RenderSystem.getModelViewStack().pushPose();
        try {
            RenderSystem.getModelViewStack().setIdentity();
            RenderSystem.applyModelViewMatrix();

            RenderSystem.setShader(() -> spotlightShader);
            RenderSystem.setShaderTexture(0, depthCopyTexture);
            RenderSystem.setShaderTexture(1, colorCopyTexture);
            RenderSystem.setShaderTexture(2, spotlightColorTexture);
            if (spotlightShader.getUniform("ScreenSize") != null) {
                spotlightShader.getUniform("ScreenSize").set((float) width, (float) height);
            }

            drawFullscreenQuad(width, height);
        } finally {
            RenderSystem.getModelViewStack().popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProj, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
        }
    }

    @SuppressWarnings("deprecation")
    private static void captureSpotlightEntity(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Entity target = mc.crosshairPickEntity;
        RenderTarget mainTarget = mc.getMainRenderTarget();

        if (target == null || target.isRemoved() || mc.level == null || target.level() != mc.level) {
            int width = mainTarget.width;
            int height = mainTarget.height;
            ensureSpotlightTargets(width, height);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, spotlightFbo);
            GlStateManager._clearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, false);
            mainTarget.bindWrite(false);
            return;
        }

        int width = mainTarget.width;
        int height = mainTarget.height;
        ensureSpotlightTargets(width, height);

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, spotlightFbo);
        GlStateManager._clearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, false);

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, spotlightFbo);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, spotlightFbo);

        float partialTick = (float) event.getPartialTick();
        Vec3 camPos = event.getCamera().getPosition();
        double x = Mth.lerp(partialTick, target.xOld, target.getX()) - camPos.x;
        double y = Mth.lerp(partialTick, target.yOld, target.getY()) - camPos.y;
        double z = Mth.lerp(partialTick, target.zOld, target.getZ()) - camPos.z;
        float yRot = Mth.lerp(partialTick, target.yRotO, target.getYRot());

        PoseStack poseStack = event.getPoseStack();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        dispatcher.render(target, x, y, z, yRot, partialTick, poseStack, buffer,
                dispatcher.getPackedLightCoords(target, partialTick));
        buffer.endBatch();

        mainTarget.bindWrite(false);
    }

    private static void drawFullscreenQuad(int width, int height) {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(0.0f, 0.0f, 0.0f).uv(0.0f, 0.0f).endVertex();
        builder.vertex((float) width, 0.0f, 0.0f).uv(1.0f, 0.0f).endVertex();
        builder.vertex((float) width, (float) height, 0.0f).uv(1.0f, 1.0f).endVertex();
        builder.vertex(0.0f, (float) height, 0.0f).uv(0.0f, 1.0f).endVertex();
        BufferUploader.drawWithShader(builder.end());
    }

    @SuppressWarnings("deprecation")
    private static void renderFilterPass(ShaderInstance shader, Consumer<ShaderInstance> uniformApplier) {
        if (shader == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        int width = mainTarget.width;
        int height = mainTarget.height;

        ensureCopyTargets(width, height);

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFbo);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

        mainTarget.bindWrite(false);

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();

        Matrix4f savedProj = RenderSystem.getProjectionMatrix();
        Matrix4f ortho = new Matrix4f().setOrtho(0.0f, (float) width, (float) height, 0.0f, -1000.0f, 1000.0f);
        RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);

        RenderSystem.getModelViewStack().pushPose();
        try {
            RenderSystem.getModelViewStack().setIdentity();
            RenderSystem.applyModelViewMatrix();

            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderTexture(0, depthCopyTexture);
            RenderSystem.setShaderTexture(1, colorCopyTexture);
            uniformApplier.accept(shader);

            drawFullscreenQuad(width, height);
        } finally {
            RenderSystem.getModelViewStack().popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProj, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
        }
    }

    @SuppressWarnings("deprecation")
    private static void renderCosmos(RenderLevelStageEvent event) {
        if (cosmosStartNanos == 0) {
            cosmosStartNanos = System.nanoTime();
        }
        float time = (System.nanoTime() - cosmosStartNanos) / 1_000_000_000.0f;
        renderWorldFilterPass(event, cosmosShader, shader -> {
            Minecraft mc = Minecraft.getInstance();
            RenderSystem.setShaderTexture(3, cosmosTerrainDepthTexture);
            if (shader.getUniform("ScreenSize") != null) {
                shader.getUniform("ScreenSize").set((float) mc.getMainRenderTarget().width, (float) mc.getMainRenderTarget().height);
            }
            if (shader.getUniform("Time") != null) {
                shader.getUniform("Time").set(time);
            }
        });
    }

    /* 世界空间滤镜通道：在 renderFilterPass 的基础上，额外向着色器提供逐像素世界坐标
       反算所需的数据，使效果附着在世界表面而非屏幕。任何"贴世界表面"的滤镜均可复用。
       视图旋转取自 RenderSystem 的逆视图旋转矩阵（AFTER_LEVEL 阶段 event 的 poseStack
       是投影栈，不可用）。着色器契约（除 Sampler0=深度、Sampler1=颜色外）：
         uniform mat4 InvViewProjMat  // inverse(ProjMat * ViewRotMat)，相机相对
         uniform vec3 CameraPos       // 相机世界坐标
       反算公式：worldPos = (InvViewProjMat * vec4(ndc, 1)).xyz / w + CameraPos */
    @SuppressWarnings("deprecation")
    private static void renderWorldFilterPass(RenderLevelStageEvent event, ShaderInstance shader, Consumer<ShaderInstance> uniformApplier) {
        if (shader == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        int width = mainTarget.width;
        int height = mainTarget.height;

        ensureCopyTargets(width, height);

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainTarget.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFbo);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);

        mainTarget.bindWrite(false);

        // 相机相对反算矩阵：AFTER_LEVEL 阶段 event.getPoseStack() 实为投影栈，不能用；
        // 视图旋转改从 RenderSystem 的逆视图旋转矩阵还原，再与投影组合求逆
        Matrix3f viewRot = new Matrix3f(RenderSystem.getInverseViewRotationMatrix()).invert();
        Matrix4f view = new Matrix4f().set(viewRot);
        Matrix4f invViewProj = new Matrix4f(event.getProjectionMatrix())
                .mul(view)
                .invert();
        Vec3 cam = event.getCamera().getPosition();

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();

        Matrix4f savedProj = RenderSystem.getProjectionMatrix();
        Matrix4f ortho = new Matrix4f().setOrtho(0.0f, (float) width, (float) height, 0.0f, -1000.0f, 1000.0f);
        RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);

        RenderSystem.getModelViewStack().pushPose();
        try {
            RenderSystem.getModelViewStack().setIdentity();
            RenderSystem.applyModelViewMatrix();

            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderTexture(0, depthCopyTexture);
            RenderSystem.setShaderTexture(1, colorCopyTexture);
            if (shader.getUniform("InvViewProjMat") != null) {
                shader.getUniform("InvViewProjMat").set(invViewProj);
            }
            if (shader.getUniform("CameraPos") != null) {
                shader.getUniform("CameraPos").set((float) cam.x, (float) cam.y, (float) cam.z);
            }
            uniformApplier.accept(shader);

            drawFullscreenQuad(width, height);
        } finally {
            RenderSystem.getModelViewStack().popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProj, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
        }
    }
}
