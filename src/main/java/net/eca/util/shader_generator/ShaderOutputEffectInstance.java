package net.eca.util.shader_generator;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ShaderOutputEffectInstance {

    private final ShaderOutputEffectDefinition definition;
    private final Map<String, Float> values = new LinkedHashMap<>();
    private boolean enabled = true;

    public ShaderOutputEffectInstance(ShaderOutputEffectDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Shader output effect definition must not be null");
        }
        this.definition = definition;
        for (ShaderModuleDefinition.Parameter parameter : definition.parameters()) {
            values.put(parameter.key(), parameter.defaultValue());
        }
    }

    public ShaderOutputEffectDefinition definition() {
        return definition;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public float value(String key) {
        Float value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Unknown shader output effect parameter: " + key);
        }
        return value;
    }

    public void setValue(String key, float value) {
        ShaderModuleDefinition.Parameter parameter = parameter(key);
        values.put(key, parameter.clamp(value));
    }

    public Map<String, Float> values() {
        return Map.copyOf(values);
    }

    public ShaderOutputEffectInstance copy() {
        ShaderOutputEffectInstance copy = new ShaderOutputEffectInstance(definition);
        copy.enabled = enabled;
        copy.values.putAll(values);
        return copy;
    }

    private ShaderModuleDefinition.Parameter parameter(String key) {
        for (ShaderModuleDefinition.Parameter parameter : definition.parameters()) {
            if (parameter.key().equals(key)) {
                return parameter;
            }
        }
        throw new IllegalArgumentException("Unknown shader output effect parameter: " + key);
    }
}
