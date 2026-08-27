package net.eca.client.gui;

import net.eca.util.bossshow.BossShowEditorState;
import net.eca.util.bossshow.Trigger;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

//BossShow 全局演出设置面板，避免把固定属性挤进时间线工作区。
final class BossShowEditorSettingsScreen extends Screen {

    private EditBox targetTypeBox;
    private EditBox radiusBox;
    private EditBox eventNameBox;
    private Button triggerBtn;
    private Button cinematicBtn;
    private Button allowRepeatBtn;
    private Trigger workingTrigger;
    private boolean workingCinematic;
    private boolean workingAllowRepeat;

    BossShowEditorSettingsScreen() {
        super(Component.translatable("gui.eca.bossshow.editor.settings.title"));
    }

    @Override
    protected void init() {
        super.init();
        int x = this.width / 2 - 150;
        int fieldX = x + 100;
        targetTypeBox = new EditBox(this.font, fieldX, 58, 200, 18,
            Component.translatable("gui.eca.bossshow.editor.target_type_placeholder"));
        targetTypeBox.setMaxLength(128);
        ResourceLocation typeId = BossShowEditorState.getTargetType() == null ? null
            : BuiltInRegistries.ENTITY_TYPE.getKey(BossShowEditorState.getTargetType());
        targetTypeBox.setValue(typeId == null ? "" : typeId.toString());
        this.addRenderableWidget(targetTypeBox);

        Trigger trigger = BossShowEditorState.getTrigger();
        workingTrigger = trigger;
        workingCinematic = BossShowEditorState.isCinematic();
        workingAllowRepeat = BossShowEditorState.isAllowRepeat();
        radiusBox = new EditBox(this.font, fieldX, 106, 200, 18,
            Component.translatable("gui.eca.bossshow.editor.radius_placeholder"));
        radiusBox.setMaxLength(16);
        radiusBox.setValue(trigger instanceof Trigger.Range r ? String.valueOf(r.effectRadius()) : "32");
        this.addRenderableWidget(radiusBox);
        eventNameBox = new EditBox(this.font, fieldX, 106, 200, 18,
            Component.translatable("gui.eca.bossshow.editor.event_name_placeholder"));
        eventNameBox.setMaxLength(128);
        eventNameBox.setValue(trigger instanceof Trigger.Custom c ? c.eventName() : "");
        this.addRenderableWidget(eventNameBox);

        triggerBtn = Button.builder(triggerText(trigger), b -> cycleTrigger()).bounds(x, 82, 90, 20).build();
        this.addRenderableWidget(triggerBtn);
        cinematicBtn = Button.builder(flagText("cinematic", workingCinematic), b -> {
            workingCinematic = !workingCinematic;
            cinematicBtn.setMessage(flagText("cinematic", workingCinematic));
        }).bounds(x, 142, 140, 20).build();
        this.addRenderableWidget(cinematicBtn);
        allowRepeatBtn = Button.builder(flagText("allow_repeat", workingAllowRepeat), b -> {
            workingAllowRepeat = !workingAllowRepeat;
            allowRepeatBtn.setMessage(flagText("allow_repeat", workingAllowRepeat));
        }).bounds(x + 148, 142, 152, 20).build();
        this.addRenderableWidget(allowRepeatBtn);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.eca.bossshow.editor.settings.apply"), b -> apply())
            .bounds(this.width / 2 - 100, this.height - 36, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.eca.bossshow.editor.settings.cancel"), b -> closePanel())
            .bounds(this.width / 2 + 4, this.height - 36, 96, 20).build());
        updateTriggerVisibility(trigger);
    }

    private void cycleTrigger() {
        Trigger next = workingTrigger instanceof Trigger.Range
            ? new Trigger.Custom("") : new Trigger.Range(32.0);
        workingTrigger = next;
        triggerBtn.setMessage(triggerText(next));
        updateTriggerVisibility(next);
    }

    private void updateTriggerVisibility(Trigger trigger) {
        boolean range = trigger instanceof Trigger.Range;
        radiusBox.visible = range;
        eventNameBox.visible = !range;
    }

    private void apply() {
        String type = targetTypeBox.getValue().trim();
        ResourceLocation typeId = type.isEmpty() ? null : ResourceLocation.tryParse(type);
        if (typeId != null && BuiltInRegistries.ENTITY_TYPE.containsKey(typeId)) {
            BossShowEditorState.setTargetType(BuiltInRegistries.ENTITY_TYPE.get(typeId));
        } else if (type.isEmpty()) {
            BossShowEditorState.setTargetType(null);
        }
        if (workingTrigger instanceof Trigger.Range) {
            try {
                BossShowEditorState.setTrigger(new Trigger.Range(Double.parseDouble(radiusBox.getValue().trim())));
            } catch (NumberFormatException ignored) {
                return;
            }
        } else {
            BossShowEditorState.setTrigger(new Trigger.Custom(eventNameBox.getValue()));
        }
        BossShowEditorState.setCinematic(workingCinematic);
        BossShowEditorState.setAllowRepeat(workingAllowRepeat);
        closePanel();
    }

    private void closePanel() {
        this.minecraft.setScreen(new BossShowEditorScreen());
    }

    private Component triggerText(Trigger trigger) {
        return Component.translatable("gui.eca.bossshow.editor.trigger",
            Component.translatable(trigger.translationKey()));
    }

    private Component flagText(String key, boolean value) {
        return Component.translatable("gui.eca.bossshow.editor." + key,
            Component.translatable(value ? "gui.eca.bossshow.editor.on" : "gui.eca.bossshow.editor.off"));
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

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
        int x = this.width / 2 - 150;
        g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.settings.target"), x, 63, 0xCCCCCC, false);
        g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.settings.trigger"), x, 87, 0xCCCCCC, false);
        g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.settings.value"), x, 111, 0xCCCCCC, false);
        super.render(g, mouseX, mouseY, partialTick);
    }
}
