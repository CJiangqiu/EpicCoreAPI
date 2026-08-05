package net.eca.util.shader_generator.mcp;

public record ShaderMcpSessionInfo(
    String id,
    String clientName,
    String clientVersion,
    String remoteAddress,
    long connectedAt,
    long lastActivity
) {}
