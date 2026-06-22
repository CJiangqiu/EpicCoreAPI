package net.eca.client.gui;

import net.eca.util.shader_generator.ShaderProjectCodec;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class ShaderProjectDetailsScreen extends Screen {

    private static final int DIALOG_WIDTH = 320;
    private final Screen parent;
    private final ProjectHandler handler;
    private final String initialModId;
    private final String initialShaderName;
    private EditBox modIdField;
    private EditBox shaderNameField;
    private Component error;

    public ShaderProjectDetailsScreen(
        Screen parent,
        Component title,
        String initialModId,
        String initialShaderName,
        ProjectHandler handler
    ) {
        super(title);
        this.parent = parent;
        this.initialModId = initialModId == null ? "" : initialModId;
        this.initialShaderName = initialShaderName == null ? "" : initialShaderName;
        this.handler = handler;
    }

    @Override
    protected void init() {
        int left = (width - DIALOG_WIDTH) / 2;
        int top = height / 2 - 72;
        modIdField = new EditBox(font, left + 16, top + 30, DIALOG_WIDTH - 32, 20,
            Component.translatable("gui.eca.shader_generator.project.mod_id"));
        modIdField.setMaxLength(64);
        modIdField.setValue(initialModId);
        addRenderableWidget(modIdField);

        shaderNameField = new EditBox(font, left + 16, top + 74, DIALOG_WIDTH - 32, 20,
            Component.translatable("gui.eca.shader_generator.project.shader_name"));
        shaderNameField.setMaxLength(64);
        shaderNameField.setValue(initialShaderName);
        addRenderableWidget(shaderNameField);

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.confirm"),
            button -> submit()
        ).bounds(left + 48, top + 112, 100, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.cancel"),
            button -> onClose()
        ).bounds(left + 172, top + 112, 100, 20).build());
        setInitialFocus(modIdField);
    }

    private void submit() {
        String modId = modIdField.getValue().trim();
        String shaderName = shaderNameField.getValue().trim();
        if (!ShaderProjectCodec.isValidModId(modId)) {
            error = Component.translatable("gui.eca.shader_generator.project.invalid_mod_id");
            return;
        }
        if (!ShaderProjectCodec.isValidShaderName(shaderName)) {
            error = Component.translatable("gui.eca.shader_generator.project.invalid_shader_name");
            return;
        }
        if (!handler.apply(modId, shaderName)) {
            error = Component.translatable("gui.eca.shader_generator.project.operation_failed");
            return;
        }
        minecraft.setScreen(parent);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = (width - DIALOG_WIDTH) / 2;
        int top = height / 2 - 72;
        graphics.fill(left, top, left + DIALOG_WIDTH, top + 144, 0xFF202225);
        graphics.renderOutline(left, top, DIALOG_WIDTH, 144, 0xFF60656D);
        graphics.drawCenteredString(font, title, width / 2, top + 10, 0xFFFFFFFF);
        graphics.drawString(font,
            Component.translatable("gui.eca.shader_generator.project.mod_id_prompt"),
            left + 16, top + 20, 0xFFC7CBD1, false);
        graphics.drawString(font,
            Component.translatable("gui.eca.shader_generator.project.shader_name_prompt"),
            left + 16, top + 64, 0xFFC7CBD1, false);
        if (error != null) {
            graphics.drawCenteredString(font, error, width / 2, top + 100, 0xFFFF6B6B);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @FunctionalInterface
    public interface ProjectHandler {
        boolean apply(String modId, String shaderName);
    }
}
