package net.eca.util.shader_generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ShaderPreviewBindings {

    private final Map<String, String> resources = new LinkedHashMap<>();
    private final List<AtlasBinding> atlases = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public Map<String, String> resources() {
        return Collections.unmodifiableMap(resources);
    }

    public List<AtlasBinding> atlases() {
        return Collections.unmodifiableList(atlases);
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public void putResource(String resourceId, String projectPath) {
        if (resourceId != null && !resourceId.isBlank() && projectPath != null && !projectPath.isBlank()) {
            resources.put(resourceId, projectPath);
        }
    }

    public void addAtlas(AtlasBinding binding) {
        if (binding != null) atlases.add(binding);
    }

    public void addWarning(String warning) {
        if (warning != null && !warning.isBlank() && !warnings.contains(warning)) warnings.add(warning);
    }

    public void clear() {
        resources.clear();
        atlases.clear();
        warnings.clear();
    }

    public ShaderPreviewBindings copy() {
        ShaderPreviewBindings copy = new ShaderPreviewBindings();
        copy.copyFrom(this);
        return copy;
    }

    public void copyFrom(ShaderPreviewBindings source) {
        clear();
        if (source == null) return;
        resources.putAll(source.resources);
        atlases.addAll(source.atlases);
        warnings.addAll(source.warnings);
    }

    public record AtlasBinding(String samplerName, String uniformName, List<String> spritePaths) {

        public AtlasBinding {
            if (samplerName == null || samplerName.isBlank() || uniformName == null
                    || uniformName.isBlank() || spritePaths == null || spritePaths.isEmpty()) {
                throw new IllegalArgumentException("Invalid shader preview atlas binding");
            }
            spritePaths = List.copyOf(spritePaths);
        }
    }
}
