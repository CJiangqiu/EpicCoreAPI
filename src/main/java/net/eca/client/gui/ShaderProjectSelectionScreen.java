package net.eca.client.gui;

import net.eca.util.shader_generator.ShaderProjectCodec.ProjectRef;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

public final class ShaderProjectSelectionScreen extends Screen {

    private static final int LIST_WIDTH = 300;
    private final Screen parent;
    private final List<ProjectRef> projects;
    private final Consumer<ProjectRef> selectionHandler;
    private int scroll;
    private int visibleRows;
    private int listTop;
    private int listBottom;

    public ShaderProjectSelectionScreen(
        Screen parent,
        List<ProjectRef> projects,
        Consumer<ProjectRef> selectionHandler
    ) {
        super(Component.translatable("gui.eca.shader_generator.project.open_title"));
        this.parent = parent;
        this.projects = List.copyOf(projects);
        this.selectionHandler = selectionHandler;
    }

    @Override
    protected void init() {
        int left = (width - LIST_WIDTH) / 2;
        listTop = 42;
        listBottom = height - 42;
        visibleRows = Math.max(1, (listBottom - listTop) / 22);
        int maxScroll = Math.max(0, projects.size() - visibleRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int end = Math.min(projects.size(), scroll + visibleRows);
        for (int index = scroll; index < end; index++) {
            ProjectRef reference = projects.get(index);
            int y = listTop + (index - scroll) * 22;
            addRenderableWidget(Button.builder(
                Component.literal(reference.id()),
                button -> {
                    selectionHandler.accept(reference);
                    minecraft.setScreen(parent);
                }
            ).bounds(left, y, LIST_WIDTH, 20).build());
        }
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.cancel"),
            button -> onClose()
        ).bounds(width / 2 - 50, height - 30, 100, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, projects.size() - visibleRows);
        int next = Math.max(0, Math.min(maxScroll, scroll + (delta > 0.0 ? -1 : 1)));
        if (next != scroll) {
            scroll = next;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);
        if (projects.isEmpty()) {
            graphics.drawCenteredString(
                font,
                Component.translatable("gui.eca.shader_generator.project.none"),
                width / 2,
                height / 2,
                0xFF9DA3AC
            );
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
