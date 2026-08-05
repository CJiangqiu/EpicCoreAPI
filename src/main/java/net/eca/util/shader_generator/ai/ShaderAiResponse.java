package net.eca.util.shader_generator.ai;

import com.google.gson.JsonObject;

import java.util.List;

public record ShaderAiResponse(String text, List<ToolCall> toolCalls) {

    public ShaderAiResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public record ToolCall(String id, String name, JsonObject input) {

        public ToolCall {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            input = input == null ? new JsonObject() : input.deepCopy();
        }
    }
}
