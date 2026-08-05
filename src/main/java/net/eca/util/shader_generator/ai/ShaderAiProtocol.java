package net.eca.util.shader_generator.ai;

import java.util.Locale;

public enum ShaderAiProtocol {
    OPENAI_CHAT,
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES;

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ShaderAiProtocol fromName(String value) {
        if (value == null) return OPENAI_RESPONSES;
        for (ShaderAiProtocol protocol : values()) {
            if (protocol.serializedName().equalsIgnoreCase(value)) return protocol;
        }
        return OPENAI_RESPONSES;
    }
}
