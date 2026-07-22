package net.eca.util.shader_generator;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.List;
import java.util.regex.Pattern;

public record ShaderProject(
    String namespace,
    String path,
    String fragmentBody,
    Set<Capability> capabilities,
    List<TextureBinding> textures,
    List<ShaderOutputEffectInstance> outputEffects
) {

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH_PATTERN = Pattern.compile("[a-z0-9/._-]+");

    public ShaderProject {
        if (namespace == null || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid shader namespace: " + namespace);
        }
        if (path == null || !PATH_PATTERN.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid shader path: " + path);
        }
        if (fragmentBody == null || fragmentBody.isBlank()) {
            throw new IllegalArgumentException("Shader fragment body must not be blank");
        }
        capabilities = immutableCapabilities(capabilities);
        textures = textures == null ? List.of() : List.copyOf(textures);
        outputEffects = outputEffects == null ? List.of() : List.copyOf(outputEffects);
    }

    public String resourceId() {
        return namespace + ":" + path;
    }

    private static Set<Capability> immutableCapabilities(Set<Capability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
    }

    public enum Capability {
        CAMERA_ORIENTATION,
        COLOR_KEY,
        LOCAL_UV_BOUNDS
    }

    public record TextureBinding(String samplerName, String projectPath) {

        public TextureBinding {
            if (samplerName == null || samplerName.isBlank()) {
                throw new IllegalArgumentException("Texture sampler name must not be blank");
            }
            if (projectPath == null || projectPath.isBlank()) {
                throw new IllegalArgumentException("Texture project path must not be blank");
            }
        }
    }
}
