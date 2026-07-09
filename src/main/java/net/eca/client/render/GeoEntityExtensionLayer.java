package net.eca.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.eca.client.render.shader.EcaShaderInstance;
import net.eca.util.entity_extension.EntityExtension;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.entity_extension.EntityExtensionSafeAccess;
import net.eca.util.entity_extension.EntityLayerExtension;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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

        LivingEntity livingAnimatable = animatable instanceof LivingEntity living ? living : null;
        EntityLayerExtension layerExtension = EntityExtensionSafeAccess.entityLayerExtension(extension, livingAnimatable);
        if (layerExtension == null || !layerExtension.enabled() || !layerExtension.shouldRender(livingAnimatable)) return;

        RenderType shaderType = layerExtension.getRenderType();
        ResourceLocation texture = layerExtension.getTexture();
        if (shaderType == null && texture == null) return;

        int light = layerExtension.isGlow() ? 15728880 : packedLight;
        int overlay = layerExtension.isHurtOverlay() ? packedOverlay : OverlayTexture.NO_OVERLAY;
        float alpha = layerExtension.getAlpha();

        boolean hasTexture = texture != null;
        boolean hasShader = shaderType != null;
        boolean oculus = EcaShaderInstance.isOculusShadersActive();

        if (hasTexture) {
            RenderType texturedLayer = RenderType.entityTranslucent(texture);
            if (oculus) {
                BufferBuilder builder = EntityLayerRenderQueue.acquireBuilder();
                builder.begin(VertexFormat.Mode.QUADS, texturedLayer.format());
                MultiBufferSource captureSource = rt -> builder;
                this.renderer.reRender(bakedModel, poseStack, captureSource, animatable,
                        texturedLayer, builder, partialTick, light, overlay, 1.0f, 1.0f, 1.0f, alpha);
                EntityLayerRenderQueue.enqueue(texturedLayer, builder, builder.end());
            } else {
                VertexConsumer texBuffer = bufferSource.getBuffer(texturedLayer);
                this.renderer.reRender(bakedModel, poseStack, bufferSource, animatable,
                        texturedLayer, texBuffer, partialTick, light, overlay, 1.0f, 1.0f, 1.0f, alpha);
            }
        }

        if (hasShader) {
            if (oculus) {
                BufferBuilder builder = EntityLayerRenderQueue.acquireBuilder();
                builder.begin(VertexFormat.Mode.QUADS, shaderType.format());
                MultiBufferSource captureSource = rt -> builder;
                this.renderer.reRender(bakedModel, poseStack, captureSource, animatable,
                        shaderType, builder, partialTick, light, overlay, 1.0f, 1.0f, 1.0f, alpha);
                EntityLayerRenderQueue.enqueue(shaderType, builder, builder.end());
            } else {
                VertexConsumer shaderBuffer = bufferSource.getBuffer(shaderType);
                this.renderer.reRender(bakedModel, poseStack, bufferSource, animatable,
                        shaderType, shaderBuffer, partialTick, light, overlay, 1.0f, 1.0f, 1.0f, alpha);
            }
        }
    }
}
