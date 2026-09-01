package net.eca.client.gui;

import net.eca.util.bossshow.BossShowDefinition.Frame;
import net.eca.util.bossshow.BossShowEditorState;
import net.eca.util.bossshow.Curve;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

//用起始镜头局部坐标生成不受录制噪声影响的运镜路径。
final class BossShowPathGeneratorScreen extends Screen implements BossShowEditorSessionScreen {

    private static final int MAX_DURATION_TICKS = 72000;
    private static final Curve[] MOTION_CURVES = {
        Curve.NONE, Curve.EASE_IN, Curve.EASE_OUT, Curve.EASE_IN_OUT, Curve.EASE_OUT_IN, Curve.BEZIER
    };

    private final Frame startFrame;
    private final int initialDuration;
    private final EditBox[] offsetFields = new EditBox[3];
    private EditBox yawChangeBox;
    private EditBox pitchChangeBox;
    private EditBox durationBox;
    private EditBox speedBox;
    private Button timingModeButton;
    private Button curveButton;
    private boolean speedMode;
    private int curveIndex = 3;
    private Component error = Component.empty();

    BossShowPathGeneratorScreen() {
        super(Component.translatable("gui.eca.bossshow.editor.path.title"));
        startFrame = BossShowEditorState.getPathGenerationStartFrame();
        initialDuration = BossShowEditorState.hasValidRange()
            ? BossShowEditorState.getOutPoint() - BossShowEditorState.getInPoint() + 1 : 40;
    }

    @Override
    protected void init() {
        super.init();
        if (startFrame == null) {
            closePanel();
            return;
        }
        int center = this.width / 2;
        int leftX = center - 150;
        int rightX = center + 10;
        String[] defaults = {"8", "0", "0"};
        for (int i = 0; i < offsetFields.length; i++) {
            int x = i == 1 ? rightX : leftX;
            int y = i < 2 ? 54 : 88;
            EditBox field = new EditBox(this.font, x, y, 140, 18, Component.empty());
            field.setMaxLength(32);
            field.setValue(defaults[i]);
            offsetFields[i] = field;
            this.addRenderableWidget(field);
        }

        yawChangeBox = numberField(rightX, 88, 0.0);
        pitchChangeBox = numberField(leftX, 122, 0.0);
        durationBox = numberField(rightX, 122, initialDuration);
        durationBox.setValue(Integer.toString(initialDuration));
        speedBox = numberField(leftX, 156, averageSpeed(8.0, initialDuration));
        durationBox.active = true;
        speedBox.active = false;

        timingModeButton = Button.builder(timingModeText(), b -> toggleTimingMode())
            .bounds(rightX, 154, 140, 20).build();
        curveButton = Button.builder(curveText(), b -> cycleCurve())
            .bounds(center - 150, 180, 300, 20).build();
        this.addRenderableWidget(timingModeButton);
        this.addRenderableWidget(curveButton);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.eca.bossshow.editor.path.apply"), b -> apply())
            .bounds(center - 100, this.height - 26, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.eca.bossshow.editor.path.cancel"), b -> closePanel())
            .bounds(center + 4, this.height - 26, 96, 20).build());
    }

    private EditBox numberField(int x, int y, double value) {
        EditBox field = new EditBox(this.font, x, y, 140, 18, Component.empty());
        field.setMaxLength(32);
        field.setValue(format(value));
        this.addRenderableWidget(field);
        return field;
    }

    private void toggleTimingMode() {
        speedMode = !speedMode;
        durationBox.active = !speedMode;
        speedBox.active = speedMode;
        updateDerivedTiming();
        timingModeButton.setMessage(timingModeText());
    }

    private void cycleCurve() {
        curveIndex = (curveIndex + 1) % MOTION_CURVES.length;
        curveButton.setMessage(curveText());
    }

    private void apply() {
        try {
            double forward = parseFinite(offsetFields[0]);
            double right = parseFinite(offsetFields[1]);
            double up = parseFinite(offsetFields[2]);
            float yawChange = parseFiniteFloat(yawChangeBox);
            float pitchChange = parseFiniteFloat(pitchChangeBox);
            if (!Float.isFinite(startFrame.yaw() + yawChange)
                || !Float.isFinite(startFrame.pitch() + pitchChange)) throw new IllegalArgumentException();
            int duration = resolveDuration(forward, right, up);
            if (duration < 2 || duration > MAX_DURATION_TICKS) throw new IllegalArgumentException();
            if (BossShowEditorState.generateCameraPath(duration, forward, right, up,
                yawChange, pitchChange, MOTION_CURVES[curveIndex])) {
                closePanel();
            }
        } catch (IllegalArgumentException ignored) {
            error = Component.translatable("gui.eca.bossshow.editor.path.invalid");
        }
    }

    private int resolveDuration(double forward, double right, double up) {
        if (!speedMode) return Integer.parseInt(durationBox.getValue().trim());
        double speed = parseFinite(speedBox);
        double distance = Math.sqrt(forward * forward + right * right + up * up);
        if (speed <= 0.0 || distance <= 0.0) throw new IllegalArgumentException();
        double intervals = Math.ceil(distance * 20.0 / speed);
        if (!Double.isFinite(intervals) || intervals >= MAX_DURATION_TICKS) throw new IllegalArgumentException();
        return (int) intervals + 1;
    }

    private void updateDerivedTiming() {
        try {
            double forward = parseFinite(offsetFields[0]);
            double right = parseFinite(offsetFields[1]);
            double up = parseFinite(offsetFields[2]);
            double distance = Math.sqrt(forward * forward + right * right + up * up);
            if (speedMode) {
                double speed = parseFinite(speedBox);
                if (speed > 0.0 && distance > 0.0) {
                    double intervals = Math.ceil(distance * 20.0 / speed);
                    if (Double.isFinite(intervals) && intervals < MAX_DURATION_TICKS) {
                        durationBox.setValue(Integer.toString((int) intervals + 1));
                    }
                }
            } else {
                int duration = Integer.parseInt(durationBox.getValue().trim());
                if (duration >= 2) speedBox.setValue(format(averageSpeed(distance, duration)));
            }
        } catch (IllegalArgumentException ignored) {
            //输入未完成时保留上一次换算结果，提交时再统一提示。
        }
    }

    private double parseFinite(EditBox field) {
        double value;
        try {
            value = Double.parseDouble(field.getValue().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(exception);
        }
        if (!Double.isFinite(value)) throw new IllegalArgumentException();
        return value;
    }

    private float parseFiniteFloat(EditBox field) {
        double value = parseFinite(field);
        if (value < -Float.MAX_VALUE || value > Float.MAX_VALUE) throw new IllegalArgumentException();
        return (float) value;
    }

    private double averageSpeed(double distance, int duration) {
        return duration <= 1 ? 0.0 : distance * 20.0 / (duration - 1);
    }

    private Component timingModeText() {
        return Component.translatable(speedMode
            ? "gui.eca.bossshow.editor.path.mode_speed"
            : "gui.eca.bossshow.editor.path.mode_duration");
    }

    private Component curveText() {
        return Component.translatable("gui.eca.bossshow.editor.path.curve",
            Component.translatable(MOTION_CURVES[curveIndex].translationKey()));
    }

    private void closePanel() {
        this.minecraft.setScreen(new BossShowEditorScreen());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            closePanel();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateDerivedTiming();
        int center = this.width / 2;
        int labelX = center - 150;
        graphics.drawCenteredString(this.font, this.title, center, 18, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
            Component.translatable("gui.eca.bossshow.editor.path.start",
                BossShowEditorState.getPathGenerationStart()), center, 34, 0xAAAAAA);
        graphics.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.path.forward"),
            labelX, 44, 0xCCCCCC, false);
        graphics.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.path.right"),
            center + 10, 44, 0xCCCCCC, false);
        graphics.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.path.up"),
            labelX, 78, 0xCCCCCC, false);
        graphics.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.path.yaw_change"),
            center + 10, 78, 0xCCCCCC, false);
        graphics.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.path.pitch_change"),
            labelX, 112, 0xCCCCCC, false);
        graphics.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.path.duration"),
            center + 10, 112, 0xCCCCCC, false);
        graphics.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.path.speed"),
            labelX, 146, 0xCCCCCC, false);
        if (!error.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, error, center, this.height - 38, 0xFF5555);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
