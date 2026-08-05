package net.eca.client.gui;

import net.eca.util.shader_generator.ai.ShaderAiToolRegistry;
import net.eca.util.shader_generator.ai.ShaderAiToolResult;
import net.eca.util.shader_generator.mcp.ShaderMcpSessionInfo;
import net.eca.util.shader_generator.mcp.ShaderMcpSettings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

final class ShaderMcpScreen extends Screen {

    private static final int PADDING = 8;
    private static final int BUTTON_HEIGHT = 20;
    private final Screen returnScreen;
    private final ShaderGeneratorScreen projectScreen;
    private EditBox portField;
    private Component status = Component.translatable("gui.eca.shader_generator.mcp.ready");
    private boolean statusError;
    private int panelRight;
    private int previewLeft;
    private int previewTop;
    private int previewRight;
    private int previewBottom;

    ShaderMcpScreen(Screen returnScreen, ShaderGeneratorScreen projectScreen) {
        super(Component.translatable("gui.eca.shader_generator.mcp.title"));
        this.returnScreen = returnScreen;
        this.projectScreen = projectScreen;
    }

    @Override
    protected void init() {
        int previewPanelLeft = Math.min(
            Math.max(340, (int) (width * 0.62F)),
            Math.max(220, width - 140)
        );
        previewTop = 52;
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

        ShaderMcpSettings settings = projectScreen.mcpSettings();
        portField = new EditBox(
            font,
            46,
            30,
            62,
            BUTTON_HEIGHT,
            Component.translatable("gui.eca.shader_generator.mcp.port")
        );
        portField.setMaxLength(5);
        portField.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
        portField.setValue(Integer.toString(settings.port()));
        portField.setEditable(!projectScreen.isMcpRunning());
        addRenderableWidget(portField);

        addRenderableWidget(Button.builder(
            Component.translatable(projectScreen.isMcpRunning()
                ? "gui.eca.shader_generator.mcp.stop"
                : "gui.eca.shader_generator.mcp.start"),
            button -> toggleServer()
        ).bounds(114, 30, 66, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.ai.back"),
            button -> onClose()
        ).bounds(panelRight - PADDING - 60, 30, 60, BUTTON_HEIGHT).build());
    }

    private void toggleServer() {
        if (projectScreen.isMcpRunning()) {
            projectScreen.stopMcp();
            statusError = false;
            status = Component.translatable("gui.eca.shader_generator.mcp.stopped");
            rebuildWidgets();
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getValue());
        } catch (NumberFormatException exception) {
            showError("gui.eca.shader_generator.mcp.invalid_port");
            return;
        }
        if (port < 1 || port > 65_535) {
            showError("gui.eca.shader_generator.mcp.invalid_port");
            return;
        }
        try {
            projectScreen.startMcp(new ShaderMcpSettings(port));
            statusError = false;
            status = Component.translatable("gui.eca.shader_generator.mcp.started");
            rebuildWidgets();
        } catch (RuntimeException exception) {
            statusError = true;
            status = Component.literal("MCP: " + conciseMessage(exception));
        }
    }

    private void showError(String key) {
        statusError = true;
        status = Component.translatable(key);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF111315);
        graphics.fill(0, 0, width, 26, 0xFF17191C);
        graphics.fill(0, 26, panelRight, height, 0xFF1A1C20);
        graphics.fill(panelRight - 1, 0, panelRight, height, 0xFF60656D);
        graphics.drawCenteredString(font, title, panelRight / 2, 9, 0xFFFFFFFF);
        graphics.drawString(
            font,
            Component.translatable("gui.eca.shader_generator.mcp.port"),
            PADDING,
            36,
            0xFFCDD1D7,
            false
        );

        int textY = 62;
        int normalColor = 0xFFCDD1D7;
        int stateColor = projectScreen.isMcpRunning() ? 0xFF62D878 : 0xFFFFB86C;
        Component state = Component.translatable(projectScreen.isMcpRunning()
            ? "gui.eca.shader_generator.mcp.state_running"
            : "gui.eca.shader_generator.mcp.state_stopped");
        graphics.drawString(font, state, PADDING, textY, stateColor, false);
        textY += 16;
        graphics.drawString(font, projectScreen.mcpEndpoint(), PADDING, textY, normalColor, false);
        textY += 16;
        graphics.drawString(
            font,
            Component.translatable(
                "gui.eca.shader_generator.mcp.tool_count",
                ShaderAiToolRegistry.definitions().size()
            ),
            PADDING,
            textY,
            normalColor,
            false
        );
        textY += 16;
        graphics.drawString(
            font,
            Component.translatable("gui.eca.shader_generator.mcp.loopback_only"),
            PADDING,
            textY,
            0xFF9DA3AC,
            false
        );
        textY += 22;

        List<ShaderMcpSessionInfo> sessions = projectScreen.mcpSessions();
        graphics.drawString(
            font,
            Component.translatable("gui.eca.shader_generator.mcp.clients", sessions.size()),
            PADDING,
            textY,
            0xFFFFFFFF,
            false
        );
        textY += 15;
        if (sessions.isEmpty()) {
            graphics.drawString(
                font,
                Component.translatable("gui.eca.shader_generator.mcp.no_clients"),
                PADDING + 6,
                textY,
                0xFF8E949D,
                false
            );
        } else {
            int availableRows = Math.max(1, (height - textY - 44) / 42);
            for (int index = 0; index < Math.min(availableRows, sessions.size()); index++) {
                ShaderMcpSessionInfo session = sessions.get(index);
                int top = textY + index * 42;
                graphics.fill(PADDING, top, panelRight - PADDING, top + 36, 0xFF24272C);
                String title = session.clientName() + " " + session.clientVersion();
                graphics.drawString(font, title, PADDING + 6, top + 5, 0xFF68DCE8, false);
                long idleSeconds = Math.max(
                    0L,
                    (System.currentTimeMillis() - session.lastActivity()) / 1000L
                );
                String detail = shortId(session.id()) + "  " + session.remoteAddress()
                    + "  " + Component.translatable(
                        "gui.eca.shader_generator.mcp.last_active", idleSeconds
                    ).getString();
                graphics.drawString(font, detail, PADDING + 6, top + 19, 0xFFAAB0B8, false);
            }
        }

        graphics.drawString(
            font,
            status,
            PADDING,
            height - 18,
            statusError ? 0xFFFF6B6B : 0xFF62D878,
            false
        );

        graphics.fill(
            previewLeft - 1,
            previewTop - 1,
            previewRight + 1,
            previewBottom + 1,
            0xFF3C4046
        );
        graphics.fill(previewLeft, previewTop, previewRight, previewBottom, 0xFF08090B);
        graphics.enableScissor(previewLeft, previewTop, previewRight, previewBottom);
        projectScreen.renderAiPreview(
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
        graphics.drawCenteredString(
            font,
            Component.translatable("gui.eca.shader_generator.ai.preview"),
            previewLeft + (previewRight - previewLeft) / 2,
            34,
            normalColor
        );
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    ShaderAiToolResult capturePreview() {
        return ShaderPreviewCapture.capture(
            previewLeft, previewTop, previewRight, previewBottom
        );
    }

    @Override
    public void onClose() {
        minecraft.setScreen(returnScreen);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static String conciseMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
            ? throwable.getClass().getSimpleName()
            : message.replace('\n', ' ').replace('\r', ' ');
    }
}
