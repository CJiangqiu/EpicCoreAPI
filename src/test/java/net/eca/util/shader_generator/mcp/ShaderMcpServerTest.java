package net.eca.util.shader_generator.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.eca.util.shader_generator.ai.ShaderAiToolContext;
import net.eca.util.shader_generator.ai.ShaderAiToolRegistry;
import net.eca.util.shader_generator.ai.ShaderAiToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderMcpServerTest {

    private final ShaderMcpServer server = new ShaderMcpServer();
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void closeServer() {
        server.close();
    }

    @Test
    void initializesListsToolsAndCallsSharedRegistry() throws Exception {
        server.start(0, new FakeContext());
        HttpResponse<String> initialized = post(null, """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2025-06-18",
              "capabilities":{},
              "clientInfo":{"name":"codex-test","version":"1.0"}
            }}
            """);
        assertEquals(200, initialized.statusCode());
        String sessionId = initialized.headers()
            .firstValue("Mcp-Session-Id")
            .orElseThrow();
        JsonObject initializeBody = JsonParser.parseString(initialized.body()).getAsJsonObject();
        assertEquals(
            "eca-shader",
            initializeBody.getAsJsonObject("result")
                .getAsJsonObject("serverInfo")
                .get("name")
                .getAsString()
        );
        assertEquals("codex-test", server.sessions().get(0).clientName());

        HttpResponse<String> listed = post(sessionId,
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        JsonObject listBody = JsonParser.parseString(listed.body()).getAsJsonObject();
        assertEquals(
            ShaderAiToolRegistry.definitions().size(),
            listBody.getAsJsonObject("result").getAsJsonArray("tools").size()
        );

        HttpResponse<String> called = post(sessionId, """
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
              "name":"get_project_summary","arguments":{}
            }}
            """);
        JsonObject callBody = JsonParser.parseString(called.body()).getAsJsonObject();
        JsonObject result = callBody.getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(
            "test-project",
            result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()
        );

        HttpResponse<String> saved = post(sessionId, """
            {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
              "name":"save_project","arguments":{}
            }}
            """);
        JsonObject saveResult = JsonParser.parseString(saved.body()).getAsJsonObject()
            .getAsJsonObject("result");
        assertEquals(
            "saved",
            saveResult.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString()
        );

        HttpResponse<String> image = post(sessionId, """
            {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{
              "name":"set_element_image","arguments":{
                "layer_index":0,"element_index":1,"source_path":"C:/effect.png"
              }
            }}
            """);
        JsonObject imageResult = JsonParser.parseString(image.body()).getAsJsonObject()
            .getAsJsonObject("result");
        assertEquals(
            "C:/effect.png",
            imageResult.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString()
        );
    }

    @Test
    void rejectsNonLocalOrigin() throws Exception {
        server.start(0, new FakeContext());
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.endpoint()))
            .header("Content-Type", "application/json")
            .header("Origin", "https://example.com")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"
            ))
            .build();
        HttpResponse<String> response = client.send(
            request, HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(403, response.statusCode());
        assertTrue(server.sessions().isEmpty());
    }

    private HttpResponse<String> post(String sessionId, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.endpoint()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream");
        if (sessionId != null) request.header("Mcp-Session-Id", sessionId);
        return client.send(
            request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private static final class FakeContext implements ShaderAiToolContext {

        @Override public String projectSummary() { return "test-project"; }
        @Override public String readShaderFile(String file) { return file; }
        @Override public String readShaderFileRange(String file, int start, int end) { return file; }
        @Override public String searchShaderFile(String file, String query) { return query; }
        @Override public String replaceShaderFile(String file, String content) { return content; }
        @Override public String replaceShaderRange(
            String file, int start, int end, String version, String content
        ) { return content; }
        @Override public String replaceShaderText(
            String file, String oldText, String newText, String version
        ) { return newText; }
        @Override public String insertShaderText(
            String file, String anchor, String position, String content, String version
        ) { return content; }
        @Override public String addLayer(String name, JsonObject properties) { return name; }
        @Override public String updateLayer(int index, JsonObject properties) { return "layer"; }
        @Override public String addElement(
            int layer, String definition, JsonObject parameters
        ) { return definition; }
        @Override public String updateElement(
            int layer, int element, JsonObject parameters
        ) { return "element"; }
        @Override public String setLayerBackgroundImage(int layer, String sourcePath) {
            return sourcePath;
        }
        @Override public String setElementImage(int layer, int element, String sourcePath) {
            return sourcePath;
        }
        @Override public String removeElement(int layer, int element) { return "removed"; }
        @Override public String removeLayer(int layer) { return "removed"; }
        @Override public String clearLayerBackground(int layer) { return "cleared"; }
        @Override public String clearElementImage(int layer, int element) { return "cleared"; }
        @Override public String resetToEmptyVisualProject() { return "reset"; }
        @Override public String setEditingMode(String mode) { return mode; }
        @Override public String saveProject() { return "saved"; }
        @Override public String exportShaderFiles() { return "exported"; }
        @Override public String compilePreview() { return "compiled"; }
        @Override public ShaderAiToolResult capturePreview() {
            return new ShaderAiToolResult("preview", List.of("cG5n"));
        }
        @Override public String undoAiTransaction() { return "undone"; }
        @Override public String redoAiTransaction() { return "redone"; }
    }
}
