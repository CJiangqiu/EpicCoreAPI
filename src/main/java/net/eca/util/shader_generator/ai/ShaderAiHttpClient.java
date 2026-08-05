package net.eca.util.shader_generator.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ShaderAiHttpClient {

    private static final int PREVIEW_REQUEST_TIMEOUT_SECONDS = 30;

    private final HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public ShaderAiResponse complete(
        ShaderAiSettings.Profile profile,
        List<ShaderAiExchange> exchanges,
        List<ShaderAiToolDefinition> tools,
        boolean storeConversation
    ) throws IOException, InterruptedException {
        if (profile == null || !profile.isUsable()) {
            throw new IOException("The active AI profile is incomplete");
        }
        JsonObject requestBody = requestBody(profile, exchanges, tools, storeConversation);
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint(profile)))
            .timeout(Duration.ofSeconds(requestTimeoutSeconds(profile, exchanges)))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                requestBody.toString(), StandardCharsets.UTF_8
            ));
        applyAuthentication(request, profile);
        profile.customHeaders().forEach(request::setHeader);
        HttpResponse<String> response = client.send(
            request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body();
            if (body.length() > 2000) body = body.substring(0, 2000);
            throw new IOException("AI API returned HTTP " + response.statusCode() + ": " + body);
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        return parseResponse(profile.protocol(), root);
    }

    static int requestTimeoutSeconds(
        ShaderAiSettings.Profile profile,
        List<ShaderAiExchange> exchanges
    ) {
        boolean containsPreview = exchanges.stream()
            .anyMatch(exchange -> !exchange.images().isEmpty());
        return containsPreview
            ? Math.min(profile.timeoutSeconds(), PREVIEW_REQUEST_TIMEOUT_SECONDS)
            : profile.timeoutSeconds();
    }

    static JsonObject requestBody(
        ShaderAiSettings.Profile profile,
        List<ShaderAiExchange> exchanges,
        List<ShaderAiToolDefinition> tools,
        boolean storeConversation
    ) {
        return switch (profile.protocol()) {
            case OPENAI_CHAT -> openAiChatRequest(profile, exchanges, tools);
            case OPENAI_RESPONSES -> openAiResponsesRequest(
                profile, exchanges, tools, storeConversation
            );
            case ANTHROPIC_MESSAGES -> anthropicRequest(profile, exchanges, tools);
        };
    }

    static ShaderAiResponse parseResponse(ShaderAiProtocol protocol, JsonObject root)
        throws IOException {
        return switch (protocol) {
            case OPENAI_CHAT -> parseOpenAiChat(root);
            case OPENAI_RESPONSES -> parseOpenAiResponses(root);
            case ANTHROPIC_MESSAGES -> parseAnthropic(root);
        };
    }

    private static JsonObject openAiChatRequest(
        ShaderAiSettings.Profile profile,
        List<ShaderAiExchange> exchanges,
        List<ShaderAiToolDefinition> tools
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("model", profile.model());
        root.add("messages", openAiChatMessages(exchanges));
        if (!tools.isEmpty()) root.add("tools", openAiChatTools(tools));
        return root;
    }

    private static JsonArray openAiChatMessages(List<ShaderAiExchange> exchanges) {
        JsonArray messages = new JsonArray();
        int index = 0;
        while (index < exchanges.size()) {
            ShaderAiExchange exchange = exchanges.get(index);
            if (exchange.kind() == ShaderAiExchange.Kind.TOOL_CALL) {
                JsonObject message = new JsonObject();
                message.addProperty("role", "assistant");
                JsonArray calls = new JsonArray();
                while (index < exchanges.size()
                        && exchanges.get(index).kind() == ShaderAiExchange.Kind.TOOL_CALL) {
                    ShaderAiExchange call = exchanges.get(index++);
                    JsonObject toolCall = new JsonObject();
                    toolCall.addProperty("id", call.id());
                    toolCall.addProperty("type", "function");
                    JsonObject function = new JsonObject();
                    function.addProperty("name", call.name());
                    function.addProperty("arguments", call.input().toString());
                    toolCall.add("function", function);
                    calls.add(toolCall);
                }
                message.add("tool_calls", calls);
                messages.add(message);
                continue;
            }
            JsonObject message = new JsonObject();
            switch (exchange.kind()) {
                case SYSTEM_TEXT -> {
                    message.addProperty("role", "system");
                    message.addProperty("content", exchange.text());
                }
                case USER_TEXT -> {
                    message.addProperty("role", "user");
                    addOpenAiChatUserContent(message, exchange);
                }
                case ASSISTANT_TEXT -> {
                    message.addProperty("role", "assistant");
                    message.addProperty("content", exchange.text());
                }
                case TOOL_RESULT -> {
                    message.addProperty("role", "tool");
                    message.addProperty("tool_call_id", exchange.id());
                    message.addProperty("content", exchange.text());
                }
                default -> throw new IllegalStateException("Unexpected exchange kind");
            }
            messages.add(message);
            index++;
        }
        return messages;
    }

    private static JsonArray openAiChatTools(List<ShaderAiToolDefinition> tools) {
        JsonArray output = new JsonArray();
        for (ShaderAiToolDefinition tool : tools) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name());
            function.addProperty("description", tool.description());
            function.add("parameters", tool.inputSchema());
            wrapper.add("function", function);
            output.add(wrapper);
        }
        return output;
    }

    private static JsonObject openAiResponsesRequest(
        ShaderAiSettings.Profile profile,
        List<ShaderAiExchange> exchanges,
        List<ShaderAiToolDefinition> tools,
        boolean storeConversation
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("model", profile.model());
        root.addProperty("store", storeConversation);
        JsonArray input = new JsonArray();
        for (ShaderAiExchange exchange : exchanges) {
            JsonObject item = new JsonObject();
            switch (exchange.kind()) {
                case SYSTEM_TEXT, USER_TEXT, ASSISTANT_TEXT -> {
                    item.addProperty("role", switch (exchange.kind()) {
                        case SYSTEM_TEXT -> "developer";
                        case USER_TEXT -> "user";
                        case ASSISTANT_TEXT -> "assistant";
                        default -> throw new IllegalStateException("Unexpected message kind");
                    });
                    if (exchange.kind() == ShaderAiExchange.Kind.USER_TEXT
                            && !exchange.images().isEmpty()) {
                        item.add("content", openAiResponsesUserContent(exchange));
                    } else {
                        item.addProperty("content", exchange.text());
                    }
                }
                case TOOL_CALL -> {
                    item.addProperty("type", "function_call");
                    item.addProperty("call_id", exchange.id());
                    item.addProperty("name", exchange.name());
                    item.addProperty("arguments", exchange.input().toString());
                }
                case TOOL_RESULT -> {
                    item.addProperty("type", "function_call_output");
                    item.addProperty("call_id", exchange.id());
                    item.addProperty("output", exchange.text());
                }
            }
            input.add(item);
        }
        root.add("input", input);
        if (!tools.isEmpty()) {
            JsonArray toolArray = new JsonArray();
            for (ShaderAiToolDefinition tool : tools) {
                JsonObject object = new JsonObject();
                object.addProperty("type", "function");
                object.addProperty("name", tool.name());
                object.addProperty("description", tool.description());
                object.add("parameters", tool.inputSchema());
                toolArray.add(object);
            }
            root.add("tools", toolArray);
        }
        return root;
    }

    private static JsonObject anthropicRequest(
        ShaderAiSettings.Profile profile,
        List<ShaderAiExchange> exchanges,
        List<ShaderAiToolDefinition> tools
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("model", profile.model());
        root.addProperty("max_tokens", 8192);
        JsonArray messages = new JsonArray();
        for (ShaderAiExchange exchange : exchanges) {
            if (exchange.kind() == ShaderAiExchange.Kind.SYSTEM_TEXT) {
                root.addProperty("system", exchange.text());
                continue;
            }
            String role = switch (exchange.kind()) {
                case USER_TEXT, TOOL_RESULT -> "user";
                case ASSISTANT_TEXT, TOOL_CALL -> "assistant";
                case SYSTEM_TEXT -> throw new IllegalStateException("System exchange handled above");
            };
            JsonObject block = new JsonObject();
            switch (exchange.kind()) {
                case SYSTEM_TEXT -> throw new IllegalStateException("System exchange handled above");
                case USER_TEXT, ASSISTANT_TEXT -> {
                    block.addProperty("type", "text");
                    block.addProperty("text", exchange.text());
                }
                case TOOL_CALL -> {
                    block.addProperty("type", "tool_use");
                    block.addProperty("id", exchange.id());
                    block.addProperty("name", exchange.name());
                    block.add("input", exchange.input());
                }
                case TOOL_RESULT -> {
                    block.addProperty("type", "tool_result");
                    block.addProperty("tool_use_id", exchange.id());
                    block.addProperty("content", exchange.text());
                }
            }
            appendAnthropicBlock(messages, role, block);
            if (exchange.kind() == ShaderAiExchange.Kind.USER_TEXT) {
                for (String image : exchange.images()) {
                    appendAnthropicBlock(messages, role, anthropicImageBlock(image));
                }
            }
        }
        root.add("messages", messages);
        if (!tools.isEmpty()) {
            JsonArray toolArray = new JsonArray();
            for (ShaderAiToolDefinition tool : tools) {
                JsonObject object = new JsonObject();
                object.addProperty("name", tool.name());
                object.addProperty("description", tool.description());
                object.add("input_schema", tool.inputSchema());
                toolArray.add(object);
            }
            root.add("tools", toolArray);
        }
        return root;
    }

    private static void appendAnthropicBlock(JsonArray messages, String role, JsonObject block) {
        if (!messages.isEmpty()) {
            JsonObject previous = messages.get(messages.size() - 1).getAsJsonObject();
            if (role.equals(previous.get("role").getAsString())) {
                previous.getAsJsonArray("content").add(block);
                return;
            }
        }
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        JsonArray content = new JsonArray();
        content.add(block);
        message.add("content", content);
        messages.add(message);
    }

    private static void addOpenAiChatUserContent(
        JsonObject message,
        ShaderAiExchange exchange
    ) {
        if (exchange.images().isEmpty()) {
            message.addProperty("content", exchange.text());
            return;
        }
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", exchange.text());
        content.add(text);
        for (String image : exchange.images()) {
            JsonObject imageBlock = new JsonObject();
            imageBlock.addProperty("type", "image_url");
            JsonObject imageUrl = new JsonObject();
            imageUrl.addProperty("url", "data:image/png;base64," + image);
            imageBlock.add("image_url", imageUrl);
            content.add(imageBlock);
        }
        message.add("content", content);
    }

    private static JsonArray openAiResponsesUserContent(ShaderAiExchange exchange) {
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "input_text");
        text.addProperty("text", exchange.text());
        content.add(text);
        for (String image : exchange.images()) {
            JsonObject imageBlock = new JsonObject();
            imageBlock.addProperty("type", "input_image");
            imageBlock.addProperty("image_url", "data:image/png;base64," + image);
            content.add(imageBlock);
        }
        return content;
    }

    private static JsonObject anthropicImageBlock(String image) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "image");
        JsonObject source = new JsonObject();
        source.addProperty("type", "base64");
        source.addProperty("media_type", "image/png");
        source.addProperty("data", image);
        block.add("source", source);
        return block;
    }

    private static ShaderAiResponse parseOpenAiChat(JsonObject root) throws IOException {
        if (!root.has("choices") || root.getAsJsonArray("choices").isEmpty()) {
            throw new IOException("OpenAI-compatible response has no choices");
        }
        JsonObject message = root.getAsJsonArray("choices").get(0)
            .getAsJsonObject().getAsJsonObject("message");
        String text = nullableText(message.get("content"));
        List<ShaderAiResponse.ToolCall> calls = new ArrayList<>();
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (JsonElement element : message.getAsJsonArray("tool_calls")) {
                JsonObject call = element.getAsJsonObject();
                JsonObject function = call.getAsJsonObject("function");
                calls.add(new ShaderAiResponse.ToolCall(
                    call.get("id").getAsString(),
                    function.get("name").getAsString(),
                    parseArguments(function.get("arguments").getAsString())
                ));
            }
        }
        return new ShaderAiResponse(text, calls);
    }

    private static ShaderAiResponse parseOpenAiResponses(JsonObject root) throws IOException {
        if (!root.has("output") || !root.get("output").isJsonArray()) {
            throw new IOException("Responses API result has no output items");
        }
        StringBuilder text = new StringBuilder();
        List<ShaderAiResponse.ToolCall> calls = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("output")) {
            JsonObject item = element.getAsJsonObject();
            String type = item.has("type") ? item.get("type").getAsString() : "";
            if ("function_call".equals(type)) {
                calls.add(new ShaderAiResponse.ToolCall(
                    string(item, "call_id"),
                    string(item, "name"),
                    parseArguments(string(item, "arguments"))
                ));
            } else if ("message".equals(type) && item.has("content")) {
                for (JsonElement content : item.getAsJsonArray("content")) {
                    JsonObject block = content.getAsJsonObject();
                    if (block.has("text")) appendText(text, block.get("text").getAsString());
                }
            }
        }
        return new ShaderAiResponse(text.toString(), calls);
    }

    private static ShaderAiResponse parseAnthropic(JsonObject root) throws IOException {
        if (!root.has("content") || !root.get("content").isJsonArray()) {
            throw new IOException("Anthropic response has no content blocks");
        }
        StringBuilder text = new StringBuilder();
        List<ShaderAiResponse.ToolCall> calls = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("content")) {
            JsonObject block = element.getAsJsonObject();
            String type = string(block, "type");
            if ("text".equals(type)) {
                appendText(text, string(block, "text"));
            } else if ("tool_use".equals(type)) {
                calls.add(new ShaderAiResponse.ToolCall(
                    string(block, "id"),
                    string(block, "name"),
                    block.has("input") && block.get("input").isJsonObject()
                        ? block.getAsJsonObject("input") : new JsonObject()
                ));
            }
        }
        return new ShaderAiResponse(text.toString(), calls);
    }

    private static JsonObject parseArguments(String source) throws IOException {
        try {
            if (source == null || source.isBlank()) return new JsonObject();
            return JsonParser.parseString(source).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("AI returned invalid tool arguments", exception);
        }
    }

    private static String endpoint(ShaderAiSettings.Profile profile) {
        String base = profile.baseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String suffix = switch (profile.protocol()) {
            case OPENAI_CHAT -> "/chat/completions";
            case OPENAI_RESPONSES -> "/responses";
            case ANTHROPIC_MESSAGES -> "/messages";
        };
        return base.toLowerCase(Locale.ROOT).endsWith(suffix) ? base : base + suffix;
    }

    private static void applyAuthentication(
        HttpRequest.Builder request,
        ShaderAiSettings.Profile profile
    ) {
        if (profile.protocol() == ShaderAiProtocol.ANTHROPIC_MESSAGES) {
            request.setHeader("x-api-key", profile.resolvedApiKey());
            request.setHeader("anthropic-version", "2023-06-01");
        } else {
            request.setHeader("Authorization", "Bearer " + profile.resolvedApiKey());
        }
    }

    private static String nullableText(JsonElement value) {
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static String string(JsonObject source, String key) {
        return source.has(key) && !source.get(key).isJsonNull()
            ? source.get(key).getAsString() : "";
    }

    private static void appendText(StringBuilder output, String value) {
        if (value == null || value.isBlank()) return;
        if (!output.isEmpty()) output.append('\n');
        output.append(value);
    }
}
