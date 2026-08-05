package net.eca.util.shader_generator.ai;

import com.google.gson.JsonObject;

public record ShaderAiToolDefinition(
    String name,
    String description,
    JsonObject inputSchema
) {

    public ShaderAiToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("AI tool name must not be blank");
        }
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? new JsonObject() : inputSchema.deepCopy();
    }
}
