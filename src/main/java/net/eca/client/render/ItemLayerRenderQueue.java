package net.eca.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.eca.client.render.shader.EcaShaderInstance;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/*
 * Item extension layers use a queue for world contexts because vanilla may flush item/entity buffers
 * after ItemRenderer returns. The queue snapshots matrices, ColorKey, and UV bounds so the shader
 * state is restored when the buffered vertices are actually drawn.
 */
@OnlyIn(Dist.CLIENT)
public class ItemLayerRenderQueue {

    private static final int MAX_POOL_SIZE = 32;
    private static final List<QueuedItemLayer> queue = new ArrayList<>();
    private static final Deque<BufferBuilder> builderPool = new ArrayDeque<>();

    public static BufferBuilder acquireBuilder() {
        BufferBuilder builder = builderPool.pollFirst();
        if (builder == null) {
            builder = new BufferBuilder(262144);
        }
        return builder;
    }

    public static void enqueue(RenderType renderType, BufferBuilder builder, BufferBuilder.RenderedBuffer renderedBuffer,
                               float colorKeyR, float colorKeyG, float colorKeyB, float colorKeyTolerance,
                               float uvMinU, float uvMinV, float uvScaleU, float uvScaleV,
                               float alpha) {
        Matrix4f modelViewMat = new Matrix4f(RenderSystem.getModelViewMatrix());
        Matrix4f projMat = new Matrix4f(RenderSystem.getProjectionMatrix());
        queue.add(new QueuedItemLayer(renderType, builder, renderedBuffer, modelViewMat, projMat,
            colorKeyR, colorKeyG, colorKeyB, colorKeyTolerance, uvMinU, uvMinV, uvScaleU, uvScaleV, alpha));
    }

    public static void flush() {
        if (queue.isEmpty()) {
            return;
        }

        List<QueuedItemLayer> entries = new ArrayList<>(queue);
        queue.clear();
        Runnable flushWork = () -> flushEntries(entries);
        if (RenderSystem.isOnRenderThread()) {
            flushWork.run();
        } else {
            RenderSystem.recordRenderCall(flushWork::run);
        }
    }

    private static void flushEntries(List<QueuedItemLayer> entries) {
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        try {
            for (QueuedItemLayer entry : entries) {
                drawEntry(entry);
            }
        } finally {
            RenderSystem.setProjectionMatrix(savedProj, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        }
    }

    private static void drawEntry(QueuedItemLayer entry) {
        RenderSystem.getModelViewStack().pushPose();
        try {
            RenderSystem.getModelViewStack().setIdentity();
            RenderSystem.getModelViewStack().mulPoseMatrix(entry.modelViewMat);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(entry.projMat, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);

            EcaShaderInstance.setColorKey(entry.colorKeyR, entry.colorKeyG, entry.colorKeyB, entry.colorKeyTolerance);
            EcaShaderInstance.setLocalUvBounds(entry.uvMinU, entry.uvMinV, entry.uvScaleU, entry.uvScaleV);
            EcaShaderInstance.setOpacity(entry.alpha);

            entry.renderType.setupRenderState();
            BufferUploader.drawWithShader(entry.renderedBuffer);
            entry.renderType.clearRenderState();
        } finally {
            RenderSystem.getModelViewStack().popPose();
            RenderSystem.applyModelViewMatrix();
            EcaShaderInstance.clearColorKey();
            EcaShaderInstance.clearLocalUvBounds();
            EcaShaderInstance.clearOpacity();

            if (builderPool.size() < MAX_POOL_SIZE) {
                builderPool.addLast(entry.builder);
            }
        }
    }

    private record QueuedItemLayer(
        RenderType renderType,
        BufferBuilder builder,
        BufferBuilder.RenderedBuffer renderedBuffer,
        Matrix4f modelViewMat,
        Matrix4f projMat,
        float colorKeyR,
        float colorKeyG,
        float colorKeyB,
        float colorKeyTolerance,
        float uvMinU,
        float uvMinV,
        float uvScaleU,
        float uvScaleV,
        float alpha
    ) {}
}
