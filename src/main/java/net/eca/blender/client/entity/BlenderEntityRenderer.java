package net.eca.blender.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.eca.blender.animation.BlenderPlaybackState;
import net.eca.blender.client.animation.BlenderAnimationClientState;
import net.eca.blender.client.model.BlenderModelAsset;
import net.eca.blender.client.render.BlenderModelRenderer;
import net.eca.blender.client.resource.BlenderModelManager;
import net.eca.blender.entity.BlenderEntityBinding;
import net.eca.blender.model.BlenderRenderRequest;
import net.eca.util.EcaLogger;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public final class BlenderEntityRenderer {
    private static final Set<Class<?>> FAILURES = ConcurrentHashMap.newKeySet();

    private BlenderEntityRenderer() { }

    public static boolean render(LivingEntity entity, BlenderEntityBinding binding, PoseStack poseStack,
                                 MultiBufferSource buffers, int packedLight, int overlay, float partialTick) {
        try {
            if (entity == null || binding == null || !binding.enabled() || !binding.shouldRender(entity)) return false;
            BlenderModelAsset asset = BlenderModelManager.INSTANCE.get(binding.modelId());
            if (asset == null) return false;
            BlenderPlaybackState playback = BlenderAnimationClientState.get(entity);
            BlenderRenderRequest request = new BlenderRenderRequest(asset.id, playback == null ? binding.animation(entity) : null,
                playback, (entity.tickCount + partialTick) / 20.0f,
                entity.level().getGameTime(), partialTick, playback == null ? binding.animationSpeed(entity) : 1,
                asset.blendRuntime == null ? null : binding.nodeClock(entity, asset.definition.nodeTimeSource()),
                asset.blendRuntime == null ? Map.of() : binding.nodeParameters(entity),
                binding.scale(entity), binding.offsetX(entity), binding.offsetY(entity), binding.offsetZ(entity));
            return BlenderModelRenderer.render(request, poseStack, buffers, packedLight, overlay);
        } catch (Throwable failure) {
            if (binding != null && FAILURES.add(binding.getClass())) EcaLogger.error("Blender entity binding failed during rendering", failure);
            return false;
        }
    }
}
