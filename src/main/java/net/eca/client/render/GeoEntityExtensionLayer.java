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

import java.util.function.Function;

@OnlyIn(Dist.CLIENT)
public class GeoEntityExtensionLayer<T extends GeoAnimatable> extends GeoRenderLayer<T> {

    private final GeoBoneVisibilityController boneVisibility = new GeoBoneVisibilityController();
    private final Function<T, Entity> entityResolver;
    private EntityLayerExtension activeExtension;

    public GeoEntityExtensionLayer(GeoRenderer<T> renderer, Function<T, Entity> entityResolver) {
        super(renderer);
        this.entityResolver = entityResolver;
    }

    @Override
    public void preRender(PoseStack poseStack, T animatable, BakedGeoModel bakedModel, RenderType renderType,
                          MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                          int packedLight, int packedOverlay) {
        activeExtension = findExtension(animatable);
        if (activeExtension != null) {
            boneVisibility.begin(bakedModel, activeExtension);
        }
    }

    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel bakedModel, RenderType renderType,
                       MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                       int packedLight, int packedOverlay) {

        EntityLayerExtension layerExtension = activeExtension;
        try {
            if (layerExtension == null) return;
            renderOverlay(poseStack, animatable, bakedModel, bufferSource, partialTick, packedLight,
                    packedOverlay, layerExtension);
        } finally {
            boneVisibility.restore();
            activeExtension = null;
        }
    }

    private EntityLayerExtension findExtension(T animatable) {
        Entity entity = entityResolver.apply(animatable);
        if (entity == null) return null;
        EntityExtension extension = EntityExtensionManager.getExtension(entity.getType());
        if (extension == null) return null;
        LivingEntity living = entity instanceof LivingEntity value ? value : null;
        EntityLayerExtension layer = EntityExtensionSafeAccess.entityLayerExtension(extension, living);
        return layer != null && layer.enabled() && layer.shouldRender(living) ? layer : null;
    }

    private void renderOverlay(PoseStack poseStack, T animatable, BakedGeoModel bakedModel,
                               MultiBufferSource bufferSource, float partialTick, int packedLight,
                               int packedOverlay, EntityLayerExtension layerExtension) {
        RenderType shaderType = layerExtension.getRenderType();
        ResourceLocation texture = layerExtension.getTexture();
        if (shaderType == null && texture == null) return;
        boneVisibility.restrictOverlay(bakedModel, layerExtension.overlayGeoBones());

        int light = layerExtension.isGlow() ? 15728880 : packedLight;
        int overlay = layerExtension.isHurtOverlay() ? packedOverlay : OverlayTexture.NO_OVERLAY;
        float alpha = layerExtension.getAlpha();
        boolean oculus = EcaShaderInstance.isOculusShadersActive();

        renderPass(poseStack, animatable, bakedModel, bufferSource, partialTick, light, overlay, alpha,
                texture == null ? null : RenderType.entityTranslucent(texture), oculus);
        renderPass(poseStack, animatable, bakedModel, bufferSource, partialTick, light, overlay, alpha, shaderType, oculus);
    }

    private void renderPass(PoseStack poseStack, T animatable, BakedGeoModel bakedModel,
                            MultiBufferSource bufferSource, float partialTick, int light, int overlay,
                            float alpha, RenderType type, boolean oculus) {
        if (type == null) return;
        if (oculus) {
            BufferBuilder builder = EntityLayerRenderQueue.acquireBuilder();
            builder.begin(VertexFormat.Mode.QUADS, type.format());
            this.renderer.reRender(bakedModel, poseStack, rt -> builder, animatable, type, builder,
                    partialTick, light, overlay, 1.0f, 1.0f, 1.0f, alpha);
            EntityLayerRenderQueue.enqueue(type, builder, builder.end());
        } else {
            this.renderer.reRender(bakedModel, poseStack, bufferSource, animatable, type,
                    bufferSource.getBuffer(type), partialTick, light, overlay, 1.0f, 1.0f, 1.0f, alpha);
        }
    }
}
