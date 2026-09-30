package net.eca.client.render.blender;

import com.mojang.blaze3d.vertex.PoseStack;
import net.eca.blender.client.entity.BlenderEntityRenderer;
import net.eca.util.entity_extension.BlenderModelExtension;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Compatibility entry point for existing entity extensions. */
@OnlyIn(Dist.CLIENT)
public final class BlenderModelRenderer {
    private BlenderModelRenderer() { }

    public static boolean render(LivingEntity entity, BlenderModelExtension extension, PoseStack poseStack,
                                 MultiBufferSource buffers, int packedLight, int overlay, float partialTick) {
        return BlenderEntityRenderer.render(entity, extension, poseStack, buffers, packedLight, overlay, partialTick);
    }
}
