package net.eca.client.gui;

import net.eca.util.shader_generator.ai.ShaderAiSettings;
import net.eca.util.shader_generator.ai.ShaderAiSettingsCodec;
import net.eca.util.shader_generator.ai.ShaderAiToolResult;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;


final class ShaderAiAssistantScreen extends Screen {

    private static final int PADDING = 8;
    private static final int BUTTON_HEIGHT = 20;
    private final ShaderGeneratorScreen parent;
    private ShaderAiSession session;
    private ShaderAiSettings settings;
    private ShaderAiTranscriptWidget transcript;
    private EditBox prompt;
    private Button sendButton;
    private int panelRight;
    private int previewLeft;
    private int previewTop;
    private int previewRight;
    private int previewBottom;

    ShaderAiAssistantScreen(ShaderGeneratorScreen parent, ShaderAiSession session) {
        super(Component.translatable("gui.eca.shader_generator.ai.title"));
        this.parent = parent;
        this.session = session;
    }

    @Override
    protected void init() {
        settings = ShaderAiSettingsCodec.load();
        int previewPanelLeft = Math.min(
            Math.max(340, (int) (width * 0.62F)),
            Math.max(220, width - 140)
        );
        previewTop = 34;
        int previewPanelRight = width - PADDING;
        int previewPanelBottom = height - PADDING;
        int previewSize = Math.max(1, Math.min(
            previewPanelRight - previewPanelLeft,
            previewPanelBottom - previewTop
        ));
        previewLeft = previewPanelLeft
            + (previewPanelRight - previewPanelLeft - previewSize) / 2;
        previewRight = previewLeft + previewSize;
        previewBottom = previewTop + previewSize;
        panelRight = previewPanelLeft - 6;
        int toolbarY = 26;
        int transcriptBottom = height - 58;
        transcript = new ShaderAiTranscriptWidget(
            font,
            PADDING,
            toolbarY + BUTTON_HEIGHT + 4,
            panelRight - PADDING * 2,
            transcriptBottom - toolbarY - BUTTON_HEIGHT - 4
        );
        transcript.setMessages(session.messages());
        addRenderableWidget(transcript);

        int promptWidth = Math.max(80, panelRight - PADDING * 2 - 78);
        prompt = new EditBox(
            font,
            PADDING,
            height - 50,
            promptWidth,
            BUTTON_HEIGHT,
            Component.translatable("gui.eca.shader_generator.ai.prompt")
        );
        prompt.setMaxLength(16_384);
        prompt.setHint(Component.translatable("gui.eca.shader_generator.ai.prompt"));
        addRenderableWidget(prompt);
        sendButton = addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.ai.send"),
            button -> send()
        ).bounds(PADDING + promptWidth + 4, height - 50, 74, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.ai.settings"),
            button -> openSettings()
        ).bounds(PADDING, toolbarY, 64, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.ai.reload"),
            button -> reloadSettings()
        ).bounds(PADDING + 68, toolbarY, 54, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.ai.end"),
            button -> endConversation()
        ).bounds(PADDING + 126, toolbarY, 60, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.mcp.button"),
            button -> openMcp()
        ).bounds(PADDING + 190, toolbarY, 46, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.ai.back"),
            button -> onClose()
        ).bounds(panelRight - PADDING - 60, toolbarY, 60, BUTTON_HEIGHT).build());
        if (session.isEmpty()) {
            session.addStatus(Component.translatable(
                "gui.eca.shader_generator.ai.ready"
            ).getString());
            transcript.setMessages(session.messages());
        }
        setFocused(prompt);
    }

    @Override
    public void tick() {
        prompt.tick();
        transcript.setMessages(session.messages());
        boolean busy = session.busy();
        sendButton.active = !busy;
        prompt.setEditable(!busy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER && getFocused() == prompt && !hasShiftDown()) {
            send();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void send() {
        String message = prompt.getValue().strip();
        if (message.isEmpty()) return;
        ShaderAiSession requestSession = session;
        if (requestSession.send(
            settings,
            message,
            new ShaderProjectToolContext(
                parent,
                () -> !requestSession.isClosed(),
                this::capturePreview
            )
        )) prompt.setValue("");
    }

    private void openSettings() {
        minecraft.setScreen(new ShaderAiSettingsScreen(this, settings));
    }

    void settingsSaved(ShaderAiSettings savedSettings) {
        settings = savedSettings;
        session.addStatus(Component.translatable(
            "gui.eca.shader_generator.ai.settings_saved"
        ).getString());
    }

    private void reloadSettings() {
        settings = ShaderAiSettingsCodec.load();
        ShaderAiSettings.Profile profile = settings.selectedProfile();
        session.addStatus(profile == null
            ? "未找到可用的 AI 配置"
            : "已重载配置：" + profile.id() + " / " + profile.protocol().serializedName());
    }

    private void endConversation() {
        session = parent.resetAiSession();
        session.addStatus(Component.translatable(
            "gui.eca.shader_generator.ai.ended"
        ).getString());
        transcript.setMessages(session.messages());
    }

    private void openMcp() {
        minecraft.setScreen(new ShaderMcpScreen(this, parent));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && !transcript.containsInteractionPoint(mouseX, mouseY)) {
            transcript.clearSelection();
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        transcript.setMessages(session.messages());
        graphics.fill(0, 0, width, height, 0xFF111315);
        graphics.fill(0, 0, width, 26, 0xFF17191C);
        graphics.fill(0, 26, panelRight, height, 0xFF1A1C20);
        graphics.fill(previewLeft - 1, previewTop - 1,
            previewRight + 1, previewBottom + 1, 0xFF3C4046);
        graphics.fill(previewLeft, previewTop, previewRight, previewBottom, 0xFF08090B);
        graphics.enableScissor(previewLeft, previewTop, previewRight, previewBottom);
        parent.renderAiPreview(
            graphics,
            previewLeft,
            previewTop,
            previewRight,
            previewBottom,
            mouseX,
            mouseY,
            partialTick
        );
        graphics.disableScissor();
        graphics.fill(panelRight - 1, 0, panelRight, height, 0xFF60656D);
        graphics.drawCenteredString(font, title, panelRight / 2, 9, 0xFFFFFFFF);
        graphics.drawCenteredString(
            font,
            Component.translatable("gui.eca.shader_generator.ai.preview"),
            previewLeft + (previewRight - previewLeft) / 2,
            12,
            0xFFCDD1D7
        );
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        parent.returnFromAiAssistant();
        minecraft.setScreen(parent);
    }

    ShaderAiToolResult capturePreview() {
        if (minecraft.screen != this) return parent.captureToolPreview();
        return ShaderPreviewCapture.capture(
            previewLeft, previewTop, previewRight, previewBottom
        );
    }

}
