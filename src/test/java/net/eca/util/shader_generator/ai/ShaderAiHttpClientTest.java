package net.eca.util.shader_generator.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderAiHttpClientTest {

    @Test
    void buildsResponsesToolAndVisionInputWithoutCredentials() {
        ShaderAiSettings.Profile profile = profile(ShaderAiProtocol.OPENAI_RESPONSES);
        JsonObject input = new JsonObject();
        input.addProperty("file", "fragment");
        List<ShaderAiExchange> exchanges = List.of(
            ShaderAiExchange.system("system"),
            ShaderAiExchange.user("repair"),
            ShaderAiExchange.toolCall("call_1", "read_shader_file", input),
            ShaderAiExchange.toolResult("call_1", "read_shader_file", "source"),
            ShaderAiExchange.userWithImages("preview", List.of("aW1hZ2U="))
        );

        JsonObject body = ShaderAiHttpClient.requestBody(
            profile, exchanges, ShaderAiToolRegistry.definitions(), false
        );

        assertEquals("test-model", body.get("model").getAsString());
        assertFalse(body.get("store").getAsBoolean());
        assertTrue(body.toString().contains("function_call_output"));
        assertTrue(body.toString().contains("data:image/png;base64,aW1hZ2U="));
        assertFalse(body.toString().contains("test-secret"));
    }

    @Test
    void buildsChatAndAnthropicNativeToolShapes() {
        JsonObject chat = ShaderAiHttpClient.requestBody(
            profile(ShaderAiProtocol.OPENAI_CHAT),
            List.of(ShaderAiExchange.user("hello")),
            ShaderAiToolRegistry.definitions(),
            false
        );
        JsonObject anthropic = ShaderAiHttpClient.requestBody(
            profile(ShaderAiProtocol.ANTHROPIC_MESSAGES),
            List.of(ShaderAiExchange.system("system"), ShaderAiExchange.user("hello")),
            ShaderAiToolRegistry.definitions(),
            false
        );

        assertTrue(chat.getAsJsonArray("tools").get(0).getAsJsonObject().has("function"));
        assertTrue(anthropic.getAsJsonArray("tools").get(0).getAsJsonObject().has("input_schema"));
        assertEquals("system", anthropic.get("system").getAsString());
    }

    @Test
    void capsPreviewRequestsWithoutShorteningTextRequests() {
        ShaderAiSettings.Profile profile = profile(ShaderAiProtocol.OPENAI_RESPONSES, 120);

        assertEquals(120, ShaderAiHttpClient.requestTimeoutSeconds(
            profile, List.of(ShaderAiExchange.user("hello"))
        ));
        assertEquals(30, ShaderAiHttpClient.requestTimeoutSeconds(
            profile,
            List.of(ShaderAiExchange.userWithImages("preview", List.of("aW1hZ2U=")))
        ));
    }

    @Test
    void parsesToolCallsFromAllSupportedResponseFormats() throws IOException {
        JsonObject chat = JsonParser.parseString("""
            {"choices":[{"message":{"content":"ok","tool_calls":[{"id":"c1","function":
            {"name":"compile_preview","arguments":"{}"}}]}}]}
            """).getAsJsonObject();
        JsonObject responses = JsonParser.parseString("""
            {"output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]},
            {"type":"function_call","call_id":"c2","name":"compile_preview","arguments":"{}"}]}
            """).getAsJsonObject();
        JsonObject anthropic = JsonParser.parseString("""
            {"content":[{"type":"text","text":"ok"},{"type":"tool_use","id":"c3",
            "name":"compile_preview","input":{}}]}
            """).getAsJsonObject();

        assertResponse(ShaderAiHttpClient.parseResponse(ShaderAiProtocol.OPENAI_CHAT, chat), "c1");
        assertResponse(
            ShaderAiHttpClient.parseResponse(ShaderAiProtocol.OPENAI_RESPONSES, responses), "c2"
        );
        assertResponse(
            ShaderAiHttpClient.parseResponse(ShaderAiProtocol.ANTHROPIC_MESSAGES, anthropic), "c3"
        );
    }

    private static void assertResponse(ShaderAiResponse response, String callId) {
        assertEquals("ok", response.text());
        assertEquals(1, response.toolCalls().size());
        assertEquals(callId, response.toolCalls().get(0).id());
        assertEquals("compile_preview", response.toolCalls().get(0).name());
    }

    private static ShaderAiSettings.Profile profile(ShaderAiProtocol protocol) {
        return profile(protocol, 30);
    }

    private static ShaderAiSettings.Profile profile(
        ShaderAiProtocol protocol,
        int timeoutSeconds
    ) {
        return new ShaderAiSettings.Profile(
            "test",
            protocol,
            "https://example.invalid/v1",
            "test-secret",
            "",
            "test-model",
            Map.of(),
            timeoutSeconds
        );
    }
}
