package net.eca.util.shader_generator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ShaderModuleInstance {

    private final ShaderModuleDefinition definition;
    private final Map<String, Float> values = new LinkedHashMap<>();
    private boolean enabled = true;

    public ShaderModuleInstance(ShaderModuleDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Shader module definition must not be null");
        }
        this.definition = definition;
        for (ShaderModuleDefinition.Parameter parameter : definition.parameters()) {
            values.put(parameter.key(), parameter.defaultValue());
        }
    }

    public ShaderModuleDefinition definition() {
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
            throw new IllegalArgumentException("Unknown shader parameter: " + key);
        }
        return value;
    }

    public void setValue(String key, float value) {
        ShaderModuleDefinition.Parameter parameter = parameter(key);
        values.put(key, parameter.clamp(value));
    }

    public Map<String, Float> values() {
        return Collections.unmodifiableMap(values);
    }

    /* 深拷贝：definition 为不可变 record 共享引用，values 和 enabled 需独立拷贝 */
    public ShaderModuleInstance copy() {
        ShaderModuleInstance copy = new ShaderModuleInstance(definition);
        copy.values.clear();
        copy.values.putAll(this.values);
        copy.enabled = this.enabled;
        return copy;
    }

    private ShaderModuleDefinition.Parameter parameter(String key) {
        for (ShaderModuleDefinition.Parameter parameter : definition.parameters()) {
            if (parameter.key().equals(key)) {
                return parameter;
            }
        }
        throw new IllegalArgumentException("Unknown shader parameter: " + key);
    }
}
