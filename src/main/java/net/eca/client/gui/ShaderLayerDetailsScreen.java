package net.eca.client.gui;

import net.eca.util.EcaLogger;
import net.eca.util.shader_generator.ShaderProjectCodec;
import net.eca.util.shader_generator.ShaderProjectCodec.ProjectRef;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.function.Consumer;

public final class ShaderLayerDetailsScreen extends Screen {

    private final Screen parent;
    private final ProjectRef projectRef;
    private final Consumer<LayerDetails> confirmHandler;
    private final String initialName;
    private EditBox nameField;
    private float red;
    private float green;
    private float blue;
    private float alpha;
    private String imagePath;

    public ShaderLayerDetailsScreen(
        Screen parent,
        ProjectRef projectRef,
        LayerDetails initial,
        Consumer<LayerDetails> confirmHandler
    ) {
        super(Component.translatable("gui.eca.shader_generator.layer.details_title"));
        this.parent = parent;
        this.projectRef = projectRef;
        this.confirmHandler = confirmHandler;
        this.initialName = initial.name();
        this.red = initial.red();
        this.green = initial.green();
        this.blue = initial.blue();
        this.alpha = initial.alpha();
        this.imagePath = initial.imagePath();
    }

    @Override
    protected void init() {
        int left = width / 2 - 150;
        int top = height / 2 - 86;
        nameField = new EditBox(
            font,
            left,
            top + 24,
            300,
            20,
            Component.translatable("gui.eca.shader_generator.layer.name")
        );
        nameField.setMaxLength(64);
        nameField.setValue(initialName);
        addRenderableWidget(nameField);
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.layer.base_color"),
            button -> openColorPicker()
        ).bounds(left, top + 54, 300, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable(imagePath == null
                ? "gui.eca.shader_generator.layer.import_background"
                : "gui.eca.shader_generator.layer.replace_background"),
            button -> importBackground()
        ).bounds(left, top + 84, 300, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.confirm"),
            button -> confirm()
        ).bounds(left + 38, top + 124, 100, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.cancel"),
            button -> onClose()
        ).bounds(left + 162, top + 124, 100, 20).build());
        setInitialFocus(nameField);
    }

    private void openColorPicker() {
        minecraft.setScreen(new ShaderColorPickerScreen(
            this,
            red,
            green,
            blue,
            alpha,
            (newRed, newGreen, newBlue, newAlpha) -> {
                red = newRed;
                green = newGreen;
                blue = newBlue;
                alpha = newAlpha;
            }
        ));
    }

    /**
     * 使用 LWJGL TinyFileDialogs 打开操作系统原生文件选择器。
     * 不依赖 AWT/Swing，通过各平台 C 库直接调用：
     * Windows → GetOpenFileNameW / macOS → NSOpenPanel / Linux → GTK/Zenity。
     */
    private void importBackground() {
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
                imagePath = ShaderProjectCodec.importTexture(projectRef, Path.of(selected));
                clearWidgets();
                setFocused(null);
                init();
            }
        } catch (Exception e) {
            EcaLogger.error("File dialog failed: {}", e.getMessage());
        }
    }

    private void confirm() {
        String name = nameField.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        confirmHandler.accept(new LayerDetails(name, red, green, blue, alpha, imagePath));
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = width / 2 - 150;
        int top = height / 2 - 86;
        graphics.drawCenteredString(font, title, width / 2, top, 0xFFFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
        /* 颜色矩形和文本在按钮上层绘制，避免被遮挡 */
        graphics.fill(left + 8, top + 58, left + 44, top + 70, colorArgb());
        graphics.renderOutline(left + 8, top + 58, 36, 12, 0xFFFFFFFF);
        if (imagePath != null) {
            graphics.drawCenteredString(font, imagePath, width / 2, top + 108, 0xFFCDD1D7);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private int colorArgb() {
        return channel(alpha) << 24
            | channel(red) << 16
            | channel(green) << 8
            | channel(blue);
    }

    private static int channel(float value) {
        return Math.round(Math.max(0.0F, Math.min(1.0F, value)) * 255.0F);
    }

    public record LayerDetails(
        String name,
        float red,
        float green,
        float blue,
        float alpha,
        String imagePath
    ) {}
}
