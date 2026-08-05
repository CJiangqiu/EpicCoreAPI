package net.eca.util.shader_generator.mcp;

public record ShaderMcpSettings(int port) {

    public static final int DEFAULT_PORT = 8767;

    public ShaderMcpSettings {
        if (port < 1 || port > 65_535) port = DEFAULT_PORT;
    }

    public static ShaderMcpSettings defaults() {
        return new ShaderMcpSettings(DEFAULT_PORT);
    }
}
