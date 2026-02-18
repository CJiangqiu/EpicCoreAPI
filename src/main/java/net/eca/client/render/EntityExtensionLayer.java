package net.eca.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.eca.client.render.shader.EcaShaderInstance;
import net.eca.util.entity_extension.EntityExtension;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.entity_extension.EntityLayerExtension;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class EntityExtensionLayer<T extends LivingEntity, M extends net.minecraft.client.model.EntityModel<T>>
    extends RenderLayer<T, M> {

    public EntityExtensionLayer(RenderLayerParent<T, M> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {

        EntityExtension extension = EntityExtensionManager.getExtension(entity.getType());
        if (extension == null) {
            return;
        }

        EntityLayerExtension layerExtension = extension.entityLayerExtension();
        if (layerExtension == null || !layerExtension.enabled()) {
            return;
        }

        RenderType renderType = layerExtension.getRenderType();
        if (renderType == null) {
            return;
        }

        int light = layerExtension.isGlow() ? 15728880 : packedLight;
        int overlay = layerExtension.isHurtOverlay()
            ? LivingEntityRenderer.getOverlayCoords(entity, 0.0f)
            : OverlayTexture.NO_OVERLAY;
        float alpha = layerExtension.getAlpha();

        if (EcaShaderInstance.isOculusShadersActive()) {
            // Oculus光影激活：捕获顶点数据入队列，延迟到管线合成后渲染
            BufferBuilder builder = EntityLayerRenderQueue.acquireBuilder();
            builder.begin(VertexFormat.Mode.QUADS, renderType.format());
            this.getParentModel().renderToBuffer(
                poseStack, builder, light, overlay, 1.0f, 1.0f, 1.0f, alpha
            );
            EntityLayerRenderQueue.enqueue(renderType, builder, builder.end());
        } else {
            VertexConsumer vertexConsumer = bufferSource.getBuffer(renderType);
            this.getParentModel().renderToBuffer(
                poseStack, vertexConsumer, light, overlay, 1.0f, 1.0f, 1.0f, alpha
            );
        }
    }

}
