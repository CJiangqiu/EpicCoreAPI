package net.eca.client.gui;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import net.eca.util.shader_generator.ai.ShaderAiToolContext;
import net.eca.util.shader_generator.ai.ShaderAiToolResult;
import net.minecraft.client.Minecraft;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;

final class ShaderProjectToolContext implements ShaderAiToolContext {

    private final ShaderGeneratorScreen projectScreen;
    private final BooleanSupplier available;
    private final Callable<ShaderAiToolResult> previewCapture;

    ShaderProjectToolContext(
        ShaderGeneratorScreen projectScreen,
        BooleanSupplier available,
        Callable<ShaderAiToolResult> previewCapture
    ) {
        this.projectScreen = projectScreen;
        this.available = available;
        this.previewCapture = previewCapture;
    }

    private <T> T call(Callable<T> task) {
        return callOnClient(() -> {
            if (!available.getAsBoolean()) {
                throw new IllegalStateException("ECA shader project is no longer available");
            }
            return task.call();
        });
    }

    @Override
    public String projectSummary() {
        return call(projectScreen::aiProjectSummary);
    }

    @Override
    public String readShaderFile(String serializedFile) {
        return call(() -> projectScreen.aiReadShaderFile(serializedFile));
    }

    @Override
    public String readShaderFileRange(String serializedFile, int startLine, int endLine) {
        return call(() -> projectScreen.aiReadShaderFileRange(
            serializedFile, startLine, endLine
        ));
    }

    @Override
    public String searchShaderFile(String serializedFile, String query) {
        return call(() -> projectScreen.aiSearchShaderFile(serializedFile, query));
    }

    @Override
    public String replaceShaderFile(String serializedFile, String content) {
        return call(() -> projectScreen.aiReplaceShaderFile(serializedFile, content));
    }

    @Override
    public String replaceShaderRange(
        String serializedFile,
        int startLine,
        int endLine,
        String expectedVersion,
        String content
    ) {
        return call(() -> projectScreen.aiReplaceShaderRange(
            serializedFile, startLine, endLine, expectedVersion, content
        ));
    }

    @Override
    public String replaceShaderText(
        String serializedFile,
        String oldText,
        String newText,
        String expectedVersion
    ) {
        return call(() -> projectScreen.aiReplaceShaderText(
            serializedFile, oldText, newText, expectedVersion
        ));
    }

    @Override
    public String insertShaderText(
        String serializedFile,
        String anchor,
        String position,
        String content,
        String expectedVersion
    ) {
        return call(() -> projectScreen.aiInsertShaderText(
            serializedFile, anchor, position, content, expectedVersion
        ));
    }

    @Override
    public String addLayer(String name, JsonObject properties) {
        return call(() -> projectScreen.aiAddLayer(name, properties));
    }

    @Override
    public String updateLayer(int layerIndex, JsonObject properties) {
        return call(() -> projectScreen.aiUpdateLayer(layerIndex, properties));
    }

    @Override
    public String addElement(int layerIndex, String definitionId, JsonObject parameters) {
        return call(() -> projectScreen.aiAddElement(layerIndex, definitionId, parameters));
    }

    @Override
    public String updateElement(int layerIndex, int elementIndex, JsonObject parameters) {
        return call(() -> projectScreen.aiUpdateElement(
            layerIndex, elementIndex, parameters
        ));
    }

    @Override
    public String setLayerBackgroundImage(int layerIndex, String sourcePath) {
        return call(() -> projectScreen.aiSetLayerBackgroundImage(layerIndex, sourcePath));
    }

    @Override
    public String setElementImage(int layerIndex, int elementIndex, String sourcePath) {
        return call(() -> projectScreen.aiSetElementImage(
            layerIndex, elementIndex, sourcePath
        ));
    }

    @Override
    public String removeElement(int layerIndex, int elementIndex) {
        return call(() -> projectScreen.aiRemoveElement(layerIndex, elementIndex));
    }

    @Override
    public String removeLayer(int layerIndex) {
        return call(() -> projectScreen.aiRemoveLayer(layerIndex));
    }

    @Override
    public String clearLayerBackground(int layerIndex) {
        return call(() -> projectScreen.aiClearLayerBackground(layerIndex));
    }

    @Override
    public String clearElementImage(int layerIndex, int elementIndex) {
        return call(() -> projectScreen.aiClearElementImage(layerIndex, elementIndex));
    }

    @Override
    public String resetToEmptyVisualProject() {
        return call(projectScreen::aiResetToEmptyVisualProject);
    }

    @Override
    public String setEditingMode(String mode) {
        return call(() -> projectScreen.aiSetEditingMode(mode));
    }

    @Override
    public String saveProject() {
        return call(projectScreen::aiSaveProject);
    }

    @Override
    public String exportShaderFiles() {
        return call(projectScreen::aiExportShaderFiles);
    }

    @Override
    public String compilePreview() {
        return call(projectScreen::aiCompilePreview);
    }

    @Override
    public ShaderAiToolResult capturePreview() {
        return call(previewCapture);
    }

    @Override
    public String undoAiTransaction() {
        return call(projectScreen::aiUndoTransaction);
    }

    @Override
    public String redoAiTransaction() {
        return call(projectScreen::aiRedoTransaction);
    }

    private static <T> T callOnClient(Callable<T> task) {
        if (RenderSystem.isOnRenderThread()) {
            try {
                return task.call();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while waiting for the client thread", exception
            );
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException(cause);
        }
    }
}
