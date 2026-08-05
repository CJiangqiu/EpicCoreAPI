package net.eca.util.shader_generator.ai;

import java.util.List;

public record ShaderAiToolResult(String text, List<String> images) {

    public ShaderAiToolResult {
        text = text == null ? "" : text;
        images = images == null ? List.of() : List.copyOf(images);
    }

    public static ShaderAiToolResult text(String text) {
        return new ShaderAiToolResult(text, List.of());
    }
}
