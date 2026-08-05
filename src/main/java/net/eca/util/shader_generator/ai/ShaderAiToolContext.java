package net.eca.util.shader_generator.ai;

import com.google.gson.JsonObject;

public interface ShaderAiToolContext {

    String projectSummary();

    String readShaderFile(String serializedFile);

    String readShaderFileRange(String serializedFile, int startLine, int endLine);

    String searchShaderFile(String serializedFile, String query);

    String replaceShaderFile(String serializedFile, String content);

    String replaceShaderRange(
        String serializedFile,
        int startLine,
        int endLine,
        String expectedVersion,
        String content
    );

    String replaceShaderText(
        String serializedFile,
        String oldText,
        String newText,
        String expectedVersion
    );

    String insertShaderText(
        String serializedFile,
        String anchor,
        String position,
        String content,
        String expectedVersion
    );

    String addLayer(String name, JsonObject properties);

    String updateLayer(int layerIndex, JsonObject properties);

    String addElement(int layerIndex, String definitionId, JsonObject parameters);

    String updateElement(int layerIndex, int elementIndex, JsonObject parameters);

    String setLayerBackgroundImage(int layerIndex, String sourcePath);

    String setElementImage(int layerIndex, int elementIndex, String sourcePath);

    String removeElement(int layerIndex, int elementIndex);

    String removeLayer(int layerIndex);

    String clearLayerBackground(int layerIndex);

    String clearElementImage(int layerIndex, int elementIndex);

    String resetToEmptyVisualProject();

    String setEditingMode(String mode);

    String saveProject();

    String exportShaderFiles();

    String compilePreview();

    ShaderAiToolResult capturePreview();

    String undoAiTransaction();

    String redoAiTransaction();
}
