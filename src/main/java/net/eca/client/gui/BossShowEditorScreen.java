package net.eca.client.gui;

import net.eca.network.BossShowSaveEditorPacket;
import net.eca.network.NetworkHandler;
import net.eca.util.bossshow.BossShowDefinition;
import net.eca.util.bossshow.BossShowDefinition.Frame;
import net.eca.util.bossshow.BossShowDefinition.Keyframe;
import net.eca.util.bossshow.BossShowEditorState;
import net.eca.util.bossshow.Curve;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

//BossShow 视频编辑工作台：顶部菜单、实时预览、上下文检查器和多轨时间线。
public final class BossShowEditorScreen extends Screen {

    private static final int TOP_HEIGHT = 24;
    private static final int INSPECTOR_HEIGHT = 66;
    private static final int DEFAULT_TIMELINE_HEIGHT = 76;
    private static final int MIN_TIMELINE_HEIGHT = 55;
    private static final int MIN_PREVIEW_HEIGHT = 80;
    private static final int MENU_HEIGHT = 18;
    private static final int MENU_FILE = 0;
    private static final int MENU_EDIT = 1;
    private static final int MENU_SHOW = 2;
    private static final int MENU_TRACK = 3;
    private static final int MENU_PREVIEW = 4;
    private static final int CONTEXT_WIDTH = 156;
    private static final int CONTEXT_ROW_HEIGHT = 18;
    private static int preferredTimelineHeight = DEFAULT_TIMELINE_HEIGHT;
    private static boolean timelineCollapsed;

    private enum SelectedTrack { CAMERA, EVENT, SUBTITLE }

    private record ContextEntry(Component label, boolean enabled, Runnable action, List<ContextEntry> children) { }

    private BossShowTimelineWidget timeline;
    private EditBox dxBox;
    private EditBox dyBox;
    private EditBox dzBox;
    private EditBox yawBox;
    private EditBox pitchBox;
    private EditBox eventIdBox;
    private EditBox subtitleBox;
    private Button curveBtn;
    private Button addContentBtn;
    private Button removeContentBtn;
    private Button previewBtn;
    private Button freeCameraBtn;
    private Button rangeBtn;
    private int inspectorY;
    private int openDropdown = -1;
    private int dropdownFirstWidgetIndex = -1;
    private int dropdownLastWidgetIndex = -1;
    private final int[] menuPositions = new int[5];
    private SelectedTrack selectedTrack = SelectedTrack.CAMERA;
    private boolean suppressResponders;
    private boolean resizingTimeline;
    private boolean timelineResizeMoved;
    private double timelineResizeStartY;
    private int timelineResizeStartHeight;
    private boolean contextMenuOpen;
    private int contextMenuX;
    private int contextMenuY;
    private int contextSubmenuIndex = -1;
    private List<ContextEntry> contextEntries = List.of();
    private Frame copiedPose;

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
        preferredTimelineHeight = clampTimelineHeight(preferredTimelineHeight);
        int timelineHeight = timelineCollapsed ? 0 : preferredTimelineHeight;
        int timelineY = this.height - timelineHeight - 8;
        inspectorY = timelineY - INSPECTOR_HEIGHT;
        addMenuBar();
        addInspectorWidgets();
        timeline = new BossShowTimelineWidget(8, timelineY, this.width - 16,
            Math.max(MIN_TIMELINE_HEIGHT, timelineHeight),
            this::onTimelineSelection);
        timeline.visible = !timelineCollapsed;
        timeline.active = !timelineCollapsed;
        this.addRenderableWidget(timeline);
        syncFromState();
        BossShowEditorState.setPreviewEnabled(true);
    }

    private void addMenuBar() {
        dropdownFirstWidgetIndex = -1;
        dropdownLastWidgetIndex = -1;
        boolean compact = this.width < 800;
        int menuWidth = compact ? 46 : 58;
        int x = 6;
        x = addMenuButton(x, Component.translatable("gui.eca.bossshow.editor.menu.file"), MENU_FILE, menuWidth);
        x = addMenuButton(x, Component.translatable("gui.eca.bossshow.editor.menu.edit"), MENU_EDIT, menuWidth);
        x = addMenuButton(x, Component.translatable("gui.eca.bossshow.editor.menu.show"), MENU_SHOW, menuWidth);
        x = addMenuButton(x, Component.translatable("gui.eca.bossshow.editor.menu.track"), MENU_TRACK, compact ? 50 : 58);
        x = addMenuButton(x, Component.translatable("gui.eca.bossshow.editor.menu.preview"), MENU_PREVIEW, compact ? 58 : 68);
        ResourceLocation id = BossShowEditorState.getEditingId();
        int idLeft = x + 4;
        int idRight = this.width - 126;
        if (idRight - idLeft >= 80) {
            this.addRenderableWidget(Button.builder(id == null ? Component.empty() : Component.literal(id.toString()), b -> {})
                .bounds(idLeft, 3, idRight - idLeft, MENU_HEIGHT).build());
        }
        this.addRenderableWidget(Button.builder(Component.translatable("gui.eca.bossshow.editor.save"), b -> doSave())
            .bounds(this.width - 122, 2, 56, MENU_HEIGHT).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.eca.bossshow.editor.back"), b -> attemptClose())
            .bounds(this.width - 62, 2, 56, MENU_HEIGHT).build());
        if (openDropdown >= 0) {
            dropdownFirstWidgetIndex = this.children().size();
            addDropdownOptions(openDropdown);
            dropdownLastWidgetIndex = this.children().size() - 1;
        }
    }

    private int addMenuButton(int x, Component label, int menu, int width) {
        menuPositions[menu] = x;
        Component text = label.copy().append(openDropdown == menu ? " ▲" : " ▼");
        this.addRenderableWidget(Button.builder(text, b -> {
            openDropdown = openDropdown == menu ? -1 : menu;
            rebuildWidgetsForDropdown();
        }).bounds(x, 3, width, MENU_HEIGHT).build());
        return x + width + 2;
    }

    private void addDropdownOptions(int menu) {
        int x;
        int y = TOP_HEIGHT + 2;
        switch (menu) {
            case MENU_FILE -> {
                x = menuPositions[MENU_FILE];
                dropdownOption(x, y, 132, "gui.eca.bossshow.editor.menu.record", this::startRecording);
                dropdownOption(x, y + MENU_HEIGHT, 132, "gui.eca.bossshow.editor.menu.save", this::doSave);
                dropdownOption(x, y + MENU_HEIGHT * 2, 132, "gui.eca.bossshow.editor.menu.back", this::attemptClose);
            }
            case MENU_EDIT -> {
                x = menuPositions[MENU_EDIT];
                dropdownOption(x, y, 160, "gui.eca.bossshow.editor.menu.undo", this::undo);
                dropdownOption(x, y + MENU_HEIGHT, 160, "gui.eca.bossshow.editor.menu.redo", this::redo);
                dropdownOption(x, y + MENU_HEIGHT * 2, 160, "gui.eca.bossshow.editor.menu.set_in", this::setIn);
                dropdownOption(x, y + MENU_HEIGHT * 3, 160, "gui.eca.bossshow.editor.menu.set_out", this::setOut);
                dropdownOption(x, y + MENU_HEIGHT * 4, 160, "gui.eca.bossshow.editor.menu.copy",
                    BossShowEditorState.hasValidRange(), () -> {
                    BossShowEditorState.copyRange(); closeDropdown();
                });
                dropdownOption(x, y + MENU_HEIGHT * 5, 160, "gui.eca.bossshow.editor.menu.cut",
                    BossShowEditorState.hasValidRange(), () -> {
                    if (BossShowEditorState.cutRange()) syncFromState(); closeDropdown();
                });
                dropdownOption(x, y + MENU_HEIGHT * 6, 160, "gui.eca.bossshow.editor.menu.paste",
                    BossShowEditorState.hasClipboard(), () -> {
                    if (BossShowEditorState.pasteAtPlayhead()) syncFromState(); closeDropdown();
                });
                dropdownOption(x, y + MENU_HEIGHT * 7, 160, "gui.eca.bossshow.editor.menu.delete_range",
                    BossShowEditorState.hasValidRange(), this::deleteRange);
                dropdownOption(x, y + MENU_HEIGHT * 8, 160, "gui.eca.bossshow.editor.menu.range",
                    BossShowEditorState.hasValidRange(), this::openRangeTransform);
                dropdownOption(x, y + MENU_HEIGHT * 9, 160, "gui.eca.bossshow.editor.context.clear_range",
                    BossShowEditorState.getInPoint() >= 0 || BossShowEditorState.getOutPoint() >= 0, () -> {
                    BossShowEditorState.clearRange(); closeDropdown(); syncFromState();
                });
                dropdownOption(x, y + MENU_HEIGHT * 10, 160, "gui.eca.bossshow.editor.menu.shortcuts", this::openShortcutHelp);
            }
            case MENU_SHOW -> {
                x = menuPositions[MENU_SHOW];
                dropdownOption(x, y, 150, "gui.eca.bossshow.editor.menu.settings", this::openSettings);
                dropdownOption(x, y + MENU_HEIGHT, 150, "gui.eca.bossshow.editor.menu.toggle_cinematic", () -> {
                    BossShowEditorState.setCinematic(!BossShowEditorState.isCinematic()); closeDropdown();
                });
                dropdownOption(x, y + MENU_HEIGHT * 2, 150, "gui.eca.bossshow.editor.menu.toggle_repeat", () -> {
                    BossShowEditorState.setAllowRepeat(!BossShowEditorState.isAllowRepeat()); closeDropdown();
                });
            }
            case MENU_TRACK -> {
                x = menuPositions[MENU_TRACK];
                dropdownOption(x, y, 160, "gui.eca.bossshow.editor.menu.add_content", this::addContent);
                dropdownOption(x, y + MENU_HEIGHT, 160, "gui.eca.bossshow.editor.menu.remove_content", this::removeContent);
                dropdownOption(x, y + MENU_HEIGHT * 2, 160, "gui.eca.bossshow.editor.menu.generate_path",
                    BossShowEditorState.frameCount() > 0, this::openPathGenerator);
            }
            case MENU_PREVIEW -> {
                x = menuPositions[MENU_PREVIEW];
                dropdownOption(x, y, 156, "gui.eca.bossshow.editor.menu.play", this::togglePreview);
                dropdownOption(x, y + MENU_HEIGHT, 156, "gui.eca.bossshow.editor.menu.free_camera", this::armFreeCamera);
                dropdownOption(x, y + MENU_HEIGHT * 2, 156, "gui.eca.bossshow.editor.menu.reset_view", () -> {
                    if (timeline != null) timeline.resetView(); closeDropdown();
                });
            }
            default -> { }
        }
    }

    private void dropdownOption(int x, int y, int width, String key, Runnable action) {
        dropdownOption(x, y, width, key, true, action);
    }

    private void dropdownOption(int x, int y, int width, String key, boolean enabled, Runnable action) {
        Button option = Button.builder(Component.translatable(key), b -> action.run())
            .bounds(x, y, width, MENU_HEIGHT).build();
        option.active = enabled;
        this.addRenderableWidget(option);
    }

    private void addInspectorWidgets() {
        int fieldY = inspectorY + 16;
        int fieldGap = 6;
        int fieldWidth = Math.max(36, (this.width - 16 - fieldGap * 4) / 5);
        int x = 8;
        dxBox = createPoseBox(x, fieldY, fieldWidth, 0); x += fieldWidth + fieldGap;
        dyBox = createPoseBox(x, fieldY, fieldWidth, 1); x += fieldWidth + fieldGap;
        dzBox = createPoseBox(x, fieldY, fieldWidth, 2); x += fieldWidth + fieldGap;
        yawBox = createPoseBox(x, fieldY, fieldWidth, 3); x += fieldWidth + fieldGap;
        pitchBox = createPoseBox(x, fieldY, fieldWidth, 4);
        eventIdBox = new EditBox(this.font, 8, fieldY, this.width - 16, 16,
            Component.translatable("gui.eca.bossshow.editor.label.event"));
        eventIdBox.setMaxLength(128);
        eventIdBox.setResponder(value -> updateSelectedContent(value, true));
        this.addRenderableWidget(eventIdBox);
        subtitleBox = new EditBox(this.font, 8, fieldY, this.width - 16, 16,
            Component.translatable("gui.eca.bossshow.editor.label.subtitle"));
        subtitleBox.setMaxLength(256);
        subtitleBox.setResponder(value -> updateSelectedContent(value, false));
        this.addRenderableWidget(subtitleBox);
        int controlsY = inspectorY + 40;
        int controlsGap = 4;
        int controlsWidth = Math.max(40, (this.width - 16 - controlsGap * 5) / 6);
        int controlsX = 8;
        previewBtn = Button.builder(Component.translatable("gui.eca.bossshow.editor.preview"), b -> togglePreview())
            .bounds(controlsX, controlsY, controlsWidth, 18).build();
        this.addRenderableWidget(previewBtn);
        controlsX += controlsWidth + controlsGap;
        freeCameraBtn = Button.builder(Component.translatable("gui.eca.bossshow.editor.pose_capture"), b -> armFreeCamera())
            .bounds(controlsX, controlsY, controlsWidth, 18).build();
        this.addRenderableWidget(freeCameraBtn);
        controlsX += controlsWidth + controlsGap;
        curveBtn = Button.builder(Component.translatable(Curve.NONE.translationKey()), b -> cycleCurve())
            .bounds(controlsX, controlsY, controlsWidth, 18).build();
        this.addRenderableWidget(curveBtn);
        controlsX += controlsWidth + controlsGap;
        rangeBtn = Button.builder(Component.translatable("gui.eca.bossshow.editor.range_offset"), b -> openRangeTransform())
            .bounds(controlsX, controlsY, controlsWidth, 18).build();
        this.addRenderableWidget(rangeBtn);
        controlsX += controlsWidth + controlsGap;
        addContentBtn = Button.builder(Component.translatable("gui.eca.bossshow.editor.add_content"), b -> addContent())
            .bounds(controlsX, controlsY, controlsWidth, 18).build();
        this.addRenderableWidget(addContentBtn);
        controlsX += controlsWidth + controlsGap;
        removeContentBtn = Button.builder(Component.translatable("gui.eca.bossshow.editor.remove_content"), b -> removeContent())
            .bounds(controlsX, controlsY, controlsWidth, 18).build();
        this.addRenderableWidget(removeContentBtn);
    }

    private EditBox createPoseBox(int x, int y, int width, int component) {
        EditBox box = new EditBox(this.font, x, y, width, 16, Component.empty());
        box.setMaxLength(32);
        box.setResponder(value -> { if (!suppressResponders) applyPoseField(component, value); });
        this.addRenderableWidget(box);
        return box;
    }

    private void onTimelineSelection(BossShowTimelineWidget.Track track, int tick) {
        selectedTrack = track == BossShowTimelineWidget.Track.EVENT ? SelectedTrack.EVENT
            : track == BossShowTimelineWidget.Track.SUBTITLE ? SelectedTrack.SUBTITLE : SelectedTrack.CAMERA;
        syncFromState();
    }

    public void syncFromState() {
        if (dxBox == null) return;
        suppressResponders = true;
        try {
            List<Frame> frames = BossShowEditorState.getFrames();
            int tick = BossShowEditorState.getPlayhead();
            Frame frame = tick >= 0 && tick < frames.size() ? frames.get(tick) : null;
            boolean camera = selectedTrack == SelectedTrack.CAMERA;
            boolean hasFrame = frame != null;
            dxBox.visible = camera; dyBox.visible = camera; dzBox.visible = camera;
            yawBox.visible = camera; pitchBox.visible = camera;
            eventIdBox.visible = selectedTrack == SelectedTrack.EVENT;
            subtitleBox.visible = selectedTrack == SelectedTrack.SUBTITLE;
            if (frame != null) {
                dxBox.setValue(format(frame.dx())); dyBox.setValue(format(frame.dy())); dzBox.setValue(format(frame.dz()));
                yawBox.setValue(format(frame.yaw())); pitchBox.setValue(format(frame.pitch()));
            } else {
                dxBox.setValue(""); dyBox.setValue(""); dzBox.setValue(""); yawBox.setValue(""); pitchBox.setValue("");
            }
            Keyframe keyframe = frame == null ? null : frame.keyframe();
            boolean hasContent = keyframe != null;
            eventIdBox.setValue(cueEvent(tick, keyframe) == null ? "" : cueEvent(tick, keyframe));
            subtitleBox.setValue(cueSubtitle(tick, keyframe) == null ? "" : cueSubtitle(tick, keyframe));
            for (EditBox box : new EditBox[]{dxBox, dyBox, dzBox, yawBox, pitchBox}) box.setEditable(hasFrame && camera);
            eventIdBox.setEditable(hasFrame && selectedTrack == SelectedTrack.EVENT && hasContent);
            subtitleBox.setEditable(hasFrame && selectedTrack == SelectedTrack.SUBTITLE && hasContent);
            curveBtn.active = hasContent && camera;
            curveBtn.setMessage(Component.translatable(hasContent ? keyframe.curve().translationKey() : Curve.NONE.translationKey()));
            addContentBtn.active = hasFrame && !hasContent;
            removeContentBtn.active = hasContent;
            rangeBtn.active = BossShowEditorState.hasValidRange();
            previewBtn.setMessage(Component.translatable(BossShowEditorState.isPreviewPlaying()
                ? "gui.eca.bossshow.editor.preview.stop" : "gui.eca.bossshow.editor.preview"));
            freeCameraBtn.active = hasFrame && !BossShowEditorState.isPreviewPlaying();
        } finally {
            suppressResponders = false;
        }
    }

    private String cueEvent(int tick, Keyframe fallback) {
        for (var cue : BossShowEditorState.getEventCues()) if (cue.tick() == tick) return cue.eventId();
        return fallback == null ? null : fallback.eventId();
    }

    private String cueSubtitle(int tick, Keyframe fallback) {
        for (var cue : BossShowEditorState.getSubtitleCues()) if (cue.tick() == tick) return cue.text();
        return fallback == null ? null : fallback.subtitleText();
    }

    private void updateSelectedContent(String value, boolean event) {
        if (suppressResponders || selectedTrack == SelectedTrack.CAMERA) return;
        Keyframe keyframe = BossShowEditorState.getSelectedKeyframeData();
        if (keyframe == null) return;
        BossShowEditorState.replaceKeyframe(BossShowEditorState.getPlayhead(), event
            ? new Keyframe(value.isEmpty() ? null : value, keyframe.subtitleText(), keyframe.curve())
            : new Keyframe(keyframe.eventId(), value.isEmpty() ? null : value, keyframe.curve()));
    }

    private void applyPoseField(int component, String value) {
        if (selectedTrack != SelectedTrack.CAMERA) return;
        int tick = BossShowEditorState.getPlayhead();
        List<Frame> frames = BossShowEditorState.getFrames();
        if (tick < 0 || tick >= frames.size()) return;
        Frame frame = frames.get(tick);
        try {
            double dx = frame.dx(), dy = frame.dy(), dz = frame.dz();
            float yaw = frame.yaw(), pitch = frame.pitch();
            switch (component) {
                case 0 -> dx = Double.parseDouble(value.trim());
                case 1 -> dy = Double.parseDouble(value.trim());
                case 2 -> dz = Double.parseDouble(value.trim());
                case 3 -> yaw = Float.parseFloat(value.trim());
                case 4 -> pitch = Float.parseFloat(value.trim());
                default -> { return; }
            }
            BossShowEditorState.replaceFramePose(tick, dx, dy, dz, yaw, pitch);
        } catch (NumberFormatException ignored) { }
    }

    private void addContent() { if (BossShowEditorState.addContentAtPlayhead()) syncFromState(); closeDropdown(); }
    private void removeContent() { if (BossShowEditorState.removeKeyframe(BossShowEditorState.getPlayhead())) syncFromState(); closeDropdown(); }

    private void cycleCurve() {
        Keyframe keyframe = BossShowEditorState.getSelectedKeyframeData();
        if (keyframe == null) return;
        BossShowEditorState.replaceKeyframe(BossShowEditorState.getPlayhead(),
            new Keyframe(keyframe.eventId(), keyframe.subtitleText(), keyframe.curve().next()));
        syncFromState();
    }

    private void togglePreview() { BossShowEditorState.togglePreviewPlayback(); closeDropdown(); syncFromState(); }

    private void togglePreviewFromShortcut() {
        if (!BossShowEditorState.isPreviewPlaying() && BossShowEditorState.frameCount() > 0
            && BossShowEditorState.getPlayhead() >= BossShowEditorState.frameCount() - 1) {
            BossShowEditorState.setPlayhead(0);
        }
        togglePreview();
    }

    private void armFreeCamera() {
        closeDropdown();
        if (BossShowEditorState.armPoseCapture()) {
            BossShowEditorState.setPreviewEnabled(false);
            this.minecraft.setScreen(null);
        }
    }

    private void openSettings() { closeDropdown(); this.minecraft.setScreen(new BossShowEditorSettingsScreen()); }
    private void openRangeTransform() { closeDropdown(); if (BossShowEditorState.hasValidRange()) this.minecraft.setScreen(new BossShowRangeTransformScreen()); }
    private void openPathGenerator() { closeDropdown(); if (BossShowEditorState.frameCount() > 0) this.minecraft.setScreen(new BossShowPathGeneratorScreen()); }
    private void setIn() { BossShowEditorState.setInPoint(BossShowEditorState.getPlayhead()); closeDropdown(); syncFromState(); }
    private void setOut() { BossShowEditorState.setOutPoint(BossShowEditorState.getPlayhead()); closeDropdown(); syncFromState(); }
    private void openShortcutHelp() { closeDropdown(); this.minecraft.setScreen(new BossShowShortcutHelpScreen()); }
    private void deleteRange() { if (BossShowEditorState.deleteRange()) syncFromState(); closeDropdown(); }
    private void undo() { if (BossShowEditorState.undo()) syncFromState(); closeDropdown(); }
    private void redo() { if (BossShowEditorState.redo()) syncFromState(); closeDropdown(); }

    private void startRecording() {
        closeDropdown();
        if (!BossShowEditorState.hasAnchor()) return;
        if (BossShowEditorState.frameCount() > 0) {
            this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
                if (confirmed) doStartRecording(); else this.minecraft.setScreen(new BossShowEditorScreen());
            }, Component.translatable("gui.eca.bossshow.editor.start_rec.title"),
                Component.translatable("gui.eca.bossshow.editor.start_rec.body", BossShowEditorState.frameCount())));
        } else doStartRecording();
    }

    private void doStartRecording() {
        if (this.minecraft.level == null) return;
        BossShowEditorState.enterRecordingStandby(this.minecraft.level.getGameTime());
        this.minecraft.setScreen(null);
    }

    private void doSave() {
        ResourceLocation id = BossShowEditorState.getEditingId();
        if (id == null) return;
        ResourceLocation typeId = BossShowEditorState.getTargetType() == null ? null
            : BuiltInRegistries.ENTITY_TYPE.getKey(BossShowEditorState.getTargetType());
        NetworkHandler.sendToServer(new BossShowSaveEditorPacket(id, typeId, BossShowEditorState.getTrigger(),
            BossShowEditorState.isCinematic(), BossShowEditorState.isAllowRepeat(),
            new ArrayList<>(BossShowEditorState.getFrames()), BossShowEditorState.getAnchorYawDeg(),
            new ArrayList<>(BossShowEditorState.getEventCues()), new ArrayList<>(BossShowEditorState.getSubtitleCues())));
        BossShowDefinition snapshot = BossShowEditorState.buildDefinition();
        if (snapshot != null) BossShowEditorState.upsertAvailableDef(snapshot);
        BossShowEditorState.clearDirty();
        closeDropdown();
    }

    private void attemptClose() {
        closeDropdown();
        if (BossShowEditorState.isDirty()) {
            this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
                if (confirmed) backToHome(); else this.minecraft.setScreen(new BossShowEditorScreen());
            }, Component.translatable("gui.eca.bossshow.editor.discard.title"),
                Component.translatable("gui.eca.bossshow.editor.discard.body")));
        } else backToHome();
    }

    private void backToHome() {
        BossShowEditorState.clearDirty();
        this.minecraft.setScreen(new BossShowEditorHomeScreen());
    }

    private void rebuildWidgetsForDropdown() {
        this.clearWidgets();
        init();
    }

    private void closeDropdown() {
        if (openDropdown >= 0) {
            openDropdown = -1;
            rebuildWidgetsForDropdown();
        }
    }

    @Override
    public void removed() {
        BossShowEditorState.setPreviewEnabled(false);
        BossShowEditorState.stopPreviewPlayback();
        super.removed();
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && contextMenuOpen) { closeContextMenu(); return true; }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && openDropdown >= 0) { closeDropdown(); return true; }
        if (this.getFocused() instanceof EditBox editBox) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                editBox.setFocused(false);
                this.setFocused(null);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (hasControlDown()) {
            if (keyCode == GLFW.GLFW_KEY_Z) { undo(); return true; }
            if (keyCode == GLFW.GLFW_KEY_Y) { redo(); return true; }
            if (keyCode == GLFW.GLFW_KEY_C) { BossShowEditorState.copyRange(); closeContextMenu(); return true; }
            if (keyCode == GLFW.GLFW_KEY_X) {
                if (BossShowEditorState.cutRange()) syncFromState(); closeContextMenu(); return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V) {
                if (BossShowEditorState.pasteAtPlayhead()) syncFromState(); closeContextMenu(); return true;
            }
        } else {
            int step = hasShiftDown() ? 10 : 1;
            if (keyCode == GLFW.GLFW_KEY_SPACE) { togglePreviewFromShortcut(); return true; }
            if (keyCode == GLFW.GLFW_KEY_LEFT) { BossShowEditorState.movePlayheadBy(-step); syncFromState(); return true; }
            if (keyCode == GLFW.GLFW_KEY_RIGHT) { BossShowEditorState.movePlayheadBy(step); syncFromState(); return true; }
            if (keyCode == GLFW.GLFW_KEY_HOME) { BossShowEditorState.setPlayhead(0); syncFromState(); return true; }
            if (keyCode == GLFW.GLFW_KEY_END) {
                BossShowEditorState.setPlayhead(Math.max(0, BossShowEditorState.frameCount() - 1)); syncFromState(); return true;
            }
            if (keyCode == GLFW.GLFW_KEY_I) { setIn(); return true; }
            if (keyCode == GLFW.GLFW_KEY_O) { setOut(); return true; }
            if (keyCode == GLFW.GLFW_KEY_DELETE) { deleteSelectedTrackContent(); return true; }
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { attemptClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (contextMenuOpen) {
            if (button == 0) return handleContextMenuClick(mouseX, mouseY);
            closeContextMenu();
        }
        if (button == 1 && timeline != null && timeline.selectAt(mouseX, mouseY)) {
            openTimelineContextMenu((int) mouseX, (int) mouseY);
            return true;
        }
        if (button == 0 && isOverTimelineHandle(mouseX, mouseY)) {
            resizingTimeline = true;
            timelineResizeMoved = false;
            timelineResizeStartY = mouseY;
            timelineResizeStartHeight = timelineCollapsed ? MIN_TIMELINE_HEIGHT : preferredTimelineHeight;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && resizingTimeline) {
            int delta = (int) Math.round(timelineResizeStartY - mouseY);
            if (Math.abs(delta) >= 2) timelineResizeMoved = true;
            if (timelineResizeMoved) {
                timelineCollapsed = false;
                applyTimelineHeight(timelineResizeStartHeight + delta);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && resizingTimeline) {
            resizingTimeline = false;
            if (!timelineResizeMoved) {
                timelineCollapsed = !timelineCollapsed;
                applyTimelineHeight(preferredTimelineHeight);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int timelineY = timeline == null ? this.height - preferredTimelineHeight - 8 : timeline.getY();
        int previewBottom = Math.max(TOP_HEIGHT + 20, inspectorY - 4);
        g.fill(0, 0, this.width, TOP_HEIGHT, 0xEE171922);
        g.fill(0, inspectorY, this.width, timelineY, 0xCC171A22);
        g.fill(0, timelineY - 1, this.width, this.height, 0xEE0E1016);
        g.renderOutline(6, TOP_HEIGHT + 2, this.width - 12, previewBottom - TOP_HEIGHT - 2, 0x553F4657);
        Component dirty = Component.translatable(BossShowEditorState.isDirty()
            ? "gui.eca.bossshow.editor.unsaved" : "gui.eca.bossshow.editor.clean");
        g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.tick",
            BossShowEditorState.getPlayhead(), BossShowEditorState.frameCount(), dirty), 8, TOP_HEIGHT + 4, 0xFFB8BFCE, false);
        g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.track." + selectedTrack.name().toLowerCase(Locale.ROOT)),
            this.width / 2 - 45, TOP_HEIGHT + 4, 0xFFB8BFCE, false);
        drawInspectorLabels(g);
        super.render(g, mouseX, mouseY, partialTick);
        drawTimelineHandle(g, timelineY, mouseX, mouseY);
        if (openDropdown >= 0) {
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 400.0F);
            drawDropdown(g, mouseX, mouseY, partialTick);
            g.pose().popPose();
        }
        if (contextMenuOpen) {
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 500.0F);
            renderContextMenu(g, mouseX, mouseY);
            g.pose().popPose();
        }
    }

    private void drawDropdown(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (dropdownFirstWidgetIndex < 0 || dropdownLastWidgetIndex < dropdownFirstWidgetIndex) return;
        List<? extends GuiEventListener> kids = this.children();
        if (dropdownFirstWidgetIndex >= kids.size()) return;
        AbstractWidget first = kids.get(dropdownFirstWidgetIndex) instanceof AbstractWidget w ? w : null;
        if (first == null) return;
        int left = first.getX() - 3, top = first.getY() - 2;
        int right = first.getX() + first.getWidth() + 3, bottom = first.getY() + first.getHeight() + 2;
        for (int i = dropdownFirstWidgetIndex + 1; i <= dropdownLastWidgetIndex && i < kids.size(); i++) {
            if (kids.get(i) instanceof AbstractWidget w) {
                left = Math.min(left, w.getX() - 3); top = Math.min(top, w.getY() - 2);
                right = Math.max(right, w.getX() + w.getWidth() + 3); bottom = Math.max(bottom, w.getY() + w.getHeight() + 2);
            }
        }
        g.fill(left, top, right, bottom, 0xFF2C2F39);
        g.renderOutline(left, top, right - left, bottom - top, 0xFF555B6A);
        for (int i = dropdownFirstWidgetIndex; i <= dropdownLastWidgetIndex && i < kids.size(); i++) {
            if (kids.get(i) instanceof AbstractWidget w) w.render(g, mouseX, mouseY, partialTick);
        }
    }

    private void drawInspectorLabels(GuiGraphics g) {
        if (selectedTrack == SelectedTrack.CAMERA) {
            int fieldGap = 6;
            int fieldWidth = Math.max(36, (this.width - 16 - fieldGap * 4) / 5);
            int x = 8;
            for (String label : new String[]{"dx", "dy", "dz", "yaw", "pitch"}) {
                g.drawString(this.font, Component.literal(label), x, inspectorY + 4, 0xFF858C9B, false);
                x += fieldWidth + fieldGap;
            }
        } else if (selectedTrack == SelectedTrack.EVENT) {
            g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.label.event"),
                8, inspectorY + 4, 0xFF858C9B, false);
        } else if (selectedTrack == SelectedTrack.SUBTITLE) {
            g.drawString(this.font, Component.translatable("gui.eca.bossshow.editor.label.subtitle"),
                8, inspectorY + 4, 0xFF858C9B, false);
        }
    }

    private void openTimelineContextMenu(int mouseX, int mouseY) {
        if (openDropdown >= 0) closeDropdown();
        contextEntries = buildContextEntries();
        int menuHeight = contextEntries.size() * CONTEXT_ROW_HEIGHT + 4;
        contextMenuX = Math.max(4, Math.min(mouseX, this.width - CONTEXT_WIDTH - 4));
        contextMenuY = Math.max(TOP_HEIGHT + 2, Math.min(mouseY, this.height - menuHeight - 4));
        contextSubmenuIndex = -1;
        contextMenuOpen = true;
    }

    private List<ContextEntry> buildContextEntries() {
        List<ContextEntry> entries = new ArrayList<>();
        Keyframe keyframe = BossShowEditorState.getSelectedKeyframeData();
        if (selectedTrack == SelectedTrack.CAMERA) {
            entries.add(contextAction("gui.eca.bossshow.editor.context.copy_pose", true, this::copyCurrentPose));
            entries.add(contextAction("gui.eca.bossshow.editor.context.paste_pose", copiedPose != null, this::pasteCopiedPose));
            entries.add(contextAction("gui.eca.bossshow.editor.context.capture_pose", true, this::armFreeCamera));
            entries.add(contextAction("gui.eca.bossshow.editor.menu.generate_path",
                BossShowEditorState.frameCount() > 0, this::openPathGenerator));
            List<ContextEntry> curves = new ArrayList<>();
            for (Curve curve : Curve.values()) {
                curves.add(new ContextEntry(Component.translatable(curve.translationKey()), true,
                    () -> setCurve(curve), List.of()));
            }
            entries.add(contextSubmenu("gui.eca.bossshow.editor.context.curve", curves));
        } else if (selectedTrack == SelectedTrack.EVENT) {
            entries.add(contextAction("gui.eca.bossshow.editor.context.edit_event", true,
                () -> focusTrackContent(true)));
            entries.add(contextAction("gui.eca.bossshow.editor.context.delete_event",
                keyframe != null && keyframe.eventId() != null, () -> deleteTrackContent(true)));
        } else {
            entries.add(contextAction("gui.eca.bossshow.editor.context.edit_subtitle", true,
                () -> focusTrackContent(false)));
            entries.add(contextAction("gui.eca.bossshow.editor.context.delete_subtitle",
                keyframe != null && keyframe.subtitleText() != null, () -> deleteTrackContent(false)));
        }
        entries.add(contextAction("gui.eca.bossshow.editor.menu.set_in", true, this::setIn));
        entries.add(contextAction("gui.eca.bossshow.editor.menu.set_out", true, this::setOut));
        entries.add(contextAction("gui.eca.bossshow.editor.menu.paste", BossShowEditorState.hasClipboard(), () -> {
            if (BossShowEditorState.pasteAtPlayhead()) syncFromState();
        }));
        List<ContextEntry> selection = List.of(
            contextAction("gui.eca.bossshow.editor.menu.copy", BossShowEditorState.hasValidRange(),
                BossShowEditorState::copyRange),
            contextAction("gui.eca.bossshow.editor.menu.cut", BossShowEditorState.hasValidRange(), () -> {
                if (BossShowEditorState.cutRange()) syncFromState();
            }),
            contextAction("gui.eca.bossshow.editor.menu.delete_range", BossShowEditorState.hasValidRange(), () -> {
                if (BossShowEditorState.deleteRange()) syncFromState();
            }),
            contextAction("gui.eca.bossshow.editor.menu.range", BossShowEditorState.hasValidRange(), this::openRangeTransform),
            contextAction("gui.eca.bossshow.editor.context.clear_range",
                BossShowEditorState.getInPoint() >= 0 || BossShowEditorState.getOutPoint() >= 0,
                BossShowEditorState::clearRange)
        );
        entries.add(contextSubmenu("gui.eca.bossshow.editor.context.selection", selection));
        return entries;
    }

    private ContextEntry contextAction(String key, boolean enabled, Runnable action) {
        return new ContextEntry(Component.translatable(key), enabled, action, List.of());
    }

    private ContextEntry contextSubmenu(String key, List<ContextEntry> children) {
        return new ContextEntry(Component.translatable(key), !children.isEmpty(), null, children);
    }

    private void copyCurrentPose() {
        int tick = BossShowEditorState.getPlayhead();
        List<Frame> frames = BossShowEditorState.getFrames();
        if (tick >= 0 && tick < frames.size()) copiedPose = frames.get(tick);
    }

    private void pasteCopiedPose() {
        if (copiedPose == null) return;
        BossShowEditorState.replaceFramePose(BossShowEditorState.getPlayhead(), copiedPose.dx(), copiedPose.dy(),
            copiedPose.dz(), copiedPose.yaw(), copiedPose.pitch());
        syncFromState();
    }

    private void focusTrackContent(boolean event) {
        if (BossShowEditorState.getSelectedKeyframeData() == null) BossShowEditorState.addContentAtPlayhead();
        syncFromState();
        EditBox box = event ? eventIdBox : subtitleBox;
        box.setFocused(true);
        this.setFocused(box);
    }

    private void deleteSelectedTrackContent() {
        if (selectedTrack == SelectedTrack.EVENT) deleteTrackContent(true);
        else if (selectedTrack == SelectedTrack.SUBTITLE) deleteTrackContent(false);
    }

    private void deleteTrackContent(boolean event) {
        Keyframe keyframe = BossShowEditorState.getSelectedKeyframeData();
        if (keyframe == null) return;
        String eventId = event ? null : keyframe.eventId();
        String subtitle = event ? keyframe.subtitleText() : null;
        if (eventId == null && subtitle == null && keyframe.curve() == Curve.NONE) {
            BossShowEditorState.removeKeyframe(BossShowEditorState.getPlayhead());
        } else {
            BossShowEditorState.replaceKeyframe(BossShowEditorState.getPlayhead(),
                new Keyframe(eventId, subtitle, keyframe.curve()));
        }
        syncFromState();
    }

    private void setCurve(Curve curve) {
        Keyframe keyframe = BossShowEditorState.getSelectedKeyframeData();
        if (keyframe == null && BossShowEditorState.addContentAtPlayhead()) {
            keyframe = BossShowEditorState.getSelectedKeyframeData();
        }
        if (keyframe == null) return;
        BossShowEditorState.replaceKeyframe(BossShowEditorState.getPlayhead(),
            new Keyframe(keyframe.eventId(), keyframe.subtitleText(), curve));
        syncFromState();
    }

    private boolean handleContextMenuClick(double mouseX, double mouseY) {
        if (contextSubmenuIndex >= 0 && contextSubmenuIndex < contextEntries.size()) {
            ContextEntry parent = contextEntries.get(contextSubmenuIndex);
            int childIndex = menuIndexAt(mouseX, mouseY, submenuX(), submenuY(parent), parent.children().size());
            if (childIndex >= 0) {
                ContextEntry child = parent.children().get(childIndex);
                if (child.enabled() && child.action() != null) child.action().run();
                closeContextMenu();
                return true;
            }
        }
        int rootIndex = menuIndexAt(mouseX, mouseY, contextMenuX, contextMenuY, contextEntries.size());
        if (rootIndex >= 0) {
            ContextEntry entry = contextEntries.get(rootIndex);
            if (!entry.children().isEmpty()) {
                contextSubmenuIndex = rootIndex;
            } else if (entry.enabled() && entry.action() != null) {
                entry.action().run();
                closeContextMenu();
            }
            return true;
        }
        closeContextMenu();
        return true;
    }

    private void renderContextMenu(GuiGraphics g, int mouseX, int mouseY) {
        int rootHover = menuIndexAt(mouseX, mouseY, contextMenuX, contextMenuY, contextEntries.size());
        if (rootHover >= 0) {
            contextSubmenuIndex = contextEntries.get(rootHover).children().isEmpty() ? -1 : rootHover;
        }
        drawContextEntries(g, contextMenuX, contextMenuY, contextEntries, rootHover);
        if (contextSubmenuIndex >= 0 && contextSubmenuIndex < contextEntries.size()) {
            ContextEntry parent = contextEntries.get(contextSubmenuIndex);
            int x = submenuX();
            int y = submenuY(parent);
            int childHover = menuIndexAt(mouseX, mouseY, x, y, parent.children().size());
            drawContextEntries(g, x, y, parent.children(), childHover);
        }
    }

    private void drawContextEntries(GuiGraphics g, int x, int y, List<ContextEntry> entries, int hoveredIndex) {
        int height = entries.size() * CONTEXT_ROW_HEIGHT + 4;
        g.fill(x, y, x + CONTEXT_WIDTH, y + height, 0xFF2C2F39);
        g.renderOutline(x, y, CONTEXT_WIDTH, height, 0xFF555B6A);
        for (int i = 0; i < entries.size(); i++) {
            ContextEntry entry = entries.get(i);
            int rowY = y + 2 + i * CONTEXT_ROW_HEIGHT;
            if (i == hoveredIndex && entry.enabled()) g.fill(x + 2, rowY, x + CONTEXT_WIDTH - 2, rowY + CONTEXT_ROW_HEIGHT, 0xFF5A6070);
            Component label = entry.children().isEmpty() ? entry.label() : entry.label().copy().append("  ▶");
            g.drawString(this.font, label, x + 6, rowY + 5, entry.enabled() ? 0xFFF1F3F7 : 0xFF777C87, false);
        }
    }

    private int menuIndexAt(double mouseX, double mouseY, int x, int y, int count) {
        if (mouseX < x || mouseX >= x + CONTEXT_WIDTH || mouseY < y + 2
            || mouseY >= y + 2 + count * CONTEXT_ROW_HEIGHT) return -1;
        return (int) ((mouseY - y - 2) / CONTEXT_ROW_HEIGHT);
    }

    private int submenuX() {
        return contextMenuX + CONTEXT_WIDTH * 2 + 2 <= this.width
            ? contextMenuX + CONTEXT_WIDTH + 2 : contextMenuX - CONTEXT_WIDTH - 2;
    }

    private int submenuY(ContextEntry parent) {
        int preferredY = contextMenuY + 2 + contextSubmenuIndex * CONTEXT_ROW_HEIGHT;
        int height = parent.children().size() * CONTEXT_ROW_HEIGHT + 4;
        return Math.max(TOP_HEIGHT + 2, Math.min(preferredY, this.height - height - 4));
    }

    private void closeContextMenu() {
        contextMenuOpen = false;
        contextSubmenuIndex = -1;
        contextEntries = List.of();
    }

    private void applyTimelineHeight(int requestedHeight) {
        preferredTimelineHeight = clampTimelineHeight(requestedHeight);
        int effectiveHeight = timelineCollapsed ? 0 : preferredTimelineHeight;
        int newTimelineY = this.height - effectiveHeight - 8;
        int newInspectorY = newTimelineY - INSPECTOR_HEIGHT;
        int deltaY = newInspectorY - inspectorY;
        for (AbstractWidget widget : new AbstractWidget[]{dxBox, dyBox, dzBox, yawBox, pitchBox,
            eventIdBox, subtitleBox, curveBtn, addContentBtn, removeContentBtn, previewBtn, freeCameraBtn, rangeBtn}) {
            widget.setY(widget.getY() + deltaY);
        }
        inspectorY = newInspectorY;
        timeline.setY(newTimelineY);
        timeline.setHeight(Math.max(MIN_TIMELINE_HEIGHT, effectiveHeight));
        timeline.visible = !timelineCollapsed;
        timeline.active = !timelineCollapsed;
    }

    private int clampTimelineHeight(int requestedHeight) {
        int maximum = Math.max(MIN_TIMELINE_HEIGHT,
            this.height - TOP_HEIGHT - INSPECTOR_HEIGHT - MIN_PREVIEW_HEIGHT - 8);
        return Math.max(MIN_TIMELINE_HEIGHT, Math.min(requestedHeight, maximum));
    }

    private boolean isOverTimelineHandle(double mouseX, double mouseY) {
        int timelineY = timeline == null ? this.height - 8 : timeline.getY();
        return mouseX >= this.width / 2.0 - 24 && mouseX <= this.width / 2.0 + 24
            && mouseY >= timelineY - 8 && mouseY <= timelineY + 5;
    }

    private void drawTimelineHandle(GuiGraphics g, int timelineY, int mouseX, int mouseY) {
        int left = this.width / 2 - 24;
        int top = timelineY - 8;
        boolean hovered = isOverTimelineHandle(mouseX, mouseY);
        g.fill(8, timelineY - 2, this.width - 8, timelineY, 0xFF454A58);
        g.fill(left, top, left + 48, top + 12, hovered || resizingTimeline ? 0xFF555B6A : 0xFF303541);
        g.renderOutline(left, top, 48, 12, 0xFF747B8D);
        g.drawCenteredString(this.font, timelineCollapsed ? "▲" : "▼", this.width / 2, top + 2, 0xFFE1E5EE);
    }

    private static String format(double value) { return String.format(Locale.ROOT, "%.4f", value); }
}
