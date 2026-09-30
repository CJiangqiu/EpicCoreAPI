package net.eca.blender.model;

import net.eca.blender.animation.BlenderNodeClock;
import net.eca.blender.animation.BlenderPlaybackState;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/** Per-draw inputs; the renderer does not retain an entity or query extension registries. */
public record BlenderRenderRequest(ResourceLocation modelId, String animation,
                                   BlenderPlaybackState playback, float entitySeconds, long gameTime,
                                   float partialTick, float animationSpeed, BlenderNodeClock nodeClock,
                                   Map<String, Float> nodeParameters, float scale,
                                   float offsetX, float offsetY, float offsetZ) {
    public BlenderRenderRequest {
        nodeParameters = Map.copyOf(nodeParameters);
    }
}
