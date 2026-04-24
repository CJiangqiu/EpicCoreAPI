package net.eca.client.gui;

import net.eca.network.BossShowSaveEditorPacket;
import net.eca.network.NetworkHandler;
import net.eca.util.bossshow.BossShowDefinition;
import net.eca.util.bossshow.BossShowDefinition.Marker;
import net.eca.util.bossshow.BossShowEditorState;
import net.eca.util.bossshow.Curve;
import net.eca.util.bossshow.Trigger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.List;

//BossShow 编辑器：trigger + cinematic + marker 列表 + 录制控制
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
    private MarkerList markerList;
    private Button removeMarkerBtn;

    //右侧 marker 编辑
    private EditBox markerEventBox;
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

        //=== Row 1: target_type label + EditBox（空 = 不绑定实体类型，Range 触发将不生效） ===
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

        //=== Row 2: trigger button + 动态字段（Range 时为 radius，Custom 时为 eventName） ===
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

        //=== marker 列表 ===
        int listTop = topY + 76;
        int listBottom = bottomBarY - 30;
        markerList = new MarkerList(this.minecraft, leftW, listBottom - listTop, listTop, listBottom, 18);
        markerList.setLeftPos(leftX);
        markerList.setRenderBackground(false);
        markerList.setRenderTopAndBottom(false);
        this.addWidget(markerList);
        //注意：rebuild 会触发 setSelected → syncFromState，必须等右侧 widget 全部就绪后再调用

        removeMarkerBtn = Button.builder(Component.translatable("gui.eca.bossshow.editor.remove_marker"), b -> {
            int idx = BossShowEditorState.getSelectedMarker();
            if (BossShowEditorState.removeMarker(idx)) {
                rebuildMarkerList();
                syncFromState();
            }
        }).bounds(leftX, listBottom + 4, 130, 20).build();
        this.addRenderableWidget(removeMarkerBtn);

        //=== 右侧：选中 marker 编辑 ===
        markerEventBox = new EditBox(this.font, rightX + LABEL_W + 40, topY + 1, FIELD_W, 16,
            Component.translatable("gui.eca.bossshow.editor.label.event"));
        markerEventBox.setMaxLength(64);
        markerEventBox.setResponder(s -> {
            if (suppressResponders) return;
            int idx = BossShowEditorState.getSelectedMarker();
            Marker m = BossShowEditorState.getSelectedMarkerObj();
            if (m == null) return;
            BossShowEditorState.replaceMarker(idx,
                new Marker(m.tickOffset(), s.isEmpty() ? null : s, m.subtitleText(), m.curve()));
        });
        this.addRenderableWidget(markerEventBox);

        subtitleBox = new EditBox(this.font, rightX + LABEL_W + 40, topY + 23, FIELD_W, 16,
            Component.translatable("gui.eca.bossshow.editor.label.subtitle"));
        subtitleBox.setMaxLength(256);
        subtitleBox.setResponder(s -> {
            if (suppressResponders) return;
            int idx = BossShowEditorState.getSelectedMarker();
            Marker m = BossShowEditorState.getSelectedMarkerObj();
            if (m == null) return;
            BossShowEditorState.replaceMarker(idx,
                new Marker(m.tickOffset(), m.eventId(), s.isEmpty() ? null : s, m.curve()));
        });
        this.addRenderableWidget(subtitleBox);

        //=== curve 行 ===
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

        rebuildMarkerList();
        syncFromState();
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

    //radiusBox 与 customEventNameBox 互斥；按当前 trigger 类型决定哪一个可见可编辑
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
        int idx = BossShowEditorState.getSelectedMarker();
        Marker m = BossShowEditorState.getSelectedMarkerObj();
        if (m == null) return;
        Curve next = m.curve().next();
        BossShowEditorState.replaceMarker(idx, new Marker(m.tickOffset(), m.eventId(), m.subtitleText(), next));
        curveBtn.setMessage(Component.translatable(next.translationKey()));
    }

    private void rebuildMarkerList() {
        if (markerList == null) return;
        markerList.rebuild();
    }

    public void syncFromState() {
        if (triggerTypeBtn == null || markerEventBox == null || subtitleBox == null || removeMarkerBtn == null || curveBtn == null) return;
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

            Marker sel = BossShowEditorState.getSelectedMarkerObj();
            boolean en = sel != null;
            markerEventBox.setEditable(en);
            subtitleBox.setEditable(en);
            curveBtn.active = en;
            removeMarkerBtn.active = en;
            if (sel != null) {
                markerEventBox.setValue(sel.eventId() == null ? "" : sel.eventId());
                subtitleBox.setValue(sel.subtitleText() == null ? "" : sel.subtitleText());
                curveBtn.setMessage(Component.translatable(sel.curve().translationKey()));
            } else {
                markerEventBox.setValue("");
                subtitleBox.setValue("");
                curveBtn.setMessage(Component.translatable(Curve.NONE.translationKey()));
            }

            recordBtn.active = BossShowEditorState.hasAnchor();
        } finally {
            suppressResponders = false;
        }
        applyTriggerFieldVisibility(BossShowEditorState.getTrigger());
    }

    public void onMarkerSelectionChanged() {
        syncFromState();
    }

    private void startRecording() {
        if (!BossShowEditorState.hasAnchor()) return;
        if (!BossShowEditorState.getSamples().isEmpty()) {
            ConfirmScreen confirm = new ConfirmScreen(
                this::onConfirmStartRecording,
                Component.translatable("gui.eca.bossshow.editor.start_rec.title"),
                Component.translatable("gui.eca.bossshow.editor.start_rec.body", BossShowEditorState.sampleCount())
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
        //进入待录制 standby：HUD 显示，等用户按 J 才真正开始捕获
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
            new java.util.ArrayList<>(BossShowEditorState.getSamples()),
            new java.util.ArrayList<>(BossShowEditorState.getMarkers()),
            BossShowEditorState.getAnchorYawDeg()
        );
        NetworkHandler.sendToServer(pkt);
        //乐观更新本地 Home 列表缓存，免去返回 Home 时还得等服务端重发
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
                BossShowEditorState.sampleCount(), BossShowEditorState.getMarkers().size(), dirtyComp),
            this.width / 2, 26, 0xAAAAAA);

        if (markerList != null) markerList.render(g, mouseX, mouseY, partialTick);

        //右侧 label
        int rightX = this.width / 2 + 8;
        int topY = 46;
        g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.label.event"),
            rightX + 40, topY + 6, 0xCCCCCC, false);
        g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.label.subtitle"),
            rightX + 40, topY + 28, 0xCCCCCC, false);
        g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.label.curve"),
            rightX + 40, topY + 51, 0xCCCCCC, false);

        super.render(g, mouseX, mouseY, partialTick);
    }

    //=== marker 列表 widget ===
    private class MarkerList extends ObjectSelectionList<MarkerList.Entry> {
        MarkerList(Minecraft mc, int width, int height, int top, int bottom, int itemHeight) {
            super(mc, width, height, top, bottom, itemHeight);
        }

        public void rebuild() {
            this.clearEntries();
            List<Marker> markers = BossShowEditorState.getMarkers();
            for (int i = 0; i < markers.size(); i++) {
                this.addEntry(new Entry(i));
            }
            int sel = BossShowEditorState.getSelectedMarker();
            if (sel >= 0 && sel < this.children().size()) {
                this.setSelected(this.children().get(sel));
            }
        }

        @Override public int getRowWidth() { return this.width - 12; }
        @Override protected int getScrollbarPosition() { return this.x1 - 6; }

        @Override
        public void setSelected(Entry entry) {
            super.setSelected(entry);
            if (entry != null) {
                BossShowEditorState.setSelectedMarker(entry.index);
                onMarkerSelectionChanged();
            }
        }

        @Override
        public void updateNarration(NarrationElementOutput output) {
            super.updateNarration(output);
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final int index;
            Entry(int index) { this.index = index; }

            @Override public Component getNarration() { return Component.literal("Marker " + index); }

            // 返回 true 以让 AbstractSelectionList 触发 setFocused→setSelected；默认实现返回 false 会导致点击不生效
            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                return button == 0;
            }

            @Override
            public void render(GuiGraphics g, int entryIdx, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean isHovering, float partialTick) {
                List<Marker> ms = BossShowEditorState.getMarkers();
                if (index < 0 || index >= ms.size()) return;
                Marker m = ms.get(index);
                int color = (MarkerList.this.getSelected() == this) ? 0xFFFFFF55 : 0xFFFFFFFF;
                Component head = Component.translatable("gui.eca.bossshow.editor.marker.row",
                    index, m.tickOffset(), String.format("%.2f", m.tickOffset() / 20.0),
                    m.eventId() == null ? "—" : m.eventId());
                g.drawString(Minecraft.getInstance().font, head, left + 4, top + 4, color, false);
            }
        }
    }
}
