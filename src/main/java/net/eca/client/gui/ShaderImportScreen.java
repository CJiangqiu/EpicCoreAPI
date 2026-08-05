package net.eca.client.gui;

import net.eca.util.shader_generator.ShaderFolderImporter.Candidate;
import net.eca.util.shader_generator.ShaderProjectCodec;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class ShaderImportScreen extends Screen {

    private final ShaderGeneratorScreen parent;
    private final Candidate candidate;
    private EditBox modIdField;
    private EditBox shaderNameField;
    private Component error;

    ShaderImportScreen(ShaderGeneratorScreen parent, Candidate candidate) {
        super(Component.translatable("gui.eca.shader_generator.import.title"));
        this.parent = parent;
        this.candidate = candidate;
    }

    @Override
    protected void init() {
        int left = width / 2 - 180;
        int top = height / 2 - 82;
        modIdField = field(left, top + 54, candidate.suggestedModId());
        shaderNameField = field(left, top + 98, candidate.suggestedName());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.confirm"), button -> submit()
        ).bounds(left + 60, top + 132, 100, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.cancel"), button -> onClose()
        ).bounds(left + 200, top + 132, 100, 20).build());
        setInitialFocus(modIdField);
    }

    private EditBox field(int x, int y, String value) {
        EditBox field = new EditBox(font, x, y, 360, 20, Component.empty());
        field.setMaxLength(128);
        field.setValue(value);
        addRenderableWidget(field);
        return field;
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
        if (!parent.importShader(candidate, modId, shaderName)) {
            error = Component.translatable("gui.eca.shader_generator.import.failed");
            return;
        }
        parent.openSourceEditor();
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
        int left = width / 2 - 180;
        int top = height / 2 - 82;
        graphics.fill(left - 12, top, left + 372, top + 164, 0xFF202225);
        graphics.drawCenteredString(font, title, width / 2, top + 8, 0xFFFFFFFF);
        graphics.drawCenteredString(
            font,
            Component.translatable(
                "gui.eca.shader_generator.import.candidate",
                candidate.displayName(),
                candidate.fileCount()
            ),
            width / 2,
            top + 24,
            0xFFC7CBD1
        );
        graphics.drawString(font,
            Component.translatable("gui.eca.shader_generator.project.mod_id_prompt"),
            left, top + 44, 0xFFC7CBD1, false);
        graphics.drawString(font,
            Component.translatable("gui.eca.shader_generator.project.shader_name_prompt"),
            left, top + 88, 0xFFC7CBD1, false);
        if (error != null) {
            graphics.drawCenteredString(font, error, width / 2, top + 120, 0xFFFF6B6B);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
