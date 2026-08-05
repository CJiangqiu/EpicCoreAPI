package net.eca.util.shader_generator.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import net.eca.util.EcaLogger;
import net.eca.util.shader_generator.ai.ShaderAiToolContext;
import net.eca.util.shader_generator.ai.ShaderAiToolDefinition;
import net.eca.util.shader_generator.ai.ShaderAiToolRegistry;
import net.eca.util.shader_generator.ai.ShaderAiToolResult;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;

public final class ShaderMcpServer implements AutoCloseable {

    public static final String ENDPOINT_PATH = "/mcp";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String PROTOCOL_HEADER = "MCP-Protocol-Version";
    private static final String LATEST_PROTOCOL_VERSION = "2025-06-18";
    private static final int MAX_REQUEST_BYTES = 8 * 1024 * 1024;
    private static final long SESSION_IDLE_MILLIS = 30L * 60L * 1000L;
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of(
        LATEST_PROTOCOL_VERSION,
        "2025-03-26",
        "2024-11-05"
    );
    private static final String INSTRUCTIONS = """
        Operate the currently open ECA shader project. Inspect the project before editing. Before adding \
        an element or changing its parameters, call get_module_definition and begin with defaults or its \
        recommended ranges; legal extremes can still produce invisible or expensive effects. Prefer \
        incremental source edits or focused visual-tool mutations over full-file replacement. Compile \
        after meaningful changes, inspect diagnostics, and use capture_preview when visual feedback is \
        useful. Destructive tools remove project content and should only be used when the user asks.
        """;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel channel;
    private ShaderAiToolContext toolContext;
    private int port;

    public synchronized void start(int requestedPort, ShaderAiToolContext context) {
        if (isRunning()) return;
        if (requestedPort < 0 || requestedPort > 65_535) {
            throw new IllegalArgumentException("MCP port must be between 0 and 65535");
        }
        if (context == null) throw new IllegalArgumentException("MCP tool context is required");
        toolContext = context;
        bossGroup = new NioEventLoopGroup(
            1,
            (ThreadFactory) runnable -> daemonThread(runnable, "ECA-MCP-Accept")
        );
        workerGroup = new NioEventLoopGroup(
            1,
            (ThreadFactory) runnable -> daemonThread(runnable, "ECA-MCP-Worker")
        );
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socket) {
                        socket.pipeline().addLast(new HttpServerCodec());
                        socket.pipeline().addLast(new HttpObjectAggregator(MAX_REQUEST_BYTES));
                        socket.pipeline().addLast(new RequestHandler());
                    }
                })
                .childOption(ChannelOption.TCP_NODELAY, true);
            ChannelFuture future = bootstrap.bind("127.0.0.1", requestedPort).syncUninterruptibly();
            if (!future.isSuccess()) throw new IllegalStateException(
                "Failed to bind ECA MCP", future.cause()
            );
            channel = future.channel();
            port = ((InetSocketAddress) channel.localAddress()).getPort();
            EcaLogger.info("[ShaderMCP] started endpoint={}", endpoint());
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    public synchronized boolean isRunning() {
        return channel != null && channel.isOpen();
    }

    public synchronized int port() {
        return port;
    }

    public synchronized String endpoint() {
        return "http://127.0.0.1:" + port + ENDPOINT_PATH;
    }

    public List<ShaderMcpSessionInfo> sessions() {
        purgeIdleSessions();
        List<ShaderMcpSessionInfo> result = new ArrayList<>();
        sessions.values().forEach(session -> result.add(session.snapshot()));
        result.sort(Comparator.comparingLong(ShaderMcpSessionInfo::lastActivity).reversed());
        return List.copyOf(result);
    }

    @Override
    public synchronized void close() {
        sessions.clear();
        if (channel != null) {
            channel.close().syncUninterruptibly();
            channel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        toolContext = null;
        port = 0;
    }

    private void purgeIdleSessions() {
        long cutoff = System.currentTimeMillis() - SESSION_IDLE_MILLIS;
        sessions.entrySet().removeIf(entry -> entry.getValue().lastActivity < cutoff);
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private final class RequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
            boolean keepAlive = HttpUtil.isKeepAlive(request);
            try {
                handle(context, request, keepAlive);
            } catch (RuntimeException exception) {
                EcaLogger.error("[ShaderMCP] request failed reason={}", safeMessage(exception));
                sendJson(context, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    rpcError(null, -32603, safeMessage(exception)), null, keepAlive);
            }
        }

        private void handle(
            ChannelHandlerContext context,
            FullHttpRequest request,
            boolean keepAlive
        ) {
            if (!ENDPOINT_PATH.equals(path(request.uri()))) {
                sendEmpty(context, request, HttpResponseStatus.NOT_FOUND, keepAlive);
                return;
            }
            if (!validOrigin(request)) {
                sendEmpty(context, request, HttpResponseStatus.FORBIDDEN, keepAlive);
                return;
            }
            if (request.method().equals(HttpMethod.OPTIONS)) {
                sendEmpty(context, request, HttpResponseStatus.NO_CONTENT, keepAlive);
            } else if (request.method().equals(HttpMethod.DELETE)) {
                deleteSession(context, request, keepAlive);
            } else if (request.method().equals(HttpMethod.GET)) {
                FullHttpResponse response = emptyResponse(HttpResponseStatus.METHOD_NOT_ALLOWED);
                response.headers().set(HttpHeaderNames.ALLOW, "POST, DELETE, OPTIONS");
                send(context, request, response, keepAlive);
            } else if (request.method().equals(HttpMethod.POST)) {
                handlePost(context, request, keepAlive);
            } else {
                sendEmpty(context, request, HttpResponseStatus.METHOD_NOT_ALLOWED, keepAlive);
            }
        }

        private void handlePost(
            ChannelHandlerContext context,
            FullHttpRequest request,
            boolean keepAlive
        ) {
            JsonObject rpc;
            try {
                JsonElement parsed = JsonParser.parseString(
                    request.content().toString(StandardCharsets.UTF_8)
                );
                if (!parsed.isJsonObject()) throw new IllegalArgumentException("Expected object");
                rpc = parsed.getAsJsonObject();
            } catch (RuntimeException exception) {
                sendJson(context, request, HttpResponseStatus.BAD_REQUEST,
                    rpcError(null, -32700, "Invalid JSON-RPC request"), null, keepAlive);
                return;
            }
            JsonElement id = rpc.get("id");
            String method = string(rpc, "method");
            JsonObject params = object(rpc, "params");
            if (!"2.0".equals(string(rpc, "jsonrpc")) || method.isBlank()) {
                sendJson(context, request, HttpResponseStatus.BAD_REQUEST,
                    rpcError(id, -32600, "Invalid JSON-RPC request"), null, keepAlive);
                return;
            }
            if ("initialize".equals(method)) {
                initialize(context, request, id, params, keepAlive);
                return;
            }
            Session session = session(request);
            if (session == null) {
                sendJson(context, request, HttpResponseStatus.NOT_FOUND,
                    rpcError(id, -32001, "Unknown or expired MCP session"), null, keepAlive);
                return;
            }
            session.touch();
            if (id == null || id.isJsonNull()) {
                sendEmpty(context, request, HttpResponseStatus.ACCEPTED, keepAlive);
                return;
            }
            JsonObject result = switch (method) {
                case "ping" -> new JsonObject();
                case "tools/list" -> toolsList();
                case "tools/call" -> callTool(params);
                default -> null;
            };
            if (result == null) {
                sendJson(context, request, HttpResponseStatus.OK,
                    rpcError(id, -32601, "Method not found: " + method), session.id, keepAlive);
            } else {
                sendJson(context, request, HttpResponseStatus.OK,
                    rpcResult(id, result), session.id, keepAlive);
            }
        }

        private void initialize(
            ChannelHandlerContext context,
            FullHttpRequest request,
            JsonElement id,
            JsonObject params,
            boolean keepAlive
        ) {
            if (id == null || id.isJsonNull()) {
                sendJson(context, request, HttpResponseStatus.BAD_REQUEST,
                    rpcError(null, -32600, "initialize requires an id"), null, keepAlive);
                return;
            }
            String requestedVersion = string(params, "protocolVersion");
            String negotiatedVersion = SUPPORTED_PROTOCOL_VERSIONS.contains(requestedVersion)
                ? requestedVersion : LATEST_PROTOCOL_VERSION;
            JsonObject clientInfo = object(params, "clientInfo");
            String sessionId = UUID.randomUUID().toString();
            String remote = String.valueOf(context.channel().remoteAddress());
            Session session = new Session(
                sessionId,
                valueOr(string(clientInfo, "name"), "Unknown MCP client"),
                valueOr(string(clientInfo, "version"), "unknown"),
                remote
            );
            sessions.put(sessionId, session);

            JsonObject capabilities = new JsonObject();
            JsonObject tools = new JsonObject();
            tools.addProperty("listChanged", false);
            capabilities.add("tools", tools);
            JsonObject serverInfo = new JsonObject();
            serverInfo.addProperty("name", "eca-shader");
            serverInfo.addProperty("title", "ECA Shader Editor");
            serverInfo.addProperty("version", "1.0.0");
            JsonObject result = new JsonObject();
            result.addProperty("protocolVersion", negotiatedVersion);
            result.add("capabilities", capabilities);
            result.add("serverInfo", serverInfo);
            result.addProperty("instructions", INSTRUCTIONS);
            sendJson(context, request, HttpResponseStatus.OK,
                rpcResult(id, result), sessionId, keepAlive);
        }

        private JsonObject toolsList() {
            JsonArray output = new JsonArray();
            for (ShaderAiToolDefinition definition : ShaderAiToolRegistry.definitions()) {
                JsonObject tool = new JsonObject();
                tool.addProperty("name", definition.name());
                tool.addProperty("description", definition.description());
                tool.add("inputSchema", definition.inputSchema().deepCopy());
                tool.add("annotations", annotations(definition.name()));
                output.add(tool);
            }
            JsonObject result = new JsonObject();
            result.add("tools", output);
            return result;
        }

        private JsonObject callTool(JsonObject params) {
            String name = string(params, "name");
            JsonObject arguments = object(params, "arguments");
            JsonObject result = new JsonObject();
            if (name.isBlank()) {
                result.add("content", textContent("Missing tool name"));
                result.addProperty("isError", true);
                return result;
            }
            boolean knownTool = ShaderAiToolRegistry.definitions().stream()
                .anyMatch(definition -> definition.name().equals(name));
            if (!knownTool) {
                result.add("content", textContent("Unknown ECA tool: " + name));
                result.addProperty("isError", true);
                return result;
            }
            ShaderAiToolContext currentContext = toolContext;
            if (currentContext == null) {
                result.add("content", textContent("No active ECA shader project"));
                result.addProperty("isError", true);
                return result;
            }
            ShaderAiToolResult toolResult = ShaderAiToolRegistry.execute(
                name, arguments, currentContext
            );
            JsonArray content = textContent(toolResult.text());
            for (String image : toolResult.images()) {
                JsonObject item = new JsonObject();
                item.addProperty("type", "image");
                item.addProperty("data", image);
                item.addProperty("mimeType", "image/png");
                content.add(item);
            }
            result.add("content", content);
            result.addProperty("isError", toolResult.text().startsWith("Tool failed:"));
            return result;
        }

        private void deleteSession(
            ChannelHandlerContext context,
            FullHttpRequest request,
            boolean keepAlive
        ) {
            String sessionId = request.headers().get(SESSION_HEADER);
            if (sessionId == null || sessions.remove(sessionId) == null) {
                sendEmpty(context, request, HttpResponseStatus.NOT_FOUND, keepAlive);
                return;
            }
            sendEmpty(context, request, HttpResponseStatus.NO_CONTENT, keepAlive);
        }

        private Session session(FullHttpRequest request) {
            String sessionId = request.headers().get(SESSION_HEADER);
            return sessionId == null ? null : sessions.get(sessionId);
        }

        private boolean validOrigin(FullHttpRequest request) {
            String origin = request.headers().get(HttpHeaderNames.ORIGIN);
            if (origin == null || origin.isBlank()) return true;
            try {
                String host = URI.create(origin).getHost();
                return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host);
            } catch (RuntimeException exception) {
                return false;
            }
        }
    }

    private static JsonObject annotations(String name) {
        boolean readOnly = name.startsWith("get_")
            || name.startsWith("list_")
            || name.startsWith("read_")
            || name.startsWith("search_")
            || "capture_preview".equals(name);
        boolean destructive = name.startsWith("remove_")
            || name.startsWith("clear_")
            || "reset_to_empty_visual_project".equals(name)
            || "replace_shader_file".equals(name)
            || "export_shader_files".equals(name);
        boolean idempotent = readOnly
            || "compile_preview".equals(name)
            || "save_project".equals(name)
            || "export_shader_files".equals(name);
        JsonObject annotations = new JsonObject();
        annotations.addProperty("readOnlyHint", readOnly);
        annotations.addProperty("destructiveHint", destructive);
        annotations.addProperty("idempotentHint", idempotent);
        annotations.addProperty("openWorldHint", false);
        return annotations;
    }

    private static JsonArray textContent(String text) {
        JsonArray content = new JsonArray();
        if (text == null || text.isEmpty()) return content;
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        content.add(item);
        return content;
    }

    private static JsonObject rpcResult(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? null : id.deepCopy());
        response.add("result", result);
        return response;
    }

    private static JsonObject rpcError(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? null : id.deepCopy());
        response.add("error", error);
        return response;
    }

    private static String path(String uri) {
        int query = uri.indexOf('?');
        return query < 0 ? uri : uri.substring(0, query);
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return "";
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
            ? object.getAsJsonObject(key) : new JsonObject();
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
            ? throwable.getClass().getSimpleName() : message;
    }

    private static FullHttpResponse emptyResponse(HttpResponseStatus status) {
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
    }

    private static void sendEmpty(
        ChannelHandlerContext context,
        FullHttpRequest request,
        HttpResponseStatus status,
        boolean keepAlive
    ) {
        send(context, request, emptyResponse(status), keepAlive);
    }

    private static void sendJson(
        ChannelHandlerContext context,
        FullHttpRequest request,
        HttpResponseStatus status,
        JsonObject json,
        String sessionId,
        boolean keepAlive
    ) {
        ByteBuf body = Unpooled.copiedBuffer(json.toString(), StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, body);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        if (sessionId != null) response.headers().set(SESSION_HEADER, sessionId);
        response.headers().set(PROTOCOL_HEADER, LATEST_PROTOCOL_VERSION);
        send(context, request, response, keepAlive);
    }

    private static void send(
        ChannelHandlerContext context,
        FullHttpRequest request,
        FullHttpResponse response,
        boolean keepAlive
    ) {
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        if (keepAlive) response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        ChannelFuture future = context.writeAndFlush(response);
        if (!keepAlive) future.addListener(ChannelFutureListener.CLOSE);
    }

    private static final class Session {

        private final String id;
        private final String clientName;
        private final String clientVersion;
        private final String remoteAddress;
        private final long connectedAt = System.currentTimeMillis();
        private volatile long lastActivity = connectedAt;

        private Session(
            String id,
            String clientName,
            String clientVersion,
            String remoteAddress
        ) {
            this.id = id;
            this.clientName = clientName;
            this.clientVersion = clientVersion;
            this.remoteAddress = remoteAddress;
        }

        private void touch() {
            lastActivity = System.currentTimeMillis();
        }

        private ShaderMcpSessionInfo snapshot() {
            return new ShaderMcpSessionInfo(
                id, clientName, clientVersion, remoteAddress, connectedAt, lastActivity
            );
        }
    }
}
