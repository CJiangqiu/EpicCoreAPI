package net.eca.util.shader_generator.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.eca.util.shader_generator.ShaderModuleDefinition;
import net.eca.util.shader_generator.ShaderModuleRegistry;

import java.util.List;

public final class ShaderAiToolRegistry {

    private static final List<ShaderAiToolDefinition> TOOLS = List.of(
        tool("get_project_summary", "Read layers, elements, parameters and active source state."),
        tool("list_module_definitions", "List reusable ECA visual module IDs and categories."),
        tool(
            "get_module_definition",
            "Read the complete parameter schema for one reusable ECA visual module.",
            stringProperty("definition_id", "Module ID returned by list_module_definitions")
        ),
        tool(
            "read_shader_file",
            "Read one complete current shader source file. Prefer ranged reads for large files.",
            enumProperty("file", "fragment", "block_vertex", "block_json", "entity_vertex", "entity_json")
        ),
        tool(
            "read_shader_file_range",
            "Read an inclusive 1-based line range. Returns content, total lines and a version required by incremental edit tools.",
            enumProperty("file", "fragment", "block_vertex", "block_json", "entity_vertex", "entity_json"),
            integerProperty("start_line", "First 1-based line to read"),
            integerProperty("end_line", "Last 1-based line to read, at most 400 lines per call")
        ),
        tool(
            "search_shader_file",
            "Find exact text in a shader file. Returns matching line excerpts and the current version.",
            enumProperty("file", "fragment", "block_vertex", "block_json", "entity_vertex", "entity_json"),
            stringProperty("query", "Case-sensitive non-empty text to find")
        ),
        tool(
            "replace_shader_file",
            "Replace one complete shader source file. Reserve this for new files or intentional full rewrites.",
            enumProperty("file", "fragment", "block_vertex", "block_json", "entity_vertex", "entity_json"),
            stringProperty("content", "Complete replacement source")
        ),
        tool(
            "replace_shader_range",
            "Replace an inclusive 1-based line range without rewriting the rest of the file.",
            enumProperty("file", "fragment", "block_vertex", "block_json", "entity_vertex", "entity_json"),
            integerProperty("start_line", "First 1-based line to replace"),
            integerProperty("end_line", "Last 1-based line to replace"),
            stringProperty("expected_version", "Version returned by the latest range read or search"),
            stringProperty("content", "Replacement text for the selected lines")
        ),
        tool(
            "replace_shader_text",
            "Replace one exact unique source fragment without rewriting the rest of the file.",
            enumProperty("file", "fragment", "block_vertex", "block_json", "entity_vertex", "entity_json"),
            stringProperty("old_text", "Exact source text that must occur exactly once"),
            stringProperty("new_text", "Replacement source text"),
            stringProperty("expected_version", "Version returned by the latest range read or search")
        ),
        tool(
            "insert_shader_text",
            "Insert source immediately before or after one exact unique anchor.",
            enumProperty("file", "fragment", "block_vertex", "block_json", "entity_vertex", "entity_json"),
            stringProperty("anchor", "Exact source text that must occur exactly once"),
            enumProperty("position", "before", "after"),
            stringProperty("content", "Source text to insert"),
            stringProperty("expected_version", "Version returned by the latest range read or search")
        ),
        tool(
            "add_layer",
            "Add and configure a visual layer. New layers are transparent unless properties explicitly set color_a. Use opaque alpha only for an intentional background.",
            stringProperty("name", "New layer name"),
            layerPropertiesProperty("properties")
        ),
        tool(
            "update_layer",
            "Update visual layer color, alpha, blending, or visibility without editing GLSL.",
            integerProperty("layer_index", "Zero-based layer index"),
            layerPropertiesProperty("properties")
        ),
        tool(
            "add_element",
            "Add a reusable ECA element to a visual layer. Call get_module_definition first and begin with defaults or recommended ranges.",
            integerProperty("layer_index", "Zero-based layer index"),
            stringProperty("definition_id", "ECA module definition ID"),
            objectProperty("parameters", "Optional parameter values")
        ),
        tool(
            "update_element_parameters",
            "Update parameters on an existing visual element. Call get_module_definition first and adjust incrementally within recommended ranges.",
            integerProperty("layer_index", "Zero-based layer index"),
            integerProperty("element_index", "Zero-based element index"),
            objectProperty("parameters", "Parameter values to update")
        ),
        tool(
            "set_layer_background_image",
            "Import a local PNG into the current project and set it as one layer's background. A neighboring .mcmeta file is imported with it.",
            integerProperty("layer_index", "Zero-based layer index"),
            stringProperty("source_path", "Absolute local path to a PNG")
        ),
        tool(
            "set_element_image",
            "Import a local PNG into the current project and set it on an image_element. A neighboring .mcmeta file is imported with it.",
            integerProperty("layer_index", "Zero-based layer index"),
            integerProperty("element_index", "Zero-based element index"),
            stringProperty("source_path", "Absolute local path to a PNG")
        ),
        tool(
            "remove_element",
            "Remove one visual element without editing generated GLSL.",
            integerProperty("layer_index", "Zero-based layer index"),
            integerProperty("element_index", "Zero-based element index")
        ),
        tool(
            "remove_layer",
            "Remove one complete visual layer, including its background and elements.",
            integerProperty("layer_index", "Zero-based layer index")
        ),
        tool(
            "clear_layer_background",
            "Remove the imported background image from one visual layer.",
            integerProperty("layer_index", "Zero-based layer index")
        ),
        tool(
            "clear_element_image",
            "Remove the imported image from one visual element while retaining the element.",
            integerProperty("layer_index", "Zero-based layer index"),
            integerProperty("element_index", "Zero-based element index")
        ),
        tool(
            "reset_to_empty_visual_project",
            "Atomically remove every visual layer and output effect and select pure visual editing. The manual source workspace is preserved but inactive."
        ),
        tool(
            "set_editing_mode",
            "Select visual, source, or hybrid editing. Hybrid composes the independent manual source with namespaced visual layers.",
            enumProperty("mode", "visual", "source", "hybrid")
        ),
        tool("save_project", "Save the current visual project and source workspace to disk."),
        tool(
            "export_shader_files",
            "Export the current project as the standard five core-shader files and return the output directory."
        ),
        tool("compile_preview", "Compile the current shader and return complete diagnostics."),
        tool("capture_preview", "Capture the current ECA preview as a PNG for visual inspection."),
        tool("undo_ai_transaction", "Undo the most recent AI source or visual project mutation."),
        tool("redo_ai_transaction", "Restore the most recently undone AI transaction.")
    );

    public static List<ShaderAiToolDefinition> definitions() {
        return TOOLS;
    }

    public static ShaderAiToolResult execute(
        String name,
        JsonObject input,
        ShaderAiToolContext context
    ) {
        try {
            return switch (name) {
                case "get_project_summary" -> ShaderAiToolResult.text(context.projectSummary());
                case "list_module_definitions" -> ShaderAiToolResult.text(listModules());
                case "get_module_definition" -> ShaderAiToolResult.text(
                    moduleDefinition(requiredString(input, "definition_id"))
                );
                case "read_shader_file" -> ShaderAiToolResult.text(
                    context.readShaderFile(requiredString(input, "file"))
                );
                case "read_shader_file_range" -> ShaderAiToolResult.text(
                    context.readShaderFileRange(
                        requiredString(input, "file"),
                        requiredInt(input, "start_line"),
                        requiredInt(input, "end_line")
                    )
                );
                case "search_shader_file" -> ShaderAiToolResult.text(
                    context.searchShaderFile(
                        requiredString(input, "file"), requiredString(input, "query")
                    )
                );
                case "replace_shader_file" -> ShaderAiToolResult.text(context.replaceShaderFile(
                    requiredString(input, "file"), requiredString(input, "content")
                ));
                case "replace_shader_range" -> ShaderAiToolResult.text(
                    context.replaceShaderRange(
                        requiredString(input, "file"),
                        requiredInt(input, "start_line"),
                        requiredInt(input, "end_line"),
                        requiredString(input, "expected_version"),
                        requiredString(input, "content")
                    )
                );
                case "replace_shader_text" -> ShaderAiToolResult.text(
                    context.replaceShaderText(
                        requiredString(input, "file"),
                        requiredString(input, "old_text"),
                        requiredString(input, "new_text"),
                        requiredString(input, "expected_version")
                    )
                );
                case "insert_shader_text" -> ShaderAiToolResult.text(
                    context.insertShaderText(
                        requiredString(input, "file"),
                        requiredString(input, "anchor"),
                        requiredString(input, "position"),
                        requiredString(input, "content"),
                        requiredString(input, "expected_version")
                    )
                );
                case "add_layer" -> ShaderAiToolResult.text(context.addLayer(
                    requiredString(input, "name"), object(input, "properties")
                ));
                case "update_layer" -> ShaderAiToolResult.text(context.updateLayer(
                    requiredInt(input, "layer_index"), object(input, "properties")
                ));
                case "add_element" -> ShaderAiToolResult.text(context.addElement(
                    requiredInt(input, "layer_index"),
                    requiredString(input, "definition_id"),
                    object(input, "parameters")
                ));
                case "update_element_parameters" -> ShaderAiToolResult.text(context.updateElement(
                    requiredInt(input, "layer_index"),
                    requiredInt(input, "element_index"),
                    object(input, "parameters")
                ));
                case "set_layer_background_image" -> ShaderAiToolResult.text(
                    context.setLayerBackgroundImage(
                        requiredInt(input, "layer_index"),
                        requiredString(input, "source_path")
                    )
                );
                case "set_element_image" -> ShaderAiToolResult.text(context.setElementImage(
                    requiredInt(input, "layer_index"),
                    requiredInt(input, "element_index"),
                    requiredString(input, "source_path")
                ));
                case "remove_element" -> ShaderAiToolResult.text(context.removeElement(
                    requiredInt(input, "layer_index"), requiredInt(input, "element_index")
                ));
                case "remove_layer" -> ShaderAiToolResult.text(context.removeLayer(
                    requiredInt(input, "layer_index")
                ));
                case "clear_layer_background" -> ShaderAiToolResult.text(
                    context.clearLayerBackground(requiredInt(input, "layer_index"))
                );
                case "clear_element_image" -> ShaderAiToolResult.text(context.clearElementImage(
                    requiredInt(input, "layer_index"), requiredInt(input, "element_index")
                ));
                case "reset_to_empty_visual_project" -> ShaderAiToolResult.text(
                    context.resetToEmptyVisualProject()
                );
                case "set_editing_mode" -> ShaderAiToolResult.text(
                    context.setEditingMode(requiredString(input, "mode"))
                );
                case "save_project" -> ShaderAiToolResult.text(context.saveProject());
                case "export_shader_files" -> ShaderAiToolResult.text(
                    context.exportShaderFiles()
                );
                case "compile_preview" -> ShaderAiToolResult.text(context.compilePreview());
                case "capture_preview" -> context.capturePreview();
                case "undo_ai_transaction" -> ShaderAiToolResult.text(context.undoAiTransaction());
                case "redo_ai_transaction" -> ShaderAiToolResult.text(context.redoAiTransaction());
                default -> ShaderAiToolResult.text("Unknown ECA tool: " + name);
            };
        } catch (RuntimeException exception) {
            return ShaderAiToolResult.text("Tool failed: " + exception.getMessage());
        }
    }

    private static String listModules() {
        JsonArray modules = new JsonArray();
        for (ShaderModuleDefinition definition : ShaderModuleRegistry.all()) {
            JsonObject module = new JsonObject();
            module.addProperty("id", definition.id());
            module.addProperty("category", definition.category().name().toLowerCase());
            modules.add(module);
        }
        return modules.toString();
    }

    private static String moduleDefinition(String id) {
        ShaderModuleDefinition definition = ShaderModuleRegistry.get(id);
        if (definition == null) return "Unknown module definition: " + id;
        JsonObject output = new JsonObject();
        output.addProperty("id", definition.id());
        output.addProperty("category", definition.category().name().toLowerCase());
        output.addProperty(
            "description",
            ShaderAiModuleMetadata.moduleDescription(definition.id())
        );
        JsonArray parameters = new JsonArray();
        for (ShaderModuleDefinition.Parameter parameter : definition.parameters()) {
            JsonObject value = new JsonObject();
            value.addProperty("key", parameter.key());
            value.addProperty("minimum", parameter.minimum());
            value.addProperty("maximum", parameter.maximum());
            value.addProperty("step", parameter.step());
            value.addProperty("default", parameter.defaultValue());
            ShaderAiModuleMetadata.Guidance guidance =
                ShaderAiModuleMetadata.parameterGuidance(definition.id(), parameter);
            value.addProperty("description", guidance.description());
            value.addProperty("recommended_minimum", guidance.recommendedMinimum());
            value.addProperty("recommended_maximum", guidance.recommendedMaximum());
            parameters.add(value);
        }
        output.add("parameters", parameters);
        return output.toString();
    }

    private static ShaderAiToolDefinition tool(
        String name,
        String description,
        JsonObject... properties
    ) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject propertyMap = new JsonObject();
        JsonArray required = new JsonArray();
        for (JsonObject property : properties) {
            String propertyName = property.remove("property_name").getAsString();
            propertyMap.add(propertyName, property);
            required.add(propertyName);
        }
        schema.add("properties", propertyMap);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return new ShaderAiToolDefinition(name, description, schema);
    }

    private static JsonObject stringProperty(String name, String description) {
        JsonObject property = property(name, "string", description);
        return property;
    }

    private static JsonObject integerProperty(String name, String description) {
        return property(name, "integer", description);
    }

    private static JsonObject objectProperty(String name, String description) {
        JsonObject property = property(name, "object", description);
        property.addProperty("additionalProperties", true);
        return property;
    }

    private static JsonObject layerPropertiesProperty(String name) {
        JsonObject property = property(
            name,
            "object",
            "Any subset of color_r, color_g, color_b, color_a, blend_mode, and visible"
        );
        JsonObject properties = new JsonObject();
        properties.add("color_r", boundedNumberProperty("Red channel", 0.0F, 1.0F));
        properties.add("color_g", boundedNumberProperty("Green channel", 0.0F, 1.0F));
        properties.add("color_b", boundedNumberProperty("Blue channel", 0.0F, 1.0F));
        properties.add("color_a", boundedNumberProperty("Layer base alpha", 0.0F, 1.0F));
        properties.add("blend_mode", enumValueProperty(
            "Layer blending mode", "normal", "add", "multiply", "screen", "overlay"
        ));
        properties.add("visible", typedProperty("boolean", "Whether the layer is visible"));
        property.add("properties", properties);
        property.addProperty("additionalProperties", false);
        return property;
    }

    private static JsonObject boundedNumberProperty(
        String description,
        float minimum,
        float maximum
    ) {
        JsonObject property = typedProperty("number", description);
        property.addProperty("minimum", minimum);
        property.addProperty("maximum", maximum);
        return property;
    }

    private static JsonObject enumValueProperty(String description, String... values) {
        JsonObject property = typedProperty("string", description);
        JsonArray choices = new JsonArray();
        for (String value : values) choices.add(value);
        property.add("enum", choices);
        return property;
    }

    private static JsonObject typedProperty(String type, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", type);
        property.addProperty("description", description);
        return property;
    }

    private static JsonObject enumProperty(String name, String... values) {
        JsonObject property = property(name, "string", "Allowed source file ID");
        JsonArray choices = new JsonArray();
        for (String value : values) choices.add(value);
        property.add("enum", choices);
        return property;
    }

    private static JsonObject property(String name, String type, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("property_name", name);
        property.addProperty("type", type);
        property.addProperty("description", description);
        return property;
    }

    private static String requiredString(JsonObject input, String key) {
        if (!input.has(key) || !input.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing string argument " + key);
        }
        return input.get(key).getAsString();
    }

    private static int requiredInt(JsonObject input, String key) {
        if (!input.has(key) || !input.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing integer argument " + key);
        }
        return input.get(key).getAsInt();
    }

    private static JsonObject object(JsonObject input, String key) {
        return input.has(key) && input.get(key).isJsonObject()
            ? input.getAsJsonObject(key) : new JsonObject();
    }

    private ShaderAiToolRegistry() {}
}
