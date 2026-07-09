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
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
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

        EntityLayerExtension layerExtension = EntityExtensionSafeAccess.entityLayerExtension(extension, entity);
        if (layerExtension == null || !layerExtension.enabled() || !layerExtension.shouldRender(entity)) {
            return;
        }

        RenderType shaderType = layerExtension.getRenderType();
        ResourceLocation texture = layerExtension.getTexture();
        if (shaderType == null && texture == null) {
            return;
        }

        int light = layerExtension.isGlow() ? 15728880 : packedLight;
        int overlay = layerExtension.isHurtOverlay()
            ? LivingEntityRenderer.getOverlayCoords(entity, 0.0f)
            : OverlayTexture.NO_OVERLAY;
        float alpha = layerExtension.getAlpha();

        // 模式 1: 纯着色器（shaderType != null, texture == null）
        // 模式 2: 纯纹理（shaderType == null, texture != null）
        // 模式 3: 纹理 + 着色器叠加（shaderType != null, texture != null）
        //         先纹理层（vanilla translucent），再着色器层（ECA shader），如 Boss 血条叠加技法
        boolean hasTexture = texture != null;
        boolean hasShader = shaderType != null;
        boolean oculus = EcaShaderInstance.isOculusShadersActive();

        if (hasTexture) {
            RenderType texturedLayer = RenderType.entityTranslucent(texture);
            if (oculus) {
                BufferBuilder builder = EntityLayerRenderQueue.acquireBuilder();
                builder.begin(VertexFormat.Mode.QUADS, texturedLayer.format());
                this.getParentModel().renderToBuffer(
                    poseStack, builder, light, overlay, 1.0f, 1.0f, 1.0f, alpha
                );
                EntityLayerRenderQueue.enqueue(texturedLayer, builder, builder.end());
            } else {
                VertexConsumer texConsumer = bufferSource.getBuffer(texturedLayer);
                this.getParentModel().renderToBuffer(
                    poseStack, texConsumer, light, overlay, 1.0f, 1.0f, 1.0f, alpha
                );
            }
        }

        if (hasShader) {
            if (oculus) {
                BufferBuilder builder = EntityLayerRenderQueue.acquireBuilder();
                builder.begin(VertexFormat.Mode.QUADS, shaderType.format());
                this.getParentModel().renderToBuffer(
                    poseStack, builder, light, overlay, 1.0f, 1.0f, 1.0f, alpha
                );
                EntityLayerRenderQueue.enqueue(shaderType, builder, builder.end());
            } else {
                VertexConsumer shaderConsumer = bufferSource.getBuffer(shaderType);
                this.getParentModel().renderToBuffer(
                    poseStack, shaderConsumer, light, overlay, 1.0f, 1.0f, 1.0f, alpha
                );
            }
        }
    }

}
