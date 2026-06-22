package net.eca.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.Color;

public final class ShaderColorPickerScreen extends Screen {

    private static final int PICKER_SIZE = 160;
    private final Screen parent;
    private final ColorHandler handler;
    private float hue;
    private float saturation;
    private float brightness;
    private float alpha;

    public ShaderColorPickerScreen(
        Screen parent,
        float red,
        float green,
        float blue,
        float alpha,
        ColorHandler handler
    ) {
        super(Component.translatable("gui.eca.shader_generator.color_picker.title"));
        this.parent = parent;
        this.handler = handler;
        float[] hsb = Color.RGBtoHSB(channel(red), channel(green), channel(blue), null);
        this.hue = hsb[0];
        this.saturation = hsb[1];
        this.brightness = hsb[2];
        this.alpha = clamp(alpha);
    }

    @Override
    protected void init() {
        int left = width / 2 - 104;
        int top = height / 2 - PICKER_SIZE / 2;
        addRenderableWidget(new SaturationBrightnessWidget(left, top, PICKER_SIZE, PICKER_SIZE));
        addRenderableWidget(new HueWidget(left + PICKER_SIZE + 8, top, 16, PICKER_SIZE));
        addRenderableWidget(Button.builder(
            Component.literal("-"),
            button -> alpha = clamp(alpha - 0.05F)
        ).bounds(left + PICKER_SIZE + 32, top + 70, 20, 20).build());
        addRenderableWidget(Button.builder(
            Component.literal("+"),
            button -> alpha = clamp(alpha + 0.05F)
        ).bounds(left + PICKER_SIZE + 76, top + 70, 20, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.confirm"),
            button -> confirm()
        ).bounds(width / 2 - 104, top + PICKER_SIZE + 10, 96, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.cancel"),
            button -> onClose()
        ).bounds(width / 2 + 8, top + PICKER_SIZE + 10, 96, 20).build());
    }

    private void confirm() {
        int rgb = Color.HSBtoRGB(hue, saturation, brightness);
        handler.apply(
            ((rgb >> 16) & 0xFF) / 255.0F,
            ((rgb >> 8) & 0xFF) / 255.0F,
            (rgb & 0xFF) / 255.0F,
            alpha
        );
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = width / 2 - 104;
        int top = height / 2 - PICKER_SIZE / 2;
        graphics.drawCenteredString(font, title, width / 2, top - 16, 0xFFFFFFFF);
        int color = currentArgb();
        graphics.fill(left + PICKER_SIZE + 32, top, left + PICKER_SIZE + 96, top + 56, color);
        graphics.renderOutline(left + PICKER_SIZE + 32, top, 64, 56, 0xFFFFFFFF);
        graphics.drawCenteredString(
            font,
            Component.translatable(
                "gui.eca.shader_generator.color_picker.alpha",
                Math.round(alpha * 100.0F)
            ),
            left + PICKER_SIZE + 64,
            top + 62,
            0xFFFFFFFF
        );
        graphics.drawCenteredString(
            font,
            String.format("#%06X", color & 0xFFFFFF),
            left + PICKER_SIZE + 64,
            top + 98,
            0xFFCDD1D7
        );
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private int currentArgb() {
        int rgb = Color.HSBtoRGB(hue, saturation, brightness);
        return Math.round(alpha * 255.0F) << 24 | rgb & 0xFFFFFF;
    }

    private static int channel(float value) {
        return Math.round(clamp(value) * 255.0F);
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    @FunctionalInterface
    public interface ColorHandler {
        void apply(float red, float green, float blue, float alpha);
    }

    private final class SaturationBrightnessWidget extends AbstractWidget {

        private SaturationBrightnessWidget(int x, int y, int width, int height) {
            super(x, y, width, height,
                Component.translatable("gui.eca.shader_generator.color_picker.saturation_brightness"));
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            update(mouseX, mouseY);
        }

        @Override
        protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
            update(mouseX, mouseY);
        }

        private void update(double mouseX, double mouseY) {
            saturation = clamp((float) ((mouseX - getX()) / width));
            brightness = clamp(1.0F - (float) ((mouseY - getY()) / height));
        }

        @Override
        protected void renderWidget(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
        ) {
            int steps = 40;
            for (int row = 0; row < steps; row++) {
                float value = 1.0F - row / (float) (steps - 1);
                int y1 = getY() + row * height / steps;
                int y2 = getY() + (row + 1) * height / steps;
                for (int column = 0; column < steps; column++) {
                    float sat = column / (float) (steps - 1);
                    int x1 = getX() + column * width / steps;
                    int x2 = getX() + (column + 1) * width / steps;
                    graphics.fill(x1, y1, x2, y2, 0xFF000000 | Color.HSBtoRGB(hue, sat, value));
                }
            }
            int markerX = getX() + Math.round(saturation * width);
            int markerY = getY() + Math.round((1.0F - brightness) * height);
            graphics.renderOutline(markerX - 2, markerY - 2, 5, 5, 0xFFFFFFFF);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class HueWidget extends AbstractWidget {

        private HueWidget(int x, int y, int width, int height) {
            super(x, y, width, height,
                Component.translatable("gui.eca.shader_generator.color_picker.hue"));
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            update(mouseY);
        }

        @Override
        protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
            update(mouseY);
        }

        private void update(double mouseY) {
            hue = clamp((float) ((mouseY - getY()) / height));
        }

        @Override
        protected void renderWidget(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
        ) {
            for (int row = 0; row < height; row++) {
                float rowHue = row / (float) Math.max(1, height - 1);
                graphics.fill(
                    getX(),
                    getY() + row,
                    getX() + width,
                    getY() + row + 1,
                    0xFF000000 | Color.HSBtoRGB(rowHue, 1.0F, 1.0F)
                );
            }
            int markerY = getY() + Math.round(hue * height);
            graphics.renderOutline(getX() - 1, markerY - 1, width + 2, 3, 0xFFFFFFFF);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
