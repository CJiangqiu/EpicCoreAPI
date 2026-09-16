package net.eca.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.eca.client.render.blender.BlenderModelLayer;
import net.eca.client.render.blender.BlenderModelRenderer;
import net.eca.client.render.EntityExtensionLayer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.eca.util.entity_extension.BlenderModelExtension;
import net.eca.util.entity_extension.EntityExtension;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.entity_extension.EntityExtensionSafeAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyIn(Dist.CLIENT)
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, M extends EntityModel<T>> {

    @Unique
    private boolean eca$blenderReplacedBody;

    @Inject(method = "render", at = @At("HEAD"))
    private void eca$resetBlenderReplacement(T entity, float entityYaw, float partialTick,
                                             PoseStack poseStack, MultiBufferSource buffers,
                                             int packedLight, CallbackInfo ci) {
        eca$blenderReplacedBody = false;
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "<init>", at = @At("RETURN"))
    private void eca$addExtensionLayer(EntityRendererProvider.Context context, M model, float shadowRadius, CallbackInfo ci) {
        LivingEntityRenderer<T, M> self = (LivingEntityRenderer<T, M>) (Object) this;
        self.addLayer(new EntityExtensionLayer<>(self));
        self.addLayer(new BlenderModelLayer<>(self));
    }

    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"
        )
    )
    private void eca$renderBlenderReplacement(EntityModel<T> model, PoseStack poseStack, VertexConsumer consumer,
                                              int packedLight, int packedOverlay, float red, float green,
                                              float blue, float alpha, T entity, float entityYaw,
                                              float partialTick, PoseStack methodPoseStack,
                                              MultiBufferSource buffers, int methodPackedLight) {
        EntityExtension extension = EntityExtensionManager.getExtension(entity.getType());
        BlenderModelExtension blender = EntityExtensionSafeAccess.blenderModelExtension(extension, entity);
        if (EntityExtensionSafeAccess.usesBlenderReplacement(blender)
            && BlenderModelRenderer.render(entity, blender, poseStack, buffers, methodPackedLight,
                packedOverlay, partialTick)) {
            eca$blenderReplacedBody = true;
            return;
        }
        model.renderToBuffer(poseStack, consumer, packedLight, packedOverlay, red, green, blue, alpha);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"
        )
    )
    private void eca$skipVanillaLayersAfterReplacement(RenderLayer layer, PoseStack poseStack,
                                                        MultiBufferSource buffers, int packedLight,
                                                        Entity layerEntity, float limbSwing,
                                                        float limbSwingAmount, float partialTick,
                                                        float ageInTicks, float netHeadYaw, float headPitch,
                                                        T entity, float entityYaw, float methodPartialTick,
                                                        PoseStack methodPoseStack,
                                                        MultiBufferSource methodBuffers,
                                                        int methodPackedLight) {
        if (!eca$blenderReplacedBody) {
            layer.render(poseStack, buffers, packedLight, layerEntity, limbSwing, limbSwingAmount,
                partialTick, ageInTicks, netHeadYaw, headPitch);
        }
    }
}
