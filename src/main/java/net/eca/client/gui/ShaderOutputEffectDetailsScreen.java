package net.eca.client.gui;

import net.eca.util.shader_generator.ShaderModuleDefinition;
import net.eca.util.shader_generator.ShaderOutputEffectInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class ShaderOutputEffectDetailsScreen extends Screen {

    private static final int PANEL_WIDTH = 360;

    private final Screen parent;
    private final Screen returnScreen;
    private final ShaderOutputEffectInstance effect;
    private final Consumer<ShaderOutputEffectInstance> confirmHandler;
    private final Runnable removeHandler;
    private int scroll;
    private int visibleRows;
    private List<ShaderModuleDefinition.Parameter> parameters = List.of();

    public ShaderOutputEffectDetailsScreen(
        Screen parent,
        Screen returnScreen,
        ShaderOutputEffectInstance source,
        Consumer<ShaderOutputEffectInstance> confirmHandler,
        Runnable removeHandler
    ) {
        super(Component.translatable("gui.eca.shader_generator.output_effect.details_title",
            Component.translatable(source.definition().displayName())));
        this.parent = parent;
        this.returnScreen = returnScreen;
        this.effect = source.copy();
        this.confirmHandler = confirmHandler;
        this.removeHandler = removeHandler;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int rowY = 44;
        int controlsBottom = height - 42;

        addRenderableWidget(Button.builder(
            Component.translatable(effect.enabled()
                ? "gui.eca.shader_generator.output_effect.enabled"
                : "gui.eca.shader_generator.output_effect.disabled"),
            button -> {
                effect.setEnabled(!effect.enabled());
                rebuildWidgets();
            }
        ).bounds(left, rowY, PANEL_WIDTH, 20).build());
        rowY += 26;

        parameters = effect.definition().parameters();
        visibleRows = Math.max(1, (controlsBottom - rowY) / 22);
        int maxScroll = Math.max(0, parameters.size() - visibleRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int end = Math.min(parameters.size(), scroll + visibleRows);
        for (int index = scroll; index < end; index++) {
            ShaderModuleDefinition.Parameter parameter = parameters.get(index);
            int y = rowY + (index - scroll) * 22;
            addRenderableWidget(Button.builder(Component.literal("-"),
                button -> adjust(parameter, -parameter.step()))
                .bounds(left + 220, y, 36, 20).build());
            addRenderableWidget(Button.builder(
                Component.literal(String.format(Locale.ROOT, "%.3f", effect.value(parameter.key()))),
                button -> {}
            ).bounds(left + 260, y, 56, 20).build());
            addRenderableWidget(Button.builder(Component.literal("+"),
                button -> adjust(parameter, parameter.step()))
                .bounds(left + 320, y, 40, 20).build());
        }

        if (removeHandler != null) {
            addRenderableWidget(Button.builder(
                Component.translatable("gui.eca.shader_generator.output_effect.remove"),
                button -> {
                    removeHandler.run();
                    minecraft.setScreen(returnScreen);
                }
            ).bounds(width / 2 - 156, height - 30, 88, 20).build());
        }
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.confirm"),
            button -> confirm()
        ).bounds(width / 2 - 52, height - 30, 96, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.cancel"),
            button -> onClose()
        ).bounds(width / 2 + 60, height - 30, 96, 20).build());
    }

    private void adjust(ShaderModuleDefinition.Parameter parameter, float delta) {
        effect.setValue(parameter.key(), effect.value(parameter.key()) + delta);
        rebuildWidgets();
    }

    private void confirm() {
        confirmHandler.accept(effect);
        minecraft.setScreen(returnScreen);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, parameters.size() - visibleRows);
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
        int left = (width - PANEL_WIDTH) / 2;
        graphics.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);
        int baseY = 70;
        int end = Math.min(parameters.size(), scroll + visibleRows);
        for (int index = scroll; index < end; index++) {
            ShaderModuleDefinition.Parameter parameter = parameters.get(index);
            graphics.drawString(font, Component.translatable(parameter.displayName()), left,
                baseY + (index - scroll) * 22, 0xFFC7CBD1, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
