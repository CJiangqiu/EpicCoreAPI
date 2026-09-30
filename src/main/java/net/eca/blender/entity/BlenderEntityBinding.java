package net.eca.blender.entity;

import net.eca.blender.animation.BlenderNodeClock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;

/** Describes entity visuals without owning rendering resources or playback state. */
public interface BlenderEntityBinding {
    ResourceLocation modelId();
    default boolean enabled() { return true; }
    default boolean shouldRender(LivingEntity entity) { return entity != null && !entity.isInvisible(); }
    default BlenderRenderPolicy renderPolicy() { return BlenderRenderPolicy.OVERLAY; }
    default String animation(LivingEntity entity) { return null; }
    default float animationSpeed(LivingEntity entity) { return 1; }
    default BlenderNodeClock nodeClock(LivingEntity entity, BlenderNodeClock resourceDefault) { return resourceDefault; }
    default Map<String, Float> nodeParameters(LivingEntity entity) { return Map.of(); }
    default float scale(LivingEntity entity) { return 1; }
    default float offsetX(LivingEntity entity) { return 0; }
    default float offsetY(LivingEntity entity) { return 0; }
    default float offsetZ(LivingEntity entity) { return 0; }
}
