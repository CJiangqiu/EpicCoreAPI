package net.eca.client.gui;

import net.eca.util.bossshow.BossShowEditorState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//BossShow 时间轴范围位姿偏移面板。
public final class BossShowRangeTransformScreen extends Screen {

    private final EditBox[] fields = new EditBox[5];
    private final EditBox[] endFields = new EditBox[5];
    private Button modeBtn;
    private boolean rampMode;
    private Component error = Component.empty();

    public BossShowRangeTransformScreen() {
        super(Component.translatable("gui.eca.bossshow.editor.range_offset.title"));
    }

    @Override
    protected void init() {
        super.init();
        int x = this.width / 2 - 45;
        int endX = this.width / 2 + 55;
        String[] labels = {"dx", "dy", "dz", "yaw", "pitch"};
        for (int i = 0; i < fields.length; i++) {
            EditBox field = new EditBox(this.font, x, 58 + i * 24, 90, 18, Component.literal(labels[i]));
            field.setMaxLength(32);
            field.setValue("0");
            fields[i] = field;
            this.addRenderableWidget(field);

            EditBox endField = new EditBox(this.font, endX, 58 + i * 24, 90, 18, Component.literal(labels[i]));
            endField.setMaxLength(32);
            endField.setValue("0");
            endField.visible = false;
            endFields[i] = endField;
            this.addRenderableWidget(endField);
        }

        modeBtn = Button.builder(modeText(), b -> toggleMode())
            .bounds(this.width / 2 + 4, 18, 120, 20).build();
        this.addRenderableWidget(modeBtn);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.eca.bossshow.editor.range_offset.apply"),
            b -> apply()).bounds(this.width / 2 - 100, this.height - 48, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.eca.bossshow.editor.range_offset.cancel"),
            b -> closePanel()).bounds(this.width / 2 + 4, this.height - 48, 96, 20).build());
    }

    private void apply() {
        if (!BossShowEditorState.hasValidRange()) {
            error = Component.translatable("gui.eca.bossshow.editor.range_offset.no_range");
            return;
        }
        try {
            double dx = Double.parseDouble(fields[0].getValue().trim());
            double dy = Double.parseDouble(fields[1].getValue().trim());
            double dz = Double.parseDouble(fields[2].getValue().trim());
            float yaw = Float.parseFloat(fields[3].getValue().trim());
            float pitch = Float.parseFloat(fields[4].getValue().trim());
            if (rampMode) {
                double endDx = Double.parseDouble(endFields[0].getValue().trim());
                double endDy = Double.parseDouble(endFields[1].getValue().trim());
                double endDz = Double.parseDouble(endFields[2].getValue().trim());
                float endYaw = Float.parseFloat(endFields[3].getValue().trim());
                float endPitch = Float.parseFloat(endFields[4].getValue().trim());
                BossShowEditorState.rampSelectedRange(dx, dy, dz, yaw, pitch,
                    endDx, endDy, endDz, endYaw, endPitch);
            } else {
                BossShowEditorState.offsetSelectedRange(dx, dy, dz, yaw, pitch);
            }
            closePanel();
        } catch (NumberFormatException ignored) {
            error = Component.translatable("gui.eca.bossshow.editor.range_offset.invalid");
        }
    }

    private void closePanel() {
        this.minecraft.setScreen(new BossShowEditorScreen());
    }

    private void toggleMode() {
        rampMode = !rampMode;
        for (EditBox endField : endFields) endField.visible = rampMode;
        modeBtn.setMessage(modeText());
    }

    private Component modeText() {
        return Component.translatable(rampMode
            ? "gui.eca.bossshow.editor.range_offset.mode_ramp"
            : "gui.eca.bossshow.editor.range_offset.mode_uniform");
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        g.drawCenteredString(this.font,
            Component.translatable("gui.eca.bossshow.editor.range_offset.range",
                BossShowEditorState.getInPoint(), BossShowEditorState.getOutPoint()),
            this.width / 2, 38, 0xAAAAAA);
        String[] labels = {"dx", "dy", "dz", "yaw", "pitch"};
        int x = this.width / 2 - 72;
        for (int i = 0; i < labels.length; i++) {
            g.drawString(this.font, Component.literal(labels[i]), x, 63 + i * 24, 0xCCCCCC, false);
        }
        if (rampMode) {
            g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.range_offset.start"),
                this.width / 2 - 42, 46, 0xAAAAAA, false);
            g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.range_offset.end"),
                this.width / 2 + 58, 46, 0xAAAAAA, false);
        }
        if (!error.getString().isEmpty()) {
            g.drawCenteredString(this.font, error, this.width / 2, this.height - 72, 0xFF5555);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
}
