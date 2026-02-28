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
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

@OnlyIn(Dist.CLIENT)
public class GeoEntityExtensionLayer<T extends Entity & GeoAnimatable> extends GeoRenderLayer<T> {

    public GeoEntityExtensionLayer(GeoRenderer<T> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel bakedModel, RenderType renderType,
                       MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                       int packedLight, int packedOverlay) {

        EntityExtension extension = EntityExtensionManager.getExtension(animatable.getType());
        if (extension == null) return;

        EntityLayerExtension layerExtension = extension.entityLayerExtension();
        if (layerExtension == null || !layerExtension.enabled()) return;

        RenderType shaderRenderType = layerExtension.getRenderType();
        if (shaderRenderType == null) return;

        int light = layerExtension.isGlow() ? 15728880 : packedLight;
        int overlay = layerExtension.isHurtOverlay() ? packedOverlay : OverlayTexture.NO_OVERLAY;
        float alpha = layerExtension.getAlpha();

        if (EcaShaderInstance.isOculusShadersActive()) {
            // Oculus光影激活：捕获顶点数据入队列，延迟到管线合成后渲染
            BufferBuilder builder = EntityLayerRenderQueue.acquireBuilder();
            builder.begin(VertexFormat.Mode.QUADS, shaderRenderType.format());
            MultiBufferSource captureSource = rt -> builder;
            this.renderer.reRender(bakedModel, poseStack, captureSource, animatable,
                    shaderRenderType, builder, partialTick, light, overlay, 1.0f, 1.0f, 1.0f, alpha);
            EntityLayerRenderQueue.enqueue(shaderRenderType, builder, builder.end());
        } else {
            VertexConsumer shaderBuffer = bufferSource.getBuffer(shaderRenderType);
            this.renderer.reRender(bakedModel, poseStack, bufferSource, animatable,
                    shaderRenderType, shaderBuffer, partialTick, light, overlay, 1.0f, 1.0f, 1.0f, alpha);
        }
    }
}
