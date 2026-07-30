package net.eca.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.eca.client.render.shader.EcaShaderInstance;
import net.eca.util.block_extension.BlockExtension;
import net.eca.util.block_extension.BlockExtensionManager;
import net.eca.util.block_extension.BlockExtensionSafeAccess;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public class GeoBlockExtensionLayer<T extends BlockEntity & GeoAnimatable> extends GeoRenderLayer<T> {

    private final GeoBoneVisibilityController boneVisibility = new GeoBoneVisibilityController();
    private BlockExtension activeExtension;

    public GeoBlockExtensionLayer(GeoBlockRenderer<T> renderer) {
        super(renderer);
    }

    @Override
    public void preRender(PoseStack poseStack, T animatable, BakedGeoModel bakedModel, RenderType renderType,
                          MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                          int packedLight, int packedOverlay) {
        BlockExtension extension = BlockExtensionManager.getExtension(animatable.getBlockState().getBlock());
        activeExtension = extension != null && animatable.getLevel() != null
            && BlockExtensionSafeAccess.shouldRender(extension, animatable.getBlockState(),
                animatable.getLevel(), animatable.getBlockPos()) ? extension : null;
        if (activeExtension != null) {
            boneVisibility.begin(bakedModel, activeExtension.hiddenGeoBones());
        }
    }

    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel bakedModel, RenderType renderType,
                       MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                       int packedLight, int packedOverlay) {
        try {
            if (activeExtension == null) {
                return;
            }
            ResourceLocation texture = getTextureResource(animatable);
            List<ShaderMaskPass> passes = activeExtension.getGeoShaderPasses(texture);
            if (passes == null || passes.isEmpty()) {
                return;
            }
            boneVisibility.restrictOverlay(bakedModel, activeExtension.overlayGeoBones());
            int light = activeExtension.isGlow() ? 15728880 : packedLight;
            boolean queued = EcaShaderInstance.isOculusShadersActive();
            for (ShaderMaskPass pass : passes) {
                renderPass(poseStack, animatable, bakedModel, bufferSource, partialTick, light,
                    pass, queued);
            }
        } finally {
            boneVisibility.restore();
            activeExtension = null;
        }
    }

    private void renderPass(PoseStack poseStack, T animatable, BakedGeoModel bakedModel,
                            MultiBufferSource bufferSource, float partialTick, int light,
                            ShaderMaskPass pass, boolean queued) {
        if (pass == null || pass.alpha() <= 0.0f) return;
        RenderType renderType = pass.renderType();
        BufferBuilder builder = ShaderMaskRenderQueue.acquireBuilder();
        builder.begin(VertexFormat.Mode.QUADS, renderType.format());
        renderer.reRender(bakedModel, poseStack, ignored -> builder, animatable, renderType, builder,
            partialTick, light, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
        if (queued) {
            ShaderMaskRenderQueue.enqueue(pass, builder, builder.end());
        } else {
            if (bufferSource instanceof MultiBufferSource.BufferSource source) {
                source.endBatch();
            }
            ShaderMaskRenderQueue.drawNow(pass, builder, builder.end());
        }
    }
}
