package net.eca.util.shader_generator;

import java.util.List;

public record ShaderModuleDefinition(
    String id,
    String displayName,
    List<Parameter> parameters,
    Emitter emitter
) {

    public ShaderModuleDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Shader module id must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Shader module display name must not be blank");
        }
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        if (emitter == null) {
            throw new IllegalArgumentException("Shader module emitter must not be null");
        }
    }

    public ShaderModuleInstance createInstance() {
        return new ShaderModuleInstance(this);
    }

    @FunctionalInterface
    public interface Emitter {
        String emit(ShaderModuleInstance module, int moduleIndex);
    }

    public record Parameter(
        String key,
        String displayName,
        float minimum,
        float maximum,
        float step,
        float defaultValue
    ) {

        public Parameter {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Shader parameter key must not be blank");
            }
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("Shader parameter display name must not be blank");
            }
            if (maximum < minimum) {
                throw new IllegalArgumentException("Shader parameter maximum must be at least its minimum");
            }
            if (step <= 0.0F) {
                throw new IllegalArgumentException("Shader parameter step must be positive");
            }
            if (defaultValue < minimum || defaultValue > maximum) {
                throw new IllegalArgumentException("Shader parameter default value is outside its range");
            }
        }

        public float clamp(float value) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }
}
