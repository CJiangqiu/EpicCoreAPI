package net.eca.util.entity_extension;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public final class GlobalEffectRegistry {

    private static final Map<ResourceLocation, RenderType> SKYBOX_PRESETS = new ConcurrentHashMap<>();
    private static final Map<RenderType, ResourceLocation> SKYBOX_REVERSE = new ConcurrentHashMap<>();

    public static void registerSkyboxPreset(ResourceLocation id, RenderType renderType) {
        if (id == null || renderType == null) {
            return;
        }
        SKYBOX_PRESETS.put(id, renderType);
        SKYBOX_REVERSE.put(renderType, id);
    }

    public static RenderType getSkyboxPreset(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        return SKYBOX_PRESETS.get(id);
    }

    public static ResourceLocation getSkyboxPresetId(RenderType renderType) {
        if (renderType == null) {
            return null;
        }
        return SKYBOX_REVERSE.get(renderType);
    }

    public static Set<ResourceLocation> getAllSkyboxPresetIds() {
        return Collections.unmodifiableSet(SKYBOX_PRESETS.keySet());
    }

    private GlobalEffectRegistry() {}
}
