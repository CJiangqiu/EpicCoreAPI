package net.eca.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

public final class ShaderPercentEditWidget extends AbstractWidget {

    private static final long DOUBLE_CLICK_MS = 450L;

    private final DoubleSupplier valueSupplier;
    private final DoubleConsumer valueConsumer;
    private final LabelFormatter labelFormatter;
    private final EditBox field;
    private long lastClickTime;
    private boolean editing;

    public ShaderPercentEditWidget(
        int x,
        int y,
        int width,
        int height,
        Component narration,
        DoubleSupplier valueSupplier,
        DoubleConsumer valueConsumer,
        LabelFormatter labelFormatter
    ) {
        super(x, y, width, height, narration);
        this.valueSupplier = valueSupplier;
        this.valueConsumer = valueConsumer;
        this.labelFormatter = labelFormatter;
        this.field = new EditBox(Minecraft.getInstance().font, x, y, width, height, narration);
        this.field.setMaxLength(8);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || button != 0 || !clicked(mouseX, mouseY)) {
            return false;
        }
        if (editing) {
            setFocused(true);
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
        return true;
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
        String text = field.getValue().trim().replace("%", "");
        editing = false;
        field.setFocused(false);
        if (text.isEmpty()) {
            return;
        }
        try {
            double next = clamp(Double.parseDouble(text) / 100.0D);
            if (Math.abs(next - value()) > 0.0001D) {
                valueConsumer.accept(next);
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
        if (isHoveredOrFocused()) {
            graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x33FFFFFF);
        }
        graphics.drawCenteredString(
            Minecraft.getInstance().font,
            labelFormatter.format(value()),
            getX() + width / 2,
            getY() + (height - 8) / 2,
            0xFFFFFFFF
        );
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    private double value() {
        return clamp(valueSupplier.getAsDouble());
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static String inputText(double value) {
        double percent = value * 100.0D;
        if (Math.abs(percent - Math.rint(percent)) < 0.0001D) {
            return String.valueOf((int) Math.round(percent));
        }
        return String.format(Locale.ROOT, "%.2f", percent);
    }

    public static String displayPercent(double value) {
        double percent = clamp(value) * 100.0D;
        if (Math.abs(percent - Math.rint(percent)) < 0.0001D) {
            return String.valueOf((int) Math.round(percent));
        }
        return String.format(Locale.ROOT, "%.1f", percent);
    }

    @FunctionalInterface
    public interface LabelFormatter {
        Component format(double value);
    }
}
