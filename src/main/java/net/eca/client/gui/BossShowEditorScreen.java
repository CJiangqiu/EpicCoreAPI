package net.eca.client.gui;

import net.eca.network.BossShowSaveEditorPacket;
import net.eca.network.NetworkHandler;
import net.eca.util.bossshow.BossShowDefinition;
import net.eca.util.bossshow.BossShowDefinition.Frame;
import net.eca.util.bossshow.BossShowDefinition.Keyframe;
import net.eca.util.bossshow.BossShowEditorState;
import net.eca.util.bossshow.Curve;
import net.eca.util.bossshow.Trigger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.List;

//BossShow 编辑器：trigger + cinematic + 关键帧列表 + 录制控制
public class BossShowEditorScreen extends Screen {

    private static final int LABEL_W = 60;
    private static final int FIELD_W = 110;

    //左侧
    private Button allowRepeatBtn;
    private Button cinematicBtn;
    private EditBox targetTypeBox;
    private Button triggerTypeBtn;
    private EditBox triggerRadiusBox;
    private EditBox customEventNameBox;
    private KeyframeList keyframeList;
    private Button removeKeyframeBtn;

    //时间轴 + 区间操作
    private Timeline timeline;
    private Button setInBtn;
    private Button setOutBtn;
    private Button copyBtn;
    private Button cutBtn;
    private Button deleteRangeBtn;
    private Button pasteBtn;

    //右侧关键帧编辑
    private EditBox eventIdBox;
    private EditBox subtitleBox;
    private Button curveBtn;

    //底部
    private Button recordBtn;
    private Button saveBtn;
    private Button closeBtn;

    private boolean suppressResponders = false;

    public BossShowEditorScreen() {
        super(Component.translatable("gui.eca.bossshow.editor.title", ""));
    }

    @Override
    protected void init() {
        super.init();
        if (!BossShowEditorState.isActive() || BossShowEditorState.getEditingId() == null) {
            this.minecraft.setScreen(null);
            return;
        }

        int leftX = 8;
        int leftW = this.width / 2 - 16;
        int rightX = this.width / 2 + 8;
        int topY = 46;
        int bottomBarY = this.height - 28;

        //=== Row 0: allowRepeat | cinematic ===
        allowRepeatBtn = Button.builder(
            Component.translatable("gui.eca.bossshow.editor.allow_repeat", flagText(BossShowEditorState.isAllowRepeat())),
            b -> {
                BossShowEditorState.setAllowRepeat(!BossShowEditorState.isAllowRepeat());
                allowRepeatBtn.setMessage(Component.translatable("gui.eca.bossshow.editor.allow_repeat",
                    flagText(BossShowEditorState.isAllowRepeat())));
            }
        ).bounds(leftX, topY, 130, 20).build();
        this.addRenderableWidget(allowRepeatBtn);

        cinematicBtn = Button.builder(
            Component.translatable("gui.eca.bossshow.editor.cinematic", flagText(BossShowEditorState.isCinematic())),
            b -> {
                BossShowEditorState.setCinematic(!BossShowEditorState.isCinematic());
                cinematicBtn.setMessage(Component.translatable("gui.eca.bossshow.editor.cinematic",
                    flagText(BossShowEditorState.isCinematic())));
            }
        ).bounds(leftX + 138, topY, 130, 20).build();
        this.addRenderableWidget(cinematicBtn);

        //=== Row 1: target_type ===
        targetTypeBox = new EditBox(this.font, leftX, topY + 26, leftW, 16,
            Component.translatable("gui.eca.bossshow.editor.target_type_placeholder"));
        targetTypeBox.setMaxLength(128);
        EntityType<?> curType = BossShowEditorState.getTargetType();
        ResourceLocation curTypeId = curType != null ? BuiltInRegistries.ENTITY_TYPE.getKey(curType) : null;
        targetTypeBox.setValue(curTypeId != null ? curTypeId.toString() : "");
        targetTypeBox.setResponder(s -> {
            if (suppressResponders) return;
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                BossShowEditorState.setTargetType(null);
                BossShowEditorState.markDirty();
                return;
            }
            ResourceLocation rl = ResourceLocation.tryParse(trimmed);
            if (rl != null && BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) {
                BossShowEditorState.setTargetType(BuiltInRegistries.ENTITY_TYPE.get(rl));
                BossShowEditorState.markDirty();
            }
        });
        this.addRenderableWidget(targetTypeBox);

        //=== Row 2: trigger ===
        Trigger trig = BossShowEditorState.getTrigger();
        triggerTypeBtn = Button.builder(
            Component.translatable("gui.eca.bossshow.editor.trigger", Component.translatable(trig.translationKey())),
            b -> cycleTriggerType()
        ).bounds(leftX, topY + 48, 110, 20).build();
        this.addRenderableWidget(triggerTypeBtn);

        triggerRadiusBox = new EditBox(this.font, leftX + 116, topY + 50, 80, 16,
            Component.translatable("gui.eca.bossshow.editor.radius_placeholder"));
        triggerRadiusBox.setMaxLength(10);
        triggerRadiusBox.setResponder(s -> {
            if (suppressResponders) return;
            try {
                double r = Double.parseDouble(s.trim());
                if (BossShowEditorState.getTrigger() instanceof Trigger.Range) {
                    BossShowEditorState.setTrigger(new Trigger.Range(r));
                }
            } catch (NumberFormatException ignored) {}
        });
        this.addRenderableWidget(triggerRadiusBox);

        customEventNameBox = new EditBox(this.font, leftX + 116, topY + 50, leftW - 116, 16,
            Component.translatable("gui.eca.bossshow.editor.event_name_placeholder"));
        customEventNameBox.setMaxLength(128);
        customEventNameBox.setResponder(s -> {
            if (suppressResponders) return;
            if (BossShowEditorState.getTrigger() instanceof Trigger.Custom) {
                BossShowEditorState.setTrigger(new Trigger.Custom(s));
            }
        });
        this.addRenderableWidget(customEventNameBox);

        //底部自下而上：record/save/close → 时间轴 → 区间操作 → 关键帧列表
        int timelineY = bottomBarY - 22;
        int rangeOpsY = bottomBarY - 46;

        //=== 关键帧列表 ===
        int listTop = topY + 76;
        int listBottom = rangeOpsY - 28;
        keyframeList = new KeyframeList(this.minecraft, leftW, listBottom - listTop, listTop, listBottom, 18);
        keyframeList.setLeftPos(leftX);
        keyframeList.setRenderBackground(false);
        keyframeList.setRenderTopAndBottom(false);
        this.addWidget(keyframeList);

        removeKeyframeBtn = Button.builder(Component.translatable("gui.eca.bossshow.editor.remove_keyframe"), b -> {
            int frameIdx = BossShowEditorState.getSelectedKeyframeFrameIndex();
            if (BossShowEditorState.removeKeyframe(frameIdx)) {
                rebuildKeyframeList();
                syncFromState();
            }
        }).bounds(leftX, listBottom + 4, 130, 18).build();
        this.addRenderableWidget(removeKeyframeBtn);

        //=== 区间操作按钮（全宽 6 等分）===
        int opsX = leftX;
        int opsTotalW = this.width - 16;
        int opsGap = 4;
        int opsBtnW = (opsTotalW - 5 * opsGap) / 6;
        setInBtn = addRangeOpButton(opsX, rangeOpsY, opsBtnW, "set_in", b -> {
            BossShowEditorState.setInPoint(BossShowEditorState.getPlayhead());
            updateTimelineButtons();
        });
        setOutBtn = addRangeOpButton(opsX + (opsBtnW + opsGap), rangeOpsY, opsBtnW, "set_out", b -> {
            BossShowEditorState.setOutPoint(BossShowEditorState.getPlayhead());
            updateTimelineButtons();
        });
        copyBtn = addRangeOpButton(opsX + 2 * (opsBtnW + opsGap), rangeOpsY, opsBtnW, "copy", b -> {
            BossShowEditorState.copyRange();
            updateTimelineButtons();
        });
        cutBtn = addRangeOpButton(opsX + 3 * (opsBtnW + opsGap), rangeOpsY, opsBtnW, "cut", b -> {
            if (BossShowEditorState.cutRange()) afterStructuralEdit();
        });
        deleteRangeBtn = addRangeOpButton(opsX + 4 * (opsBtnW + opsGap), rangeOpsY, opsBtnW, "delete_range", b -> {
            if (BossShowEditorState.deleteRange()) afterStructuralEdit();
        });
        pasteBtn = addRangeOpButton(opsX + 5 * (opsBtnW + opsGap), rangeOpsY, opsBtnW, "paste", b -> {
            if (BossShowEditorState.pasteAtPlayhead()) afterStructuralEdit();
        });

        //=== 时间轴 ===
        timeline = new Timeline(leftX, timelineY, this.width - 16, 14);
        this.addRenderableWidget(timeline);

        //=== 右侧：选中关键帧编辑 ===
        eventIdBox = new EditBox(this.font, rightX + LABEL_W + 40, topY + 1, FIELD_W, 16,
            Component.translatable("gui.eca.bossshow.editor.label.event"));
        eventIdBox.setMaxLength(64);
        eventIdBox.setResponder(s -> {
            if (suppressResponders) return;
            int frameIdx = BossShowEditorState.getSelectedKeyframeFrameIndex();
            Keyframe kf = BossShowEditorState.getSelectedKeyframeData();
            if (kf == null) return;
            BossShowEditorState.replaceKeyframe(frameIdx,
                new Keyframe(s.isEmpty() ? null : s, kf.subtitleText(), kf.curve()));
        });
        this.addRenderableWidget(eventIdBox);

        subtitleBox = new EditBox(this.font, rightX + LABEL_W + 40, topY + 23, FIELD_W, 16,
            Component.translatable("gui.eca.bossshow.editor.label.subtitle"));
        subtitleBox.setMaxLength(256);
        subtitleBox.setResponder(s -> {
            if (suppressResponders) return;
            int frameIdx = BossShowEditorState.getSelectedKeyframeFrameIndex();
            Keyframe kf = BossShowEditorState.getSelectedKeyframeData();
            if (kf == null) return;
            BossShowEditorState.replaceKeyframe(frameIdx,
                new Keyframe(kf.eventId(), s.isEmpty() ? null : s, kf.curve()));
        });
        this.addRenderableWidget(subtitleBox);

        //=== curve ===
        curveBtn = Button.builder(Component.translatable(Curve.NONE.translationKey()), b -> cycleCurve())
            .bounds(rightX + LABEL_W + 40, topY + 45, FIELD_W, 20).build();
        this.addRenderableWidget(curveBtn);

        //=== 底部按钮 ===
        recordBtn = Button.builder(Component.translatable("gui.eca.bossshow.editor.record"), b -> startRecording())
            .bounds(leftX, bottomBarY, 130, 20).build();
        this.addRenderableWidget(recordBtn);

        saveBtn = Button.builder(Component.translatable("gui.eca.bossshow.editor.save"), b -> doSave())
            .bounds(leftX + 138, bottomBarY, 80, 20).build();
        this.addRenderableWidget(saveBtn);

        closeBtn = Button.builder(Component.translatable("gui.eca.bossshow.editor.back"), b -> attemptClose())
            .bounds(this.width - 128, bottomBarY, 120, 20).build();
        this.addRenderableWidget(closeBtn);

        rebuildKeyframeList();
        syncFromState();
        updateTimelineButtons();
        //编辑器界面打开期间启用相机预览（播放头帧位姿）
        BossShowEditorState.setPreviewEnabled(true);
    }

    @Override
    public void removed() {
        BossShowEditorState.setPreviewEnabled(false);
        super.removed();
    }

    private Button addRangeOpButton(int x, int y, int w, String key, Button.OnPress onPress) {
        Button b = Button.builder(Component.translatable("gui.eca.bossshow.editor.timeline." + key), onPress)
            .bounds(x, y, w, 18).build();
        this.addRenderableWidget(b);
        return b;
    }

    //结构性编辑（剪切/删除/粘贴）后刷新列表、右侧面板、按钮状态
    private void afterStructuralEdit() {
        rebuildKeyframeList();
        syncFromState();
        updateTimelineButtons();
    }

    private void updateTimelineButtons() {
        if (setInBtn == null) return;
        boolean hasFrames = BossShowEditorState.frameCount() > 0;
        boolean range = BossShowEditorState.hasValidRange();
        boolean clip = BossShowEditorState.hasClipboard();
        setInBtn.active = hasFrames;
        setOutBtn.active = hasFrames;
        copyBtn.active = range;
        cutBtn.active = range;
        deleteRangeBtn.active = range;
        pasteBtn.active = clip;
    }

    private static Component flagText(boolean v) {
        return Component.translatable(v ? "gui.eca.bossshow.editor.on" : "gui.eca.bossshow.editor.off");
    }

    private void cycleTriggerType() {
        Trigger cur = BossShowEditorState.getTrigger();
        Trigger next = (cur instanceof Trigger.Range) ? new Trigger.Custom("") : new Trigger.Range(32.0);
        BossShowEditorState.setTrigger(next);
        triggerTypeBtn.setMessage(Component.translatable("gui.eca.bossshow.editor.trigger", Component.translatable(next.translationKey())));
        applyTriggerFieldVisibility(next);
    }

    private void applyTriggerFieldVisibility(Trigger trig) {
        suppressResponders = true;
        try {
            boolean isRange = trig instanceof Trigger.Range;
            triggerRadiusBox.setValue(isRange ? String.valueOf(((Trigger.Range) trig).effectRadius()) : "");
            triggerRadiusBox.setEditable(isRange);
            triggerRadiusBox.visible = isRange;

            boolean isCustom = trig instanceof Trigger.Custom;
            customEventNameBox.setValue(isCustom ? ((Trigger.Custom) trig).eventName() : "");
            customEventNameBox.setEditable(isCustom);
            customEventNameBox.visible = isCustom;
        } finally {
            suppressResponders = false;
        }
    }

    private void cycleCurve() {
        int frameIdx = BossShowEditorState.getSelectedKeyframeFrameIndex();
        Keyframe kf = BossShowEditorState.getSelectedKeyframeData();
        if (kf == null) return;
        Curve next = kf.curve().next();
        BossShowEditorState.replaceKeyframe(frameIdx, new Keyframe(kf.eventId(), kf.subtitleText(), next));
        curveBtn.setMessage(Component.translatable(next.translationKey()));
    }

    private void rebuildKeyframeList() {
        if (keyframeList == null) return;
        keyframeList.rebuild();
    }

    public void syncFromState() {
        if (triggerTypeBtn == null || eventIdBox == null || subtitleBox == null
            || removeKeyframeBtn == null || curveBtn == null) return;
        suppressResponders = true;
        try {
            Trigger trig = BossShowEditorState.getTrigger();
            triggerTypeBtn.setMessage(Component.translatable("gui.eca.bossshow.editor.trigger", Component.translatable(trig.translationKey())));

            cinematicBtn.setMessage(Component.translatable("gui.eca.bossshow.editor.cinematic",
                flagText(BossShowEditorState.isCinematic())));
            allowRepeatBtn.setMessage(Component.translatable("gui.eca.bossshow.editor.allow_repeat",
                flagText(BossShowEditorState.isAllowRepeat())));

            EntityType<?> curType = BossShowEditorState.getTargetType();
            ResourceLocation curTypeId = curType != null ? BuiltInRegistries.ENTITY_TYPE.getKey(curType) : null;
            targetTypeBox.setValue(curTypeId != null ? curTypeId.toString() : "");

            Keyframe sel = BossShowEditorState.getSelectedKeyframeData();
            boolean en = sel != null;
            eventIdBox.setEditable(en);
            subtitleBox.setEditable(en);
            curveBtn.active = en;
            removeKeyframeBtn.active = en;
            if (sel != null) {
                eventIdBox.setValue(sel.eventId() == null ? "" : sel.eventId());
                subtitleBox.setValue(sel.subtitleText() == null ? "" : sel.subtitleText());
                curveBtn.setMessage(Component.translatable(sel.curve().translationKey()));
            } else {
                eventIdBox.setValue("");
                subtitleBox.setValue("");
                curveBtn.setMessage(Component.translatable(Curve.NONE.translationKey()));
            }

            recordBtn.active = BossShowEditorState.hasAnchor();
        } finally {
            suppressResponders = false;
        }
        applyTriggerFieldVisibility(BossShowEditorState.getTrigger());
    }

    public void onKeyframeSelectionChanged() {
        syncFromState();
    }

    private void startRecording() {
        if (!BossShowEditorState.hasAnchor()) return;
        if (!BossShowEditorState.getFrames().isEmpty()) {
            ConfirmScreen confirm = new ConfirmScreen(
                this::onConfirmStartRecording,
                Component.translatable("gui.eca.bossshow.editor.start_rec.title"),
                Component.translatable("gui.eca.bossshow.editor.start_rec.body", BossShowEditorState.frameCount())
            );
            this.minecraft.setScreen(confirm);
        } else {
            doStartRecording();
        }
    }

    private void onConfirmStartRecording(boolean confirmed) {
        if (confirmed) {
            doStartRecording();
        } else {
            this.minecraft.setScreen(new BossShowEditorScreen());
        }
    }

    private void doStartRecording() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        BossShowEditorState.enterRecordingStandby(mc.level.getGameTime());
        mc.setScreen(null);
    }

    private void doSave() {
        ResourceLocation id = BossShowEditorState.getEditingId();
        if (id == null) return;
        ResourceLocation typeId = BossShowEditorState.getTargetType() != null
            ? BuiltInRegistries.ENTITY_TYPE.getKey(BossShowEditorState.getTargetType())
            : null;
        BossShowSaveEditorPacket pkt = new BossShowSaveEditorPacket(
            id, typeId,
            BossShowEditorState.getTrigger(),
            BossShowEditorState.isCinematic(),
            BossShowEditorState.isAllowRepeat(),
            new java.util.ArrayList<>(BossShowEditorState.getFrames()),
            BossShowEditorState.getAnchorYawDeg()
        );
        NetworkHandler.sendToServer(pkt);
        BossShowDefinition snapshot = BossShowEditorState.buildDefinition();
        if (snapshot != null) {
            BossShowEditorState.upsertAvailableDef(snapshot);
        }
        BossShowEditorState.clearDirty();
    }

    private void attemptClose() {
        if (BossShowEditorState.isDirty()) {
            ConfirmScreen confirm = new ConfirmScreen(
                this::onConfirmExit,
                Component.translatable("gui.eca.bossshow.editor.discard.title"),
                Component.translatable("gui.eca.bossshow.editor.discard.body")
            );
            this.minecraft.setScreen(confirm);
        } else {
            backToHome();
        }
    }

    private void backToHome() {
        BossShowEditorState.clearDirty();
        this.minecraft.setScreen(new BossShowEditorHomeScreen());
    }

    private void onConfirmExit(boolean confirmed) {
        if (confirmed) backToHome();
        else this.minecraft.setScreen(new BossShowEditorScreen());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 /* ESC */) {
            if (this.getFocused() instanceof EditBox eb && eb.isFocused()) {
                eb.setFocused(false);
                this.setFocused(null);
                return true;
            }
            attemptClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        ResourceLocation id = BossShowEditorState.getEditingId();
        Component idComp = id != null
            ? Component.literal(id.toString())
            : Component.translatable("gui.eca.bossshow.editor.title.unknown");
        g.drawCenteredString(this.font,
            Component.translatable("gui.eca.bossshow.editor.title", idComp),
            this.width / 2, 12, 0xFFFFFF);

        ResourceLocation typeId = BossShowEditorState.getTargetType() != null
            ? BuiltInRegistries.ENTITY_TYPE.getKey(BossShowEditorState.getTargetType())
            : null;
        Component typeComp = typeId != null
            ? Component.literal(typeId.toString())
            : Component.translatable("gui.eca.bossshow.editor.meta.unknown");
        Component dirtyComp = Component.translatable(
            BossShowEditorState.isDirty() ? "gui.eca.bossshow.editor.unsaved" : "gui.eca.bossshow.editor.clean");
        g.drawCenteredString(this.font,
            Component.translatable("gui.eca.bossshow.editor.meta", typeComp,
                BossShowEditorState.frameCount(), BossShowEditorState.keyframeCount(), dirtyComp),
            this.width / 2, 26, 0xAAAAAA);

        if (keyframeList != null) keyframeList.render(g, mouseX, mouseY, partialTick);

        //右侧 label
        int rightX = this.width / 2 + 8;
        int topY = 46;
        g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.label.event"),
            rightX + 40, topY + 6, 0xCCCCCC, false);
        g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.label.subtitle"),
            rightX + 40, topY + 28, 0xCCCCCC, false);
        g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.label.curve"),
            rightX + 40, topY + 51, 0xCCCCCC, false);

        //时间轴 tick / 区间数值（画在右半区操作按钮上方的空白处）
        int frameCount = BossShowEditorState.frameCount();
        if (frameCount > 0) {
            int rangeOpsY = (this.height - 28) - 46;
            String info = "tick " + BossShowEditorState.getPlayhead() + " / " + (frameCount - 1);
            if (BossShowEditorState.hasValidRange()) {
                info += "   in " + BossShowEditorState.getInPoint()
                    + " → out " + BossShowEditorState.getOutPoint();
            }
            g.drawString(this.font, info, rightX, rangeOpsY - 12, 0xFFAAAAAA, false);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    //=== 关键帧列表 widget ===
    private class KeyframeList extends ObjectSelectionList<KeyframeList.Entry> {
        KeyframeList(Minecraft mc, int width, int height, int top, int bottom, int itemHeight) {
            super(mc, width, height, top, bottom, itemHeight);
        }

        public void rebuild() {
            this.clearEntries();
            List<Frame> frames = BossShowEditorState.getFrames();
            for (int i = 0; i < frames.size(); i++) {
                if (frames.get(i).keyframe() != null) {
                    this.addEntry(new Entry(i));
                }
            }
            int sel = BossShowEditorState.getSelectedKeyframeFrameIndex();
            for (Entry e : this.children()) {
                if (e.frameIndex == sel) {
                    this.setSelected(e);
                    break;
                }
            }
        }

        @Override public int getRowWidth() { return this.width - 12; }
        @Override protected int getScrollbarPosition() { return this.x1 - 6; }

        @Override
        public void setSelected(Entry entry) {
            super.setSelected(entry);
            if (entry != null) {
                BossShowEditorState.setSelectedKeyframeFrameIndex(entry.frameIndex);
                //选中关键帧时把播放头移到该帧，预览相机随之跳转
                BossShowEditorState.setPlayhead(entry.frameIndex);
                onKeyframeSelectionChanged();
            }
        }

        @Override
        public void updateNarration(NarrationElementOutput output) {
            super.updateNarration(output);
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final int frameIndex;
            Entry(int frameIndex) { this.frameIndex = frameIndex; }

            @Override public Component getNarration() { return Component.literal("Keyframe at tick " + frameIndex); }

            // 返回 true 以让 AbstractSelectionList 触发 setFocused→setSelected
            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                return button == 0;
            }

            @Override
            public void render(GuiGraphics g, int entryIdx, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean isHovering, float partialTick) {
                List<Frame> frames = BossShowEditorState.getFrames();
                if (frameIndex < 0 || frameIndex >= frames.size()) return;
                Keyframe kf = frames.get(frameIndex).keyframe();
                if (kf == null) return;
                int color = (KeyframeList.this.getSelected() == this) ? 0xFFFFFF55 : 0xFFFFFFFF;
                Component head = Component.translatable("gui.eca.bossshow.editor.keyframe.row",
                    entryIdx, frameIndex, String.format("%.2f", frameIndex / 20.0),
                    kf.eventId() == null ? "—" : kf.eventId());
                g.drawString(Minecraft.getInstance().font, head, left + 4, top + 4, color, false);
            }
        }
    }

    //=== 时间轴 widget：播放头 + 区间 + 关键帧刻度，可点击/拖动 scrub ===
    private static final class Timeline extends AbstractWidget {
        Timeline(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty());
        }

        private int xToFrame(double mouseX, int frameCount) {
            if (frameCount <= 1) return 0;
            double frac = (mouseX - this.getX()) / (this.getWidth() - 1);
            frac = Math.max(0.0, Math.min(1.0, frac));
            return (int) Math.round(frac * (frameCount - 1));
        }

        private int frameToX(int idx, int frameCount) {
            if (frameCount <= 1) return this.getX();
            return this.getX() + (int) Math.round((idx / (double) (frameCount - 1)) * (this.getWidth() - 1));
        }

        private void seek(double mouseX) {
            if (BossShowEditorState.frameCount() <= 0) return;
            BossShowEditorState.setPlayhead(xToFrame(mouseX, BossShowEditorState.frameCount()));
        }

        @Override
        public void onClick(double mouseX, double mouseY) { seek(mouseX); }

        @Override
        protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) { seek(mouseX); }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x0 = this.getX(), y0 = this.getY(), w = this.getWidth(), h = this.getHeight();
            g.fill(x0, y0, x0 + w, y0 + h, 0xCC101010);

            Font font = Minecraft.getInstance().font;
            int frameCount = BossShowEditorState.frameCount();
            if (frameCount <= 0) {
                g.drawCenteredString(font, Component.translatable("gui.eca.bossshow.editor.timeline.empty"),
                    x0 + w / 2, y0 + (h - font.lineHeight) / 2, 0xFFAAAAAA);
                return;
            }

            //in/out 区间阴影
            if (BossShowEditorState.hasValidRange()) {
                int xi = frameToX(BossShowEditorState.getInPoint(), frameCount);
                int xo = frameToX(BossShowEditorState.getOutPoint(), frameCount);
                g.fill(xi, y0 + 1, xo + 1, y0 + h - 1, 0x553388FF);
            }

            //关键帧刻度
            for (int idx : BossShowEditorState.getKeyframeFrameIndices()) {
                int kx = frameToX(idx, frameCount);
                g.fill(kx, y0 + 1, kx + 1, y0 + h - 1, 0xFFFFDD33);
            }

            //播放头
            int px = frameToX(BossShowEditorState.getPlayhead(), frameCount);
            g.fill(px, y0, px + 1, y0 + h, 0xFFFFFFFF);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE,
                Component.translatable("gui.eca.bossshow.editor.timeline.narration"));
        }
    }
}
