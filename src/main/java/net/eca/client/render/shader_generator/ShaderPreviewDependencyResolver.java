package net.eca.client.render.shader_generator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.eca.util.shader_generator.ShaderPreviewBindings;
import net.eca.util.shader_generator.ShaderProjectCodec;
import net.eca.util.shader_generator.ShaderProjectCodec.ProjectRef;
import net.eca.util.shader_generator.ShaderSourceFile;
import net.eca.util.shader_generator.ShaderSourceWorkspace;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ShaderPreviewDependencyResolver {

    public static GeneratedShaderPreview.Dependencies resolve(
        ProjectRef reference,
        ShaderSourceWorkspace workspace
    ) {
        Map<ResourceLocation, Path> resources = new LinkedHashMap<>();
        workspace.previewBindings().resources().forEach((resourceId, projectPath) -> {
            Path path = ShaderProjectCodec.resolveProjectAsset(reference, projectPath);
            if (path == null || !Files.isRegularFile(path)) return;
            ResourceLocation location = ResourceLocation.tryParse(resourceId);
            if (location != null) resources.put(location, path);
        });

        List<GeneratedShaderPreview.ResolvedAtlasBinding> atlases = new ArrayList<>();
        for (ShaderPreviewBindings.AtlasBinding binding : workspace.previewBindings().atlases()) {
            List<Path> sprites = binding.spritePaths().stream()
                .map(path -> ShaderProjectCodec.resolveProjectAsset(reference, path))
                .filter(path -> path != null && Files.isRegularFile(path))
                .toList();
            if (sprites.size() == binding.spritePaths().size()) {
                atlases.add(new GeneratedShaderPreview.ResolvedAtlasBinding(
                    binding.samplerName(), binding.uniformName(), sprites
                ));
            }
        }
        return new GeneratedShaderPreview.Dependencies(
            resources, samplerNames(workspace), atlases
        );
    }

    private static Set<String> samplerNames(ShaderSourceWorkspace workspace) {
        Set<String> names = new LinkedHashSet<>();
        collectSamplerNames(workspace.source(ShaderSourceFile.BLOCK_JSON), names);
        collectSamplerNames(workspace.source(ShaderSourceFile.ENTITY_JSON), names);
        return names;
    }

    private static void collectSamplerNames(String source, Set<String> output) {
        try {
            JsonObject root = JsonParser.parseString(source).getAsJsonObject();
            if (!root.has("samplers") || !root.get("samplers").isJsonArray()) return;
            for (var element : root.getAsJsonArray("samplers")) {
                if (element.isJsonObject() && element.getAsJsonObject().has("name")) {
                    output.add(element.getAsJsonObject().get("name").getAsString());
                }
            }
        } catch (RuntimeException ignored) {
            // 编译器会报告 JSON 语法错误，此处只负责尽力收集预览绑定。
        }
    }

    private ShaderPreviewDependencyResolver() {}
}
