package net.eca.util.shader_generator;

import java.util.List;

public record ShaderOutputEffectDefinition(
    String id,
    String displayName,
    Stage stage,
    List<ShaderModuleDefinition.Parameter> parameters,
    Emitter emitter
) {

    public ShaderOutputEffectDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Shader output effect id must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Shader output effect display name must not be blank");
        }
        if (stage == null) {
            throw new IllegalArgumentException("Shader output effect stage must not be null");
        }
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        if (emitter == null) {
            throw new IllegalArgumentException("Shader output effect emitter must not be null");
        }
    }

    public ShaderOutputEffectInstance createInstance() {
        return new ShaderOutputEffectInstance(this);
    }

    @FunctionalInterface
    public interface Emitter {
        String emit(ShaderOutputEffectInstance effect, int effectIndex);
    }

    public enum Stage {
        UV,
        RESAMPLE,
        COLOR
    }
}
