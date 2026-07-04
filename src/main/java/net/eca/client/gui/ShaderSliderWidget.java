package net.eca.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/* PS 风格数值滑条：拖动改值、单击定位、双击键入精确数值 */
public final class ShaderSliderWidget extends AbstractWidget {

    private static final long DOUBLE_CLICK_MS = 450L;
    private static final int TRACK_BG = 0xFF2A2D31;
    private static final int TRACK_FILL = 0x883C6CFF;
    private static final int BORDER = 0xFF3C4046;

    private final double min;
    private final double max;
    private final double step;
    private final DoubleSupplier supplier;
    private final DoubleConsumer consumer;
    private final Formatter formatter;
    private final EditBox field;
    private long lastClickTime;
    private boolean editing;

    public ShaderSliderWidget(
        int x,
        int y,
        int width,
        int height,
        Component label,
        double min,
        double max,
        double step,
        DoubleSupplier supplier,
        DoubleConsumer consumer,
        Formatter formatter
    ) {
        super(x, y, width, height, label);
        this.min = min;
        this.max = max;
        this.step = step <= 0.0 ? 0.001 : step;
        this.supplier = supplier;
        this.consumer = consumer;
        this.formatter = formatter;
        this.field = new EditBox(Minecraft.getInstance().font, x, y, width, height, label);
        this.field.setMaxLength(10);
    }

    private double value() {
        return Mth.clamp(supplier.getAsDouble(), min, max);
    }

    private double valueFromX(double mouseX) {
        double range = max - min;
        if (range <= 0.0) {
            return min;
        }
        double t = Mth.clamp((mouseX - getX()) / Math.max(1, width), 0.0, 1.0);
        double snapped = min + Math.round(t * range / step) * step;
        return Mth.clamp(snapped, min, max);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || button != 0 || !clicked(mouseX, mouseY)) {
            return false;
        }
        if (editing) {
            field.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastClickTime <= DOUBLE_CLICK_MS) {
            startEditing();
            lastClickTime = 0L;
            return true;
        }
        lastClickTime = now;
        setFocused(true);
        consumer.accept(valueFromX(mouseX));
        return true;
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        if (!editing) {
            consumer.accept(valueFromX(mouseX));
        }
    }

    private void startEditing() {
        editing = true;
        field.setValue(inputText(value()));
        field.setFocused(true);
        field.setCursorPosition(field.getValue().length());
        field.setHighlightPos(0);
        setFocused(true);
    }

    @Override
    public void setFocused(boolean focused) {
        boolean wasEditing = editing;
        super.setFocused(focused);
        field.setFocused(focused && editing);
        if (wasEditing && !focused) {
            confirm();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!editing) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            cancel();
            return true;
        }
        return field.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return editing ? field.charTyped(codePoint, modifiers) : super.charTyped(codePoint, modifiers);
    }

    private void confirm() {
        if (!editing) {
            return;
        }
        String text = field.getValue().trim();
        editing = false;
        field.setFocused(false);
        if (text.isEmpty()) {
            return;
        }
        try {
            double next = Mth.clamp(Double.parseDouble(text), min, max);
            if (Math.abs(next - value()) > 0.0001) {
                consumer.accept(next);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void cancel() {
        editing = false;
        field.setFocused(false);
    }

    @Override
    protected void renderWidget(
        GuiGraphics graphics,
        int mouseX,
        int mouseY,
        float partialTick
    ) {
        if (editing) {
            field.render(graphics, mouseX, mouseY, partialTick);
            return;
        }
        graphics.fill(getX(), getY(), getX() + width, getY() + height, TRACK_BG);
        double t = max > min ? (value() - min) / (max - min) : 0.0;
        int fillWidth = (int) Math.round(Mth.clamp(t, 0.0, 1.0) * width);
        if (fillWidth > 0) {
            graphics.fill(getX(), getY(), getX() + fillWidth, getY() + height, TRACK_FILL);
        }
        int border = isHoveredOrFocused() ? 0xFFFFFFFF : BORDER;
        graphics.renderOutline(getX(), getY(), width, height, border);
        graphics.drawString(
            Minecraft.getInstance().font,
            formatter.format(value()),
            getX() + 4,
            getY() + (height - 8) / 2,
            0xFFFFFFFF,
            false
        );
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    private String inputText(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((int) Math.round(value));
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    @FunctionalInterface
    public interface Formatter {
        Component format(double value);
    }
}
