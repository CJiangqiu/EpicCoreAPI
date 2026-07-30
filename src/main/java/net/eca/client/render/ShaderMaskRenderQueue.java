package net.eca.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.eca.client.render.shader.EcaShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public final class ShaderMaskRenderQueue {

    private static final int MAX_POOL_SIZE = 64;
    private static final List<QueuedPass> QUEUE = new ArrayList<>();
    private static final Deque<BufferBuilder> BUILDER_POOL = new ArrayDeque<>();

    private ShaderMaskRenderQueue() {
    }

    public static BufferBuilder acquireBuilder() {
        BufferBuilder builder = BUILDER_POOL.pollFirst();
        return builder == null ? new BufferBuilder(262144) : builder;
    }

    public static void enqueue(ShaderMaskPass pass, BufferBuilder builder,
                               BufferBuilder.RenderedBuffer renderedBuffer) {
        enqueue(pass, builder, renderedBuffer, MaskUvTransform.IDENTITY);
    }

    public static void enqueue(ShaderMaskPass pass, BufferBuilder builder,
                               BufferBuilder.RenderedBuffer renderedBuffer,
                               MaskUvTransform uvTransform) {
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        QUEUE.add(new QueuedPass(pass, builder, renderedBuffer, modelView, projection,
            uvTransform == null ? MaskUvTransform.IDENTITY : uvTransform));
    }

    public static void drawNow(ShaderMaskPass pass, BufferBuilder builder,
                               BufferBuilder.RenderedBuffer renderedBuffer) {
        drawNow(pass, builder, renderedBuffer, MaskUvTransform.IDENTITY);
    }

    public static void drawNow(ShaderMaskPass pass, BufferBuilder builder,
                               BufferBuilder.RenderedBuffer renderedBuffer,
                               MaskUvTransform uvTransform) {
        try {
            draw(pass, renderedBuffer, uvTransform == null ? MaskUvTransform.IDENTITY : uvTransform);
        } finally {
            recycle(builder);
        }
    }

    public static void flush() {
        if (QUEUE.isEmpty()) {
            return;
        }
        List<QueuedPass> entries = new ArrayList<>(QUEUE);
        QUEUE.clear();
        Runnable work = () -> flushEntries(entries);
        if (RenderSystem.isOnRenderThread()) {
            work.run();
        } else {
            RenderSystem.recordRenderCall(work::run);
        }
    }

    private static void flushEntries(List<QueuedPass> entries) {
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        try {
            for (QueuedPass entry : entries) {
                RenderSystem.getModelViewStack().pushPose();
                try {
                    RenderSystem.getModelViewStack().setIdentity();
                    RenderSystem.getModelViewStack().mulPoseMatrix(entry.modelView());
                    RenderSystem.applyModelViewMatrix();
                    RenderSystem.setProjectionMatrix(entry.projection(), VertexSorting.DISTANCE_TO_ORIGIN);
                    draw(entry.pass(), entry.renderedBuffer(), entry.uvTransform());
                } finally {
                    RenderSystem.getModelViewStack().popPose();
                    RenderSystem.applyModelViewMatrix();
                    recycle(entry.builder());
                }
            }
        } finally {
            RenderSystem.setProjectionMatrix(savedProjection, VertexSorting.DISTANCE_TO_ORIGIN);
        }
    }

    private static void draw(ShaderMaskPass pass, BufferBuilder.RenderedBuffer renderedBuffer,
                             MaskUvTransform uvTransform) {
        MaskUvTransform appliedUv = pass.maskSource() == ShaderMaskSource.TEXTURE
            ? uvTransform : MaskUvTransform.IDENTITY;
        EcaShaderInstance.setShaderMask(pass.maskSource(), pass.maskTexture(),
            pass.maskColor(), pass.maskTolerance());
        EcaShaderInstance.setLocalUvBounds(appliedUv.minU(), appliedUv.minV(),
            appliedUv.scaleU(), appliedUv.scaleV());
        EcaShaderInstance.setOpacity(pass.alpha());
        boolean stateActive = false;
        try {
            pass.renderType().setupRenderState();
            stateActive = true;
            BufferUploader.drawWithShader(renderedBuffer);
        } finally {
            if (stateActive) {
                pass.renderType().clearRenderState();
            }
            EcaShaderInstance.clearShaderMask();
            EcaShaderInstance.clearLocalUvBounds();
            EcaShaderInstance.clearOpacity();
        }
    }

    private static void recycle(BufferBuilder builder) {
        if (builder != null && BUILDER_POOL.size() < MAX_POOL_SIZE) {
            BUILDER_POOL.addLast(builder);
        }
    }

    private record QueuedPass(
        ShaderMaskPass pass,
        BufferBuilder builder,
        BufferBuilder.RenderedBuffer renderedBuffer,
        Matrix4f modelView,
        Matrix4f projection,
        MaskUvTransform uvTransform
    ) {
    }
}
