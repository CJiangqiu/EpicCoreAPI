package net.eca.client.gui;

import net.eca.util.shader_generator.ShaderModuleDefinition;
import net.eca.util.shader_generator.ShaderModuleRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

public final class ShaderEffectSelectionScreen extends Screen {

    private static final int LIST_WIDTH = 320;
    private final Screen parent;
    private final List<ShaderModuleDefinition> effects;
    private final Consumer<ShaderModuleDefinition> selectionHandler;
    private ShaderModuleDefinition.Category selectedCategory;
    private int scroll;
    private int visibleRows;
    private int listTop;
    private int listBottom;

    public ShaderEffectSelectionScreen(
        Screen parent,
        List<ShaderModuleDefinition> effects,
        Consumer<ShaderModuleDefinition> selectionHandler
    ) {
        super(Component.translatable("gui.eca.shader_generator.effects.title"));
        this.parent = parent;
        this.effects = List.copyOf(effects);
        this.selectionHandler = selectionHandler;
    }

    @Override
    protected void init() {
        if (selectedCategory == null) {
            addCategoryButtons();
        } else {
            addEffectButtons();
        }
        addRenderableWidget(Button.builder(
            Component.translatable(selectedCategory == null
                ? "gui.eca.shader_generator.button.cancel"
                : "gui.eca.shader_generator.button.back"),
            button -> {
                if (selectedCategory == null) {
                    onClose();
                } else {
                    selectedCategory = null;
                    scroll = 0;
                    rebuildWidgets();
                }
            }
        ).bounds(width / 2 - 50, height - 30, 100, 20).build());
    }

    private void addCategoryButtons() {
        int left = (width - LIST_WIDTH) / 2;
        int y = height / 2 - 48;
        for (ShaderModuleDefinition.Category category : ShaderModuleDefinition.Category.values()) {
            addRenderableWidget(Button.builder(
                Component.translatable(category.translationKey()),
                button -> {
                    /* IMAGE 分类只有 image_element 一个条目，直接进入详情编辑 */
                    if (category == ShaderModuleDefinition.Category.IMAGE) {
                        ShaderModuleDefinition definition = ShaderModuleRegistry.get("image_element");
                        if (definition != null) {
                            selectionHandler.accept(definition);
                        }
                        return;
                    }
                    selectedCategory = category;
                    scroll = 0;
                    rebuildWidgets();
                }
            ).bounds(left, y, LIST_WIDTH, 24).build());
            y += 30;
        }
    }

    private void addEffectButtons() {
        List<ShaderModuleDefinition> categoryEffects = categoryEffects();
        int left = (width - LIST_WIDTH) / 2;
        listTop = 48;
        listBottom = height - 42;
        visibleRows = Math.max(1, (listBottom - listTop) / 24);
        int maxScroll = Math.max(0, categoryEffects.size() - visibleRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int end = Math.min(categoryEffects.size(), scroll + visibleRows);
        for (int index = scroll; index < end; index++) {
            ShaderModuleDefinition definition = categoryEffects.get(index);
            int y = listTop + (index - scroll) * 24;
            addRenderableWidget(Button.builder(
                Component.translatable(definition.displayName()),
                button -> selectionHandler.accept(definition)
            ).bounds(left, y, LIST_WIDTH, 20).build());
        }
    }

    private List<ShaderModuleDefinition> categoryEffects() {
        return effects.stream()
            .filter(definition -> definition.category() == selectedCategory)
            .toList();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (selectedCategory == null) {
            return false;
        }
        List<ShaderModuleDefinition> categoryEffects = categoryEffects();
        int maxScroll = Math.max(0, categoryEffects.size() - visibleRows);
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
        Component heading = selectedCategory == null
            ? title
            : Component.translatable(selectedCategory.translationKey());
        graphics.drawCenteredString(font, heading, width / 2, 18, 0xFFFFFFFF);
        graphics.drawCenteredString(
            font,
            Component.translatable(selectedCategory == null
                ? "gui.eca.shader_generator.effects.category_description"
                : "gui.eca.shader_generator.effects.description"),
            width / 2,
            32,
            0xFF9DA3AC
        );
        if (selectedCategory != null && categoryEffects().isEmpty()) {
            graphics.drawCenteredString(
                font,
                Component.translatable("gui.eca.shader_generator.effects.empty_category"),
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
