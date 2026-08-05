package net.eca.client.gui;

import net.eca.util.shader_generator.ShaderFolderImporter.Candidate;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

public final class ShaderImportSelectionScreen extends Screen {

    private static final int LIST_WIDTH = 420;
    private final Screen parent;
    private final List<Candidate> candidates;
    private final Consumer<Candidate> selectionHandler;
    private int scroll;
    private int visibleRows;

    ShaderImportSelectionScreen(
        Screen parent,
        List<Candidate> candidates,
        Consumer<Candidate> selectionHandler
    ) {
        super(Component.translatable("gui.eca.shader_generator.import.select_title"));
        this.parent = parent;
        this.candidates = List.copyOf(candidates);
        this.selectionHandler = selectionHandler;
    }

    @Override
    protected void init() {
        int listTop = 42;
        int listBottom = height - 42;
        visibleRows = Math.max(1, (listBottom - listTop) / 22);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, candidates.size() - visibleRows)));
        int left = (width - LIST_WIDTH) / 2;
        int end = Math.min(candidates.size(), scroll + visibleRows);
        for (int index = scroll; index < end; index++) {
            Candidate candidate = candidates.get(index);
            int y = listTop + (index - scroll) * 22;
            addRenderableWidget(Button.builder(
                Component.translatable(
                    "gui.eca.shader_generator.import.candidate",
                    candidate.displayName(),
                    candidate.fileCount()
                ),
                button -> selectionHandler.accept(candidate)
            ).bounds(left, y, LIST_WIDTH, 20).build());
        }
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.cancel"),
            button -> onClose()
        ).bounds(width / 2 - 50, height - 30, 100, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, candidates.size() - visibleRows);
        int next = Math.max(0, Math.min(maxScroll, scroll + (delta > 0.0D ? -1 : 1)));
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
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
