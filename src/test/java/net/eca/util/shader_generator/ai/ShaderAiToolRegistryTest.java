package net.eca.util.shader_generator.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderAiToolRegistryTest {

    @Test
    void exposesVisualRemovalAndModeTools() {
        Set<String> names = ShaderAiToolRegistry.definitions().stream()
            .map(ShaderAiToolDefinition::name)
            .collect(Collectors.toSet());

        assertEquals(28, ShaderAiToolRegistry.definitions().size());
        assertEquals(28, names.size());
        assertTrue(names.contains("remove_element"));
        assertTrue(names.contains("remove_layer"));
        assertTrue(names.contains("clear_layer_background"));
        assertTrue(names.contains("clear_element_image"));
        assertTrue(names.contains("set_layer_background_image"));
        assertTrue(names.contains("set_element_image"));
        assertTrue(names.contains("reset_to_empty_visual_project"));
        assertTrue(names.contains("set_editing_mode"));
        assertTrue(names.contains("update_layer"));
        assertTrue(names.contains("save_project"));
        assertTrue(names.contains("export_shader_files"));
        assertTrue(names.contains("redo_ai_transaction"));
    }

    @Test
    void exposesLayerAppearanceProperties() {
        ShaderAiToolDefinition addLayer = ShaderAiToolRegistry.definitions().stream()
            .filter(definition -> "add_layer".equals(definition.name()))
            .findFirst()
            .orElseThrow();
        JsonObject properties = addLayer.inputSchema()
            .getAsJsonObject("properties")
            .getAsJsonObject("properties")
            .getAsJsonObject("properties");

        assertTrue(properties.has("color_a"));
        assertTrue(properties.has("blend_mode"));
        assertTrue(properties.has("visible"));
    }

    @Test
    void describesEveryModuleParameterWithAValidRecommendedRange() {
        String listed = ShaderAiToolRegistry.execute(
            "list_module_definitions", new JsonObject(), new EmptyContext()
        ).text();
        for (var module : JsonParser.parseString(listed).getAsJsonArray()) {
            String id = module.getAsJsonObject().get("id").getAsString();
            JsonObject input = new JsonObject();
            input.addProperty("definition_id", id);
            JsonObject definition = JsonParser.parseString(
                ShaderAiToolRegistry.execute(
                    "get_module_definition", input, new EmptyContext()
                ).text()
            ).getAsJsonObject();

            assertTrue(definition.has("description"), id);
            for (var item : definition.getAsJsonArray("parameters")) {
                JsonObject parameter = item.getAsJsonObject();
                assertTrue(parameter.has("description"), id + ":" + parameter);
                float minimum = parameter.get("minimum").getAsFloat();
                float maximum = parameter.get("maximum").getAsFloat();
                float recommendedMinimum = parameter.get("recommended_minimum").getAsFloat();
                float recommendedMaximum = parameter.get("recommended_maximum").getAsFloat();
                assertTrue(recommendedMinimum >= minimum, id + ":" + parameter);
                assertTrue(recommendedMaximum <= maximum, id + ":" + parameter);
                assertTrue(recommendedMinimum <= recommendedMaximum, id + ":" + parameter);
            }
        }
    }

    @Test
    void explainsNebulaDensityDirectionAndKeepsItsSafeRangeBelowSparseExtremes() {
        JsonObject input = new JsonObject();
        input.addProperty("definition_id", "nebula_haze");
        JsonObject definition = JsonParser.parseString(
            ShaderAiToolRegistry.execute(
                "get_module_definition", input, new EmptyContext()
            ).text()
        ).getAsJsonObject();
        JsonObject density = definition.getAsJsonArray("parameters").asList().stream()
            .map(element -> element.getAsJsonObject())
            .filter(parameter -> "density".equals(parameter.get("key").getAsString()))
            .findFirst()
            .orElseThrow();

        assertTrue(density.get("description").getAsString().contains("sparser"));
        assertTrue(density.get("recommended_maximum").getAsFloat() < 0.7F);
    }

    @Test
    void explainsThatBlackHoleOcclusionRequiresNormalLayerBlending() {
        JsonObject input = new JsonObject();
        input.addProperty("definition_id", "black_hole");
        JsonObject definition = JsonParser.parseString(
            ShaderAiToolRegistry.execute(
                "get_module_definition", input, new EmptyContext()
            ).text()
        ).getAsJsonObject();

        assertTrue(definition.get("description").getAsString().contains("NORMAL"));
        assertTrue(definition.get("description").getAsString().contains("ADD"));
    }

    private static final class EmptyContext implements ShaderAiToolContext {
        @Override public String projectSummary() { return ""; }
        @Override public String readShaderFile(String file) { return ""; }
        @Override public String readShaderFileRange(String file, int start, int end) { return ""; }
        @Override public String searchShaderFile(String file, String query) { return ""; }
        @Override public String replaceShaderFile(String file, String content) { return ""; }
        @Override public String replaceShaderRange(
            String file, int start, int end, String version, String content
        ) { return ""; }
        @Override public String replaceShaderText(
            String file, String oldText, String newText, String version
        ) { return ""; }
        @Override public String insertShaderText(
            String file, String anchor, String position, String content, String version
        ) { return ""; }
        @Override public String addLayer(String name, JsonObject properties) { return ""; }
        @Override public String updateLayer(int index, JsonObject properties) { return ""; }
        @Override public String addElement(
            int layer, String definition, JsonObject parameters
        ) { return ""; }
        @Override public String updateElement(
            int layer, int element, JsonObject parameters
        ) { return ""; }
        @Override public String setLayerBackgroundImage(int layer, String path) { return ""; }
        @Override public String setElementImage(int layer, int element, String path) { return ""; }
        @Override public String removeElement(int layer, int element) { return ""; }
        @Override public String removeLayer(int layer) { return ""; }
        @Override public String clearLayerBackground(int layer) { return ""; }
        @Override public String clearElementImage(int layer, int element) { return ""; }
        @Override public String resetToEmptyVisualProject() { return ""; }
        @Override public String setEditingMode(String mode) { return ""; }
        @Override public String saveProject() { return ""; }
        @Override public String exportShaderFiles() { return ""; }
        @Override public String compilePreview() { return ""; }
        @Override public ShaderAiToolResult capturePreview() {
            return ShaderAiToolResult.text("");
        }
        @Override public String undoAiTransaction() { return ""; }
        @Override public String redoAiTransaction() { return ""; }
    }
}
