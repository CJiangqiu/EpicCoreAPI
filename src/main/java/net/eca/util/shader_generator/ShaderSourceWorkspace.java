package net.eca.util.shader_generator;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

public final class ShaderSourceWorkspace {

    private final Map<ShaderSourceFile, String> sources = new EnumMap<>(ShaderSourceFile.class);
    private final ShaderPreviewBindings previewBindings = new ShaderPreviewBindings();
    private boolean visualOverlayEnabled;

    public boolean isInitialized() {
        return sources.keySet().containsAll(EnumSet.allOf(ShaderSourceFile.class));
    }

    public String source(ShaderSourceFile file) {
        return sources.getOrDefault(file, "");
    }

    public void setSource(ShaderSourceFile file, String source) {
        if (file != null) {
            sources.put(file, source == null ? "" : source);
        }
    }

    public boolean visualOverlayEnabled() {
        return visualOverlayEnabled;
    }

    public void setVisualOverlayEnabled(boolean enabled) {
        visualOverlayEnabled = enabled;
    }

    public ShaderPreviewBindings previewBindings() {
        return previewBindings;
    }

    public ShaderSourceWorkspace copy() {
        ShaderSourceWorkspace copy = new ShaderSourceWorkspace();
        copy.sources.putAll(sources);
        copy.visualOverlayEnabled = visualOverlayEnabled;
        copy.previewBindings.copyFrom(previewBindings);
        return copy;
    }

    public void copyFrom(ShaderSourceWorkspace source) {
        sources.clear();
        if (source != null) {
            sources.putAll(source.sources);
            visualOverlayEnabled = source.visualOverlayEnabled;
            previewBindings.copyFrom(source.previewBindings);
        } else {
            visualOverlayEnabled = false;
            previewBindings.clear();
        }
    }
}
