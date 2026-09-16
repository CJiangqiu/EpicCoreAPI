package net.eca.client.render.blender;

import com.mojang.blaze3d.vertex.PoseStack;
import net.eca.util.entity_extension.BlenderModelExtension;
import net.eca.util.entity_extension.EntityExtension;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.entity_extension.EntityExtensionSafeAccess;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class BlenderModelLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
    public BlenderModelLayer(RenderLayerParent<T, M> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        EntityExtension extension = EntityExtensionManager.getExtension(entity.getType());
        BlenderModelExtension model = EntityExtensionSafeAccess.blenderModelExtension(extension, entity);
        if (model == null || EntityExtensionSafeAccess.usesBlenderReplacement(model)) {
            return;
        }
        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0f);
        BlenderModelRenderer.render(entity, model, poseStack, buffers, packedLight, overlay, partialTick);
    }
}
