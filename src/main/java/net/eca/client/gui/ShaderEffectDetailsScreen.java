package net.eca.client.gui;

import net.eca.util.EcaLogger;
import net.eca.util.shader_generator.ShaderModuleDefinition;
import net.eca.util.shader_generator.ShaderModuleInstance;
import net.eca.util.shader_generator.ShaderProjectCodec;
import net.eca.util.shader_generator.ShaderProjectCodec.ProjectRef;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class ShaderEffectDetailsScreen extends Screen {

    private static final int PANEL_WIDTH = 360;
    private final Screen parent;
    private final Screen returnScreen;
    private final ShaderModuleInstance effect;
    private final ProjectRef projectRef;
    private final Consumer<ShaderModuleInstance> confirmHandler;
    private int scroll;
    private int visibleRows;
    private List<ShaderModuleDefinition.Parameter> visibleParameters = List.of();

    public ShaderEffectDetailsScreen(
        Screen parent,
        Screen returnScreen,
        ShaderModuleDefinition definition,
        ProjectRef projectRef,
        Consumer<ShaderModuleInstance> confirmHandler
    ) {
        super(Component.translatable("gui.eca.shader_generator.effects.details_title",
            Component.translatable(definition.displayName())));
        this.parent = parent;
        this.returnScreen = returnScreen;
        this.effect = definition.createInstance();
        this.projectRef = projectRef;
        this.confirmHandler = confirmHandler;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int top = 44;
        int controlsBottom = height - 42;
        visibleRows = Math.max(1, (controlsBottom - top - 28) / 22);
        visibleParameters = effect.definition().parameters().stream()
            .filter(parameter -> !isColorParameter(parameter.key()))
            .toList();
        int maxScroll = Math.max(0, visibleParameters.size() - visibleRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.parameter.color"),
            button -> openColorPicker()
        ).bounds(left, top, PANEL_WIDTH, 20).build());

        int end = Math.min(visibleParameters.size(), scroll + visibleRows);
        for (int index = scroll; index < end; index++) {
            ShaderModuleDefinition.Parameter parameter = visibleParameters.get(index);
            int y = top + 26 + (index - scroll) * 22;
            if (parameter.key().equals("repeat")) {
                addRenderableWidget(Button.builder(
                    Component.translatable(effect.value("repeat") >= 0.5F
                        ? "gui.eca.shader_generator.effects.repeat_on"
                        : "gui.eca.shader_generator.effects.repeat_off"),
                    button -> {
                        effect.setValue("repeat", effect.value("repeat") >= 0.5F ? 0.0F : 1.0F);
                        rebuildWidgets();
                    }
                ).bounds(left + 220, y, 140, 20).build());
                continue;
            }
            addRenderableWidget(Button.builder(
                Component.literal("-"),
                button -> adjust(parameter, -parameter.step())
            ).bounds(left + 220, y, 36, 20).build());
            addRenderableWidget(Button.builder(
                Component.literal(String.format(Locale.ROOT, "%.2f", effect.value(parameter.key()))),
                button -> {}
            ).bounds(left + 260, y, 56, 20).build());
            addRenderableWidget(Button.builder(
                Component.literal("+"),
                button -> adjust(parameter, parameter.step())
            ).bounds(left + 320, y, 40, 20).build());
        }

        if (effect.definition().id().equals("image_element")) {
            addRenderableWidget(Button.builder(
                Component.translatable(effect.imagePath() == null
                    ? "gui.eca.shader_generator.effects.import_image"
                    : "gui.eca.shader_generator.effects.replace_image"),
                button -> importImage()
            ).bounds(left, height - 66, PANEL_WIDTH, 20).build());
        }

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.confirm"),
            button -> confirm()
        ).bounds(width / 2 - 104, height - 30, 96, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.cancel"),
            button -> onClose()
        ).bounds(width / 2 + 8, height - 30, 96, 20).build());
    }

    private void adjust(ShaderModuleDefinition.Parameter parameter, float delta) {
        effect.setValue(parameter.key(), effect.value(parameter.key()) + delta);
        rebuildWidgets();
    }

    private void openColorPicker() {
        minecraft.setScreen(new ShaderColorPickerScreen(
            this,
            effect.value("color_r"),
            effect.value("color_g"),
            effect.value("color_b"),
            effect.value("color_a"),
            (red, green, blue, alpha) -> {
                effect.setValue("color_r", red);
                effect.setValue("color_g", green);
                effect.setValue("color_b", blue);
                effect.setValue("color_a", alpha);
            }
        ));
    }

    private void importImage() {
        if (projectRef == null) {
            return;
        }
        try {
            String selected = TinyFileDialogs.tinyfd_openFileDialog(
                "Select PNG Image",
                null,
                null,
                "PNG Image (*.png)",
                false
            );
            if (selected != null) {
                effect.setImagePath(ShaderProjectCodec.importTexture(projectRef, Path.of(selected)));
                clearWidgets();
                setFocused(null);
                init();
            }
        } catch (Exception e) {
            EcaLogger.error("File dialog failed: {}", e.getMessage());
        }
    }

    private void confirm() {
        if (effect.definition().id().equals("image_element") && effect.imagePath() == null) {
            return;
        }
        confirmHandler.accept(effect);
        minecraft.setScreen(returnScreen);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, visibleParameters.size() - visibleRows);
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
        int top = 44;
        graphics.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);
        int color = colorArgb();
        graphics.fill(left + 8, top + 4, left + 44, top + 16, color);
        graphics.renderOutline(left + 8, top + 4, 36, 12, 0xFFFFFFFF);

        int end = Math.min(visibleParameters.size(), scroll + visibleRows);
        for (int index = scroll; index < end; index++) {
            ShaderModuleDefinition.Parameter parameter = visibleParameters.get(index);
            int y = top + 32 + (index - scroll) * 22;
            graphics.drawString(
                font,
                Component.translatable(parameter.displayName()),
                left,
                y,
                0xFFC7CBD1,
                false
            );
        }
        if (effect.definition().id().equals("image_element") && effect.imagePath() != null) {
            graphics.drawCenteredString(font, effect.imagePath(), width / 2, height - 78, 0xFFCDD1D7);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private int colorArgb() {
        return channel(effect.value("color_a")) << 24
            | channel(effect.value("color_r")) << 16
            | channel(effect.value("color_g")) << 8
            | channel(effect.value("color_b"));
    }

    private static boolean isColorParameter(String key) {
        return key.equals("color_r")
            || key.equals("color_g")
            || key.equals("color_b")
            || key.equals("color_a");
    }

    private static int channel(float value) {
        return Math.round(Math.max(0.0F, Math.min(1.0F, value)) * 255.0F);
    }
}
