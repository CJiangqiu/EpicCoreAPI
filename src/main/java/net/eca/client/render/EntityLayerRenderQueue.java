package net.eca.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

// Oculus兼容：实体渲染层延迟队列
// 光影开启时实体层渲染目标是G-buffer，着色器输出在延迟合成中丢失。
// 将顶点数据缓存到队列，在Oculus管线合成后（主帧缓冲区活跃时）统一绘制。
// 使用BufferBuilder池避免每帧分配native内存导致OOM。
// 保存渲染时的矩阵状态，flush时恢复，防止玩家移动导致渲染层与实体分离。
@OnlyIn(Dist.CLIENT)
public class EntityLayerRenderQueue {

    private static final int MAX_POOL_SIZE = 32;
    private static final List<QueuedEntityLayer> queue = new ArrayList<>();
    private static final Deque<BufferBuilder> builderPool = new ArrayDeque<>();

    public static BufferBuilder acquireBuilder() {
        BufferBuilder builder = builderPool.pollFirst();
        if (builder == null) {
            builder = new BufferBuilder(262144);
        }
        return builder;
    }

    public static void enqueue(RenderType renderType, BufferBuilder builder, BufferBuilder.RenderedBuffer renderedBuffer) {
        Matrix4f modelViewMat = new Matrix4f(RenderSystem.getModelViewMatrix());
        Matrix4f projMat = new Matrix4f(RenderSystem.getProjectionMatrix());
        queue.add(new QueuedEntityLayer(renderType, builder, renderedBuffer, modelViewMat, projMat));
    }

    public static void flush() {
        if (queue.isEmpty()) {
            return;
        }

        Matrix4f savedModelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());

        try {
            for (QueuedEntityLayer entry : queue) {
                RenderSystem.getModelViewStack().pushPose();
                RenderSystem.getModelViewStack().setIdentity();
                RenderSystem.getModelViewStack().mulPoseMatrix(entry.modelViewMat);
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(entry.projMat, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);

                entry.renderType.setupRenderState();
                BufferUploader.drawWithShader(entry.renderedBuffer);
                entry.renderType.clearRenderState();

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

    private record QueuedEntityLayer(
        RenderType renderType,
        BufferBuilder builder,
        BufferBuilder.RenderedBuffer renderedBuffer,
        Matrix4f modelViewMat,
        Matrix4f projMat
    ) {}
}
