package net.eca.util.shader_generator.ai;

import com.google.gson.JsonObject;

import java.util.List;

public record ShaderAiExchange(
    Kind kind,
    String id,
    String name,
    String text,
    JsonObject input,
    List<String> images
) {

    public ShaderAiExchange {
        if (kind == null) throw new IllegalArgumentException("AI exchange kind must not be null");
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        text = text == null ? "" : text;
        input = input == null ? new JsonObject() : input.deepCopy();
        images = images == null ? List.of() : List.copyOf(images);
    }

    public static ShaderAiExchange user(String text) {
        return new ShaderAiExchange(Kind.USER_TEXT, "", "", text, null, List.of());
    }

    public static ShaderAiExchange system(String text) {
        return new ShaderAiExchange(Kind.SYSTEM_TEXT, "", "", text, null, List.of());
    }

    public static ShaderAiExchange userWithImages(String text, List<String> images) {
        return new ShaderAiExchange(Kind.USER_TEXT, "", "", text, null, images);
    }

    public static ShaderAiExchange assistant(String text) {
        return new ShaderAiExchange(Kind.ASSISTANT_TEXT, "", "", text, null, List.of());
    }

    public static ShaderAiExchange toolCall(String id, String name, JsonObject input) {
        return new ShaderAiExchange(Kind.TOOL_CALL, id, name, "", input, List.of());
    }

    public static ShaderAiExchange toolResult(String id, String name, String result) {
        return new ShaderAiExchange(Kind.TOOL_RESULT, id, name, result, null, List.of());
    }

    public enum Kind {
        SYSTEM_TEXT,
        USER_TEXT,
        ASSISTANT_TEXT,
        TOOL_CALL,
        TOOL_RESULT
    }
}
