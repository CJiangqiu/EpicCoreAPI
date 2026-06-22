package net.eca.util.shader_generator;

import java.util.List;

public record ShaderModuleDefinition(
    String id,
    String displayName,
    Category category,
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
        if (category == null) {
            throw new IllegalArgumentException("Shader module category must not be null");
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

    public enum Category {
        BASIC("gui.eca.shader_generator.effects.category.basic"),
        STARRY_SKY("gui.eca.shader_generator.effects.category.starry_sky"),
        MAGIC("gui.eca.shader_generator.effects.category.magic"),
        /* IMAGE 不在效果下拉中作为分类按钮展示，image_element 作为直接条目出现 */
        IMAGE("gui.eca.shader_generator.effects.category.image");

        private final String translationKey;

        Category(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
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
