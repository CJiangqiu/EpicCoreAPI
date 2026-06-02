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
 * Oculus 兼容：物品扩展着色层延迟队列。光影开启时世界内的物品（掉落物/展示框/手持等）渲染目标是 G-buffer，着色器输出在延迟合成中丢失，
 * 故把顶点数据缓存到队列，在 Oculus 管线合成后（主帧缓冲区活跃时）统一绘制；BufferBuilder 池化避免每帧分配 native 内存。
 * 与 EntityLayerRenderQueue 的本质差别：ColorKey/LocalUvBounds 是逐物品的静态共享状态，而 shader 在 setupRenderState() 里才读它们，
 * 入队与 flush 时机错开，故必须随每个 entry 快照、绘制前重设、绘制后清除，否则多物品会串色。
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
                               float uvMinU, float uvMinV, float uvScaleU, float uvScaleV) {
        Matrix4f modelViewMat = new Matrix4f(RenderSystem.getModelViewMatrix());
        Matrix4f projMat = new Matrix4f(RenderSystem.getProjectionMatrix());
        queue.add(new QueuedItemLayer(renderType, builder, renderedBuffer, modelViewMat, projMat,
            colorKeyR, colorKeyG, colorKeyB, colorKeyTolerance, uvMinU, uvMinV, uvScaleU, uvScaleV));
    }

    public static void flush() {
        if (queue.isEmpty()) {
            return;
        }

        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());

        try {
            for (QueuedItemLayer entry : queue) {
                RenderSystem.getModelViewStack().pushPose();
                RenderSystem.getModelViewStack().setIdentity();
                RenderSystem.getModelViewStack().mulPoseMatrix(entry.modelViewMat);
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(entry.projMat, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);

                // 在 setupRenderState() 触发 shader 读取 uniform 之前，恢复本 entry 的 ColorKey/UvBounds 快照
                EcaShaderInstance.setColorKey(entry.colorKeyR, entry.colorKeyG, entry.colorKeyB, entry.colorKeyTolerance);
                EcaShaderInstance.setLocalUvBounds(entry.uvMinU, entry.uvMinV, entry.uvScaleU, entry.uvScaleV);

                entry.renderType.setupRenderState();
                BufferUploader.drawWithShader(entry.renderedBuffer);
                entry.renderType.clearRenderState();

                EcaShaderInstance.clearColorKey();
                EcaShaderInstance.clearLocalUvBounds();

                RenderSystem.getModelViewStack().popPose();
                RenderSystem.applyModelViewMatrix();

                if (builderPool.size() < MAX_POOL_SIZE) {
                    builderPool.addLast(entry.builder);
                }
            }
        } finally {
            RenderSystem.setProjectionMatrix(savedProj, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
            queue.clear();
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
        float uvScaleV
    ) {}
}
