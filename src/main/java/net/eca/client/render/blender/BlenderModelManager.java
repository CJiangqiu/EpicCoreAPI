package net.eca.client.render.blender;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.eca.util.EcaLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
@SuppressWarnings("removal")
public final class BlenderModelManager extends SimplePreparableReloadListener<Map<ResourceLocation, BlenderModelAsset>> {
    public static final BlenderModelManager INSTANCE = new BlenderModelManager();
    private static final String ROOT = "eca/blender";

    private volatile Map<ResourceLocation, BlenderModelAsset> models = Map.of();
    private final Set<ResourceLocation> dynamicTextures = new HashSet<>();

    private BlenderModelManager() {
    }

    BlenderModelAsset get(ResourceLocation id) {
        return id == null ? null : models.get(id);
    }

    @Override
    protected Map<ResourceLocation, BlenderModelAsset> prepare(ResourceManager resourceManager,
                                                               ProfilerFiller profiler) {
        Map<ResourceLocation, BlenderModelAsset> loaded = new HashMap<>();
        Map<ResourceLocation, Resource> definitions = resourceManager.listResources(ROOT,
            location -> location.getPath().endsWith("/definition.json"));
        for (Map.Entry<ResourceLocation, Resource> entry : definitions.entrySet()) {
            ResourceLocation definitionLocation = entry.getKey();
            try {
                String path = definitionLocation.getPath();
                int start = ROOT.length() + 1;
                int end = path.length() - "/definition.json".length();
                if (start >= end) {
                    throw new IOException("Definition has no model id");
                }
                ResourceLocation modelId = new ResourceLocation(definitionLocation.getNamespace(),
                    path.substring(start, end));
                JsonObject json;
                try (InputStream input = entry.getValue().open();
                     InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    json = JsonParser.parseReader(reader).getAsJsonObject();
                }
                BlenderModelDefinition definition = BlenderModelDefinition.parse(json);
                String folder = path.substring(0, path.length() - "definition.json".length());
                ResourceLocation modelLocation = new ResourceLocation(definitionLocation.getNamespace(),
                    folder + definition.modelFile());
                BlenderModelAsset asset = GltfModelLoader.load(resourceManager, modelLocation, modelId, definition);
                loaded.put(modelId, asset);
            } catch (Exception exception) {
                EcaLogger.error("Failed to load Blender model definition " + definitionLocation, exception);
            }
        }
        return Map.copyOf(loaded);
    }

    @Override
    protected void apply(Map<ResourceLocation, BlenderModelAsset> prepared, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        var textureManager = Minecraft.getInstance().getTextureManager();
        dynamicTextures.forEach(textureManager::release);
        dynamicTextures.clear();

        Map<ResourceLocation, BlenderModelAsset> accepted = new HashMap<>();
        for (Map.Entry<ResourceLocation, BlenderModelAsset> entry : prepared.entrySet()) {
            boolean valid = true;
            for (BlenderModelAsset.TextureData texture : entry.getValue().textures) {
                try (ByteArrayInputStream input = new ByteArrayInputStream(texture.bytes())) {
                    NativeImage image = NativeImage.read(input);
                    textureManager.register(texture.location(), new DynamicTexture(image));
                    dynamicTextures.add(texture.location());
                } catch (Exception exception) {
                    EcaLogger.error("Failed to create texture for Blender model " + entry.getKey(), exception);
                    valid = false;
                    break;
                }
            }
            if (valid) {
                accepted.put(entry.getKey(), entry.getValue());
            }
        }
        models = Map.copyOf(accepted);
        EcaLogger.info("Loaded {} Blender model resource(s)", models.size());
    }
}
