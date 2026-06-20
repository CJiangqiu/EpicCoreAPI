package net.eca.util.shader_generator;

import java.util.List;

public enum ShaderTargetProfile {
    BLOCK("block", List.of("Position", "Color", "UV0", "UV2", "Normal")),
    NEW_ENTITY("entity", List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal"));

    private final String resourceSuffix;
    private final List<String> attributes;

    ShaderTargetProfile(String resourceSuffix, List<String> attributes) {
        this.resourceSuffix = resourceSuffix;
        this.attributes = attributes;
    }

    public String resourceSuffix() {
        return resourceSuffix;
    }

    public List<String> attributes() {
        return attributes;
    }
}
