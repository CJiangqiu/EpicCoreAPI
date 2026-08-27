package net.eca.util.bossshow;

import net.eca.util.bossshow.BossShowDefinition.Frame;
import net.eca.util.bossshow.BossShowDefinition.EventCue;
import net.eca.util.bossshow.BossShowDefinition.Keyframe;
import net.eca.util.bossshow.BossShowDefinition.SubtitleCue;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("removal")
//客户端 BossShow 编辑器状态单例。仅在客户端逻辑线程访问。
public final class BossShowEditorState {

    private static final int MAX_UNDO_SNAPSHOTS = 128;

    public enum RecState { IDLE, RECORDING, PAUSED }

    private static boolean active = false;
    private static ResourceLocation editingId = null;
    private static EntityType<?> targetType = null;
    private static Trigger trigger = new Trigger.Custom("");
    private static boolean cinematic = true;
    private static boolean allowRepeat = false;

    //工作副本：单条帧序列，关键帧内嵌于 Frame
    private static final ArrayList<Frame> workingFrames = new ArrayList<>();
    private static final ArrayList<EventCue> workingEventCues = new ArrayList<>();
    private static final ArrayList<SubtitleCue> workingSubtitleCues = new ArrayList<>();
    //当前选中的关键帧所在的帧下标（-1 = 无选中）
    private static int selectedKeyframeFrameIndex = -1;
    private static boolean dirty = false;

    //Home 缓存
    private static final ArrayList<BossShowDefinition> availableDefs = new ArrayList<>();

    //=== 选择模式 ===
    public enum SelectionKind { NONE, CREATE_NEW, PLAY }
    private static SelectionKind selectionKind = SelectionKind.NONE;
    private static ResourceLocation pendingPlayDefId = null;
    private static UUID hoveredEntityUuid = null;

    //=== 锚点 ===
    private static UUID anchorEntityUuid = null;
    private static double anchorX, anchorY, anchorZ;
    private static float anchorYawDeg = 0f;
    private static boolean anchorValid = false;

    //=== 录制状态 ===
    private static RecState recState = RecState.IDLE;
    private static long recordingStartTick = 0L;
    //备份用于 ESC 取消还原
    private static final ArrayList<Frame> backupFrames = new ArrayList<>();

    //=== 时间轴编辑（瞬态：不持久化、不入网络包）===
    private static int playhead = 0;
    private static int inPoint = -1;
    private static int outPoint = -1;
    //剪贴板仅在同一演出内有效，enter/beginSession/exit 时清空
    private static final ArrayList<Frame> clipboard = new ArrayList<>();
    //编辑快照仅在当前会话内有效。
    private static final Deque<List<Frame>> undoStack = new ArrayDeque<>();
    private static final Deque<List<Frame>> redoStack = new ArrayDeque<>();
    //仅当编辑器 Screen 打开时为 true（由 Screen init/removed 切换）
    private static boolean previewEnabled = false;
    private static final BossShowPose previewPose = new BossShowPose();
    private static boolean poseCaptureArmed = false;
    private static int poseCaptureFrame = -1;
    private static boolean previewPlaying = false;
    private static double previewCursor = 0.0;

    private BossShowEditorState() {}

    //=== Session ===
    public static void beginSession(Collection<BossShowDefinition> available) {
        active = true;
        editingId = null;
        targetType = null;
        trigger = new Trigger.Custom("");
        cinematic = true;
        allowRepeat = false;
        workingFrames.clear();
        workingEventCues.clear();
        workingSubtitleCues.clear();
        selectedKeyframeFrameIndex = -1;
        dirty = false;
        availableDefs.clear();
        if (available != null) availableDefs.addAll(available);
        recState = RecState.IDLE;
        recordingStartTick = 0L;
        backupFrames.clear();
        resetTimelineEditing();
        clipboard.clear();
        undoStack.clear();
        redoStack.clear();
        poseCaptureArmed = false;
        poseCaptureFrame = -1;
        previewPlaying = false;
    }

    public static void enter(BossShowDefinition def) {
        editingId = def.id();
        targetType = def.targetType();
        trigger = def.trigger();
        cinematic = def.cinematic();
        allowRepeat = def.allowRepeat();
        workingFrames.clear();
        workingFrames.addAll(def.frames());
        workingEventCues.clear();
        workingEventCues.addAll(def.eventCues());
        workingSubtitleCues.clear();
        workingSubtitleCues.addAll(def.subtitleCues());
        mergeContentCuesIntoFrames();
        selectedKeyframeFrameIndex = findFirstKeyframeIndex();
        dirty = false;
        active = true;
        //def 已有帧时，新录制的帧必须复用同一 anchor yaw，否则坐标系错乱
        if (!def.frames().isEmpty()) {
            anchorYawDeg = def.anchorYawDeg();
        }
        recState = RecState.IDLE;
        recordingStartTick = 0L;
        backupFrames.clear();
        resetTimelineEditing();
        clipboard.clear();
        undoStack.clear();
        redoStack.clear();
        poseCaptureArmed = false;
        poseCaptureFrame = -1;
        previewPlaying = false;
    }

    public static BossShowDefinition createBlank(ResourceLocation id, EntityType<?> type) {
        return new BossShowDefinition(id, type, new Trigger.Custom(""), true, false,
            new ArrayList<>(), BossShowDefinition.Source.CONFIG, 0f);
    }

    public static List<BossShowDefinition> getAvailableDefs() {
        return Collections.unmodifiableList(availableDefs);
    }

    public static void upsertAvailableDef(BossShowDefinition def) {
        if (def == null || def.id() == null) return;
        for (int i = 0; i < availableDefs.size(); i++) {
            if (def.id().equals(availableDefs.get(i).id())) {
                availableDefs.set(i, def);
                return;
            }
        }
        availableDefs.add(def);
    }

    public static void exit() {
        active = false;
        editingId = null;
        targetType = null;
        trigger = new Trigger.Custom("");
        cinematic = true;
        allowRepeat = false;
        workingFrames.clear();
        workingEventCues.clear();
        workingSubtitleCues.clear();
        selectedKeyframeFrameIndex = -1;
        dirty = false;
        availableDefs.clear();
        clearAnchor();
        recState = RecState.IDLE;
        backupFrames.clear();
        resetTimelineEditing();
        clipboard.clear();
        undoStack.clear();
        redoStack.clear();
        poseCaptureArmed = false;
        poseCaptureFrame = -1;
        previewPlaying = false;
    }

    //=== 锚点 ===
    public static void setAnchor(UUID uuid, double x, double y, double z, float yawDeg) {
        anchorEntityUuid = uuid;
        anchorX = x;
        anchorY = y;
        anchorZ = z;
        anchorYawDeg = yawDeg;
        anchorValid = true;
    }

    //编辑已有 def 时：只更新位置，保留当前 yaw，避免覆写烤入 yaw 导致坐标系错乱
    public static void setAnchorPositionKeepYaw(UUID uuid, double x, double y, double z) {
        anchorEntityUuid = uuid;
        anchorX = x;
        anchorY = y;
        anchorZ = z;
        anchorValid = true;
    }

    public static void clearAnchor() {
        anchorEntityUuid = null;
        anchorX = anchorY = anchorZ = 0;
        anchorYawDeg = 0f;
        anchorValid = false;
    }

    public static boolean hasAnchor() { return anchorValid; }
    public static UUID getAnchorEntityUuid() { return anchorEntityUuid; }
    public static double getAnchorX() { return anchorX; }
    public static double getAnchorY() { return anchorY; }
    public static double getAnchorZ() { return anchorZ; }
    public static float getAnchorYawDeg() { return anchorYawDeg; }

    //=== 录制 ===
    public static RecState getRecState() { return recState; }
    public static boolean isRecordingMode() { return recState != RecState.IDLE; }
    public static boolean isActivelyRecording() { return recState == RecState.RECORDING; }
    public static long getRecordingStartTick() { return recordingStartTick; }

    public static void enterRecordingStandby(long currentGameTick) {
        if (recState != RecState.IDLE) return;
        undoStack.clear();
        redoStack.clear();
        backupFrames.clear();
        backupFrames.addAll(workingFrames);
        workingFrames.clear();
        selectedKeyframeFrameIndex = -1;
        recordingStartTick = currentGameTick;
        recState = RecState.PAUSED;
        resetTimelineEditing();
    }

    public static void startOrResumeRecording(long currentGameTick) {
        if (recState == RecState.IDLE) {
            undoStack.clear();
            redoStack.clear();
            backupFrames.clear();
            backupFrames.addAll(workingFrames);
            workingFrames.clear();
            selectedKeyframeFrameIndex = -1;
            recordingStartTick = currentGameTick;
            resetTimelineEditing();
        }
        recState = RecState.RECORDING;
    }

    public static void pauseRecording() {
        if (recState == RecState.RECORDING) recState = RecState.PAUSED;
    }

    //ENTER 键：保存退出
    //若整个 session 没录到任何帧（进入 standby 后没按 J 就 ENTER），把 backup 还原回去
    public static void finishRecording() {
        if (recState == RecState.IDLE) return;
        if (workingFrames.isEmpty() && !backupFrames.isEmpty()) {
            workingFrames.addAll(backupFrames);
            selectedKeyframeFrameIndex = findFirstKeyframeIndex();
        } else if (!workingFrames.isEmpty()) {
            rebuildContentCuesFromFrames();
            dirty = true;
        }
        backupFrames.clear();
        undoStack.clear();
        redoStack.clear();
        recState = RecState.IDLE;
        resetTimelineEditing();
    }

    //ESC 键：放弃录制（还原备份）
    public static void discardRecording() {
        if (recState == RecState.IDLE) return;
        workingFrames.clear();
        workingFrames.addAll(backupFrames);
        backupFrames.clear();
        undoStack.clear();
        redoStack.clear();
        selectedKeyframeFrameIndex = findFirstKeyframeIndex();
        recState = RecState.IDLE;
        resetTimelineEditing();
    }

    //每 tick 由事件处理器调用：把当前摄像机捕获为一个普通帧（仅 RECORDING 状态）
    public static void captureFrameFromCamera(double camX, double camY, double camZ, float camYaw, float camPitch) {
        if (recState != RecState.RECORDING || !anchorValid) return;
        double rad = Math.toRadians(anchorYawDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double ox = camX - anchorX;
        double oy = camY - anchorY;
        double oz = camZ - anchorZ;
        //逆旋转到 anchor-local 坐标系
        double dx = ox * cos - oz * sin;
        double dz = ox * sin + oz * cos;
        float localYaw = camYaw - anchorYawDeg;
        workingFrames.add(new Frame(dx, oy, dz, localYaw, camPitch, null));
    }

    //K 键：将当前帧（最后一帧）显式标记为关键帧（录制和暂停态都允许）
    //返回该帧下标；frames 为空时返回 -1；该帧已是关键帧时幂等返回其下标
    public static int markCurrentFrameAsKeyframe() {
        if (recState == RecState.IDLE) return -1;
        if (workingFrames.isEmpty()) return -1;
        int t = workingFrames.size() - 1;
        Frame current = workingFrames.get(t);
        if (current.keyframe() != null) return t;
        workingFrames.set(t, new Frame(current.dx(), current.dy(), current.dz(),
            current.yaw(), current.pitch(),
            new Keyframe(null, null, Curve.NONE)));
        selectedKeyframeFrameIndex = t;
        return t;
    }

    //=== 关键帧编辑 API（编辑器 GUI 使用）===
    //返回所有关键帧的帧下标（升序）
    public static List<Integer> getKeyframeFrameIndices() {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < workingFrames.size(); i++) {
            if (workingFrames.get(i).keyframe() != null) result.add(i);
        }
        return Collections.unmodifiableList(result);
    }

    public static int getSelectedKeyframeFrameIndex() { return selectedKeyframeFrameIndex; }

    public static void setSelectedKeyframeFrameIndex(int frameIdx) {
        if (workingFrames.isEmpty()) { selectedKeyframeFrameIndex = -1; return; }
        if (frameIdx < 0 || frameIdx >= workingFrames.size()
            || workingFrames.get(frameIdx).keyframe() == null) {
            selectedKeyframeFrameIndex = -1;
            return;
        }
        selectedKeyframeFrameIndex = frameIdx;
    }

    public static Keyframe getSelectedKeyframeData() {
        if (selectedKeyframeFrameIndex < 0 || selectedKeyframeFrameIndex >= workingFrames.size()) return null;
        return workingFrames.get(selectedKeyframeFrameIndex).keyframe();
    }

    public static void replaceKeyframe(int frameIdx, Keyframe kf) {
        if (frameIdx < 0 || frameIdx >= workingFrames.size() || kf == null) return;
        Frame f = workingFrames.get(frameIdx);
        if (kf.equals(f.keyframe())) return;
        pushUndoSnapshot();
        workingFrames.set(frameIdx, new Frame(f.dx(), f.dy(), f.dz(), f.yaw(), f.pitch(), kf));
        replaceEventCue(frameIdx, kf.eventId());
        replaceSubtitleCue(frameIdx, kf.subtitleText());
        dirty = true;
    }

    //在任意 tick 添加内容容器，不需要录制时预先标记关键帧。
    public static boolean addContentAtPlayhead() {
        int frameIdx = playhead;
        if (frameIdx < 0 || frameIdx >= workingFrames.size()) return false;
        if (workingFrames.get(frameIdx).keyframe() != null) {
            selectedKeyframeFrameIndex = frameIdx;
            return false;
        }
        Frame f = workingFrames.get(frameIdx);
        pushUndoSnapshot();
        workingFrames.set(frameIdx, new Frame(f.dx(), f.dy(), f.dz(), f.yaw(), f.pitch(),
            new Keyframe(null, null, Curve.NONE)));
        selectedKeyframeFrameIndex = frameIdx;
        dirty = true;
        return true;
    }

    //移除关键帧标记：保留该帧的位姿，仅清除 keyframe 附加数据
    public static boolean removeKeyframe(int frameIdx) {
        if (frameIdx < 0 || frameIdx >= workingFrames.size()) return false;
        if (workingFrames.get(frameIdx).keyframe() == null) return false;
        Frame f = workingFrames.get(frameIdx);
        pushUndoSnapshot();
        workingFrames.set(frameIdx, new Frame(f.dx(), f.dy(), f.dz(), f.yaw(), f.pitch(), null));
        removeEventCue(frameIdx);
        removeSubtitleCue(frameIdx);
        if (selectedKeyframeFrameIndex == frameIdx) {
            //选中下一个可用关键帧，若无则选前一个
            selectedKeyframeFrameIndex = -1;
            for (int i = frameIdx + 1; i < workingFrames.size(); i++) {
                if (workingFrames.get(i).keyframe() != null) { selectedKeyframeFrameIndex = i; break; }
            }
            if (selectedKeyframeFrameIndex < 0) {
                for (int i = frameIdx - 1; i >= 0; i--) {
                    if (workingFrames.get(i).keyframe() != null) { selectedKeyframeFrameIndex = i; break; }
                }
            }
        }
        dirty = true;
        return true;
    }

    private static void replaceEventCue(int tick, String eventId) {
        removeEventCue(tick);
        if (eventId != null && !eventId.isEmpty()) workingEventCues.add(new EventCue(tick, eventId));
    }

    private static void removeEventCue(int tick) {
        workingEventCues.removeIf(cue -> cue.tick() == tick);
    }

    private static void replaceSubtitleCue(int tick, String text) {
        removeSubtitleCue(tick);
        if (text != null && !text.isEmpty()) workingSubtitleCues.add(new SubtitleCue(tick, text));
    }

    private static void removeSubtitleCue(int tick) {
        workingSubtitleCues.removeIf(cue -> cue.tick() == tick);
    }

    private static void rebuildContentCuesFromFrames() {
        workingEventCues.clear();
        workingSubtitleCues.clear();
        for (int i = 0; i < workingFrames.size(); i++) {
            Keyframe keyframe = workingFrames.get(i).keyframe();
            if (keyframe == null) continue;
            if (keyframe.eventId() != null) workingEventCues.add(new EventCue(i, keyframe.eventId()));
            if (keyframe.subtitleText() != null) workingSubtitleCues.add(new SubtitleCue(i, keyframe.subtitleText()));
        }
    }

    private static void mergeContentCuesIntoFrames() {
        for (EventCue cue : workingEventCues) {
            mergeCueIntoFrame(cue.tick(), cue.eventId(), null);
        }
        for (SubtitleCue cue : workingSubtitleCues) {
            mergeCueIntoFrame(cue.tick(), null, cue.text());
        }
    }

    private static void mergeCueIntoFrame(int tick, String eventId, String subtitleText) {
        if (tick < 0 || tick >= workingFrames.size()) return;
        Frame frame = workingFrames.get(tick);
        Keyframe current = frame.keyframe();
        String mergedEvent = eventId != null ? eventId : current != null ? current.eventId() : null;
        String mergedSubtitle = subtitleText != null ? subtitleText : current != null ? current.subtitleText() : null;
        Curve curve = current != null ? current.curve() : Curve.NONE;
        workingFrames.set(tick, new Frame(frame.dx(), frame.dy(), frame.dz(), frame.yaw(), frame.pitch(),
            new Keyframe(mergedEvent, mergedSubtitle, curve)));
    }

    //=== getters / metadata ===
    public static boolean isActive() { return active; }
    public static boolean isDirty() { return dirty; }
    public static void clearDirty() { dirty = false; }
    public static void markDirty() { dirty = true; }

    public static ResourceLocation getEditingId() { return editingId; }
    public static EntityType<?> getTargetType() { return targetType; }
    public static void setTargetType(EntityType<?> type) { targetType = type; }

    public static Trigger getTrigger() { return trigger; }
    public static void setTrigger(Trigger t) {
        if (t == null) return;
        trigger = t;
        dirty = true;
    }

    public static boolean isCinematic() { return cinematic; }
    public static void setCinematic(boolean v) { cinematic = v; dirty = true; }

    public static boolean isAllowRepeat() { return allowRepeat; }
    public static void setAllowRepeat(boolean v) { allowRepeat = v; dirty = true; }

    public static List<Frame> getFrames() {
        return Collections.unmodifiableList(workingFrames);
    }

    //替换任意 tick 的镜头位姿，供时间轴属性面板使用。
    public static void replaceFramePose(int frameIdx, double dx, double dy, double dz,
                                        float yaw, float pitch) {
        if (frameIdx < 0 || frameIdx >= workingFrames.size()) return;
        Frame current = workingFrames.get(frameIdx);
        if (Double.compare(current.dx(), dx) == 0 && Double.compare(current.dy(), dy) == 0
            && Double.compare(current.dz(), dz) == 0 && Float.compare(current.yaw(), yaw) == 0
            && Float.compare(current.pitch(), pitch) == 0) return;
        pushUndoSnapshot();
        workingFrames.set(frameIdx, new Frame(dx, dy, dz, yaw, pitch, current.keyframe()));
        dirty = true;
    }

    public static int frameCount() { return workingFrames.size(); }

    //把播放头移动到边界内的相邻 tick。
    public static void movePlayheadBy(int delta) {
        if (workingFrames.isEmpty()) {
            playhead = 0;
            selectedKeyframeFrameIndex = -1;
            return;
        }
        setPlayhead(playhead + delta);
        setSelectedKeyframeFrameIndex(playhead);
    }

    public static int keyframeCount() {
        int count = 0;
        for (Frame f : workingFrames) {
            if (f.keyframe() != null) count++;
        }
        return count;
    }

    //返回带有事件或字幕内容的时间轴 tick 数量。
    public static int contentCount() {
        return keyframeCount();
    }

    //给选中区间的每个 tick 添加相同的位姿偏移。
    public static boolean offsetSelectedRange(double dx, double dy, double dz, float yaw, float pitch) {
        if (recState != RecState.IDLE || !hasValidRange()) return false;
        pushUndoSnapshot();
        for (int i = inPoint; i <= outPoint; i++) {
            Frame f = workingFrames.get(i);
            workingFrames.set(i, new Frame(f.dx() + dx, f.dy() + dy, f.dz() + dz,
                f.yaw() + yaw, f.pitch() + pitch, f.keyframe()));
        }
        dirty = true;
        return true;
    }

    //在选中区间内按 tick 比例渐变应用位姿偏移。
    public static boolean rampSelectedRange(double startDx, double startDy, double startDz,
                                            float startYaw, float startPitch,
                                            double endDx, double endDy, double endDz,
                                            float endYaw, float endPitch) {
        if (recState != RecState.IDLE || !hasValidRange()) return false;
        pushUndoSnapshot();
        int span = outPoint - inPoint;
        for (int i = inPoint; i <= outPoint; i++) {
            double t = span == 0 ? 0.0 : (i - inPoint) / (double) span;
            Frame f = workingFrames.get(i);
            workingFrames.set(i, new Frame(
                f.dx() + lerp(startDx, endDx, t),
                f.dy() + lerp(startDy, endDy, t),
                f.dz() + lerp(startDz, endDz, t),
                f.yaw() + (float) lerp(startYaw, endYaw, t),
                f.pitch() + (float) lerp(startPitch, endPitch, t),
                f.keyframe()));
        }
        dirty = true;
        return true;
    }

    public static boolean canUndo() { return !undoStack.isEmpty(); }
    public static boolean canRedo() { return !redoStack.isEmpty(); }

    //撤销最近一次时间轴修改。
    public static boolean undo() {
        if (undoStack.isEmpty() || recState != RecState.IDLE) return false;
        redoStack.push(new ArrayList<>(workingFrames));
        restoreSnapshot(undoStack.pop());
        return true;
    }

    //重做最近一次被撤销的时间轴修改。
    public static boolean redo() {
        if (redoStack.isEmpty() || recState != RecState.IDLE) return false;
        undoStack.push(new ArrayList<>(workingFrames));
        restoreSnapshot(redoStack.pop());
        return true;
    }

    private static void pushUndoSnapshot() {
        undoStack.push(new ArrayList<>(workingFrames));
        while (undoStack.size() > MAX_UNDO_SNAPSHOTS) undoStack.removeLast();
        redoStack.clear();
    }

    private static void restoreSnapshot(List<Frame> snapshot) {
        workingFrames.clear();
        workingFrames.addAll(snapshot);
        rebuildContentCuesFromFrames();
        if (workingFrames.isEmpty()) {
            playhead = 0;
            selectedKeyframeFrameIndex = -1;
        } else {
            setPlayhead(playhead);
            setSelectedKeyframeFrameIndex(selectedKeyframeFrameIndex);
        }
        dirty = true;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static BossShowDefinition buildDefinition() {
        if (editingId == null) return null;
        return new BossShowDefinition(
            editingId, targetType, trigger, cinematic, allowRepeat,
            new ArrayList<>(workingFrames), BossShowDefinition.Source.CONFIG, anchorYawDeg,
            new ArrayList<>(workingEventCues), new ArrayList<>(workingSubtitleCues));
    }

    public static List<EventCue> getEventCues() {
        return Collections.unmodifiableList(workingEventCues);
    }

    public static List<SubtitleCue> getSubtitleCues() {
        return Collections.unmodifiableList(workingSubtitleCues);
    }

    public static boolean armPoseCapture() {
        if (recState != RecState.IDLE || workingFrames.isEmpty()) return false;
        poseCaptureFrame = Math.max(0, Math.min(playhead, workingFrames.size() - 1));
        poseCaptureArmed = true;
        previewPlaying = false;
        return true;
    }

    public static boolean isPoseCaptureArmed() { return poseCaptureArmed; }

    public static void cancelPoseCapture() {
        poseCaptureArmed = false;
        poseCaptureFrame = -1;
    }

    public static void commitPoseCapture(double camX, double camY, double camZ,
                                         float camYaw, float camPitch) {
        if (!poseCaptureArmed || poseCaptureFrame < 0) return;
        double rad = Math.toRadians(anchorYawDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double ox = camX - anchorX;
        double oz = camZ - anchorZ;
        double dx = ox * cos - oz * sin;
        double dz = ox * sin + oz * cos;
        replaceFramePose(poseCaptureFrame, dx, camY - anchorY, dz,
            camYaw - anchorYawDeg, camPitch);
        playhead = poseCaptureFrame;
        poseCaptureArmed = false;
        poseCaptureFrame = -1;
    }

    public static boolean isPreviewPlaying() { return previewPlaying; }

    public static void stopPreviewPlayback() {
        previewPlaying = false;
    }

    public static void togglePreviewPlayback() {
        if (workingFrames.isEmpty()) return;
        if (previewPlaying) {
            previewPlaying = false;
            setPlayhead((int) Math.round(previewCursor));
            return;
        }
        previewCursor = Math.max(0, Math.min(playhead, workingFrames.size() - 1));
        previewPlaying = true;
    }

    public static void tickPreviewPlayback() {
        if (!previewPlaying || workingFrames.isEmpty()) return;
        previewCursor += 1.0;
        playhead = Math.max(0, Math.min((int) Math.floor(previewCursor), workingFrames.size() - 1));
        if (previewCursor >= workingFrames.size() - 1) {
            previewCursor = workingFrames.size() - 1;
            previewPlaying = false;
            setPlayhead((int) previewCursor);
        }
    }

    //=== 选择模式 ===
    public static SelectionKind getSelectionKind() { return selectionKind; }
    public static boolean isPlaySelectionMode() { return selectionKind == SelectionKind.PLAY; }
    public static boolean isAnySelectionMode() { return selectionKind != SelectionKind.NONE; }

    public static void enterSelectionMode() {
        selectionKind = SelectionKind.CREATE_NEW;
        pendingPlayDefId = null;
        hoveredEntityUuid = null;
        clearAnchor();
        recState = RecState.IDLE;
        recordingStartTick = 0L;
        backupFrames.clear();
    }

    public static void enterPlaySelection(ResourceLocation defId) {
        selectionKind = SelectionKind.PLAY;
        pendingPlayDefId = defId;
        hoveredEntityUuid = null;
    }

    public static void exitSelectionMode() {
        selectionKind = SelectionKind.NONE;
        pendingPlayDefId = null;
        hoveredEntityUuid = null;
    }

    public static ResourceLocation getPendingPlayDefId() { return pendingPlayDefId; }
    public static UUID getHoveredEntityUuid() { return hoveredEntityUuid; }
    public static void setHoveredEntityUuid(UUID uuid) { hoveredEntityUuid = uuid; }

    public static ResourceLocation generateAutoId(EntityType<?> type) {
        ResourceLocation typeId = type != null ? BuiltInRegistries.ENTITY_TYPE.getKey(type) : null;
        String namespace = typeId != null ? typeId.getNamespace() : "eca";
        String basePath = typeId != null ? typeId.getPath() : "anchor";
        ResourceLocation candidate = new ResourceLocation(namespace, basePath);
        int n = 2;
        while (idTaken(candidate)) {
            candidate = new ResourceLocation(namespace, basePath + "_" + n);
            n++;
        }
        return candidate;
    }

    private static boolean idTaken(ResourceLocation id) {
        for (BossShowDefinition d : availableDefs) {
            if (d.id().equals(id)) return true;
        }
        return false;
    }

    private static int findFirstKeyframeIndex() {
        for (int i = 0; i < workingFrames.size(); i++) {
            if (workingFrames.get(i).keyframe() != null) return i;
        }
        return -1;
    }

    //=== 时间轴编辑（播放头 / 区间 / 剪贴板）===
    public static int getPlayhead() { return playhead; }

    public static void setPlayhead(int idx) {
        if (workingFrames.isEmpty()) { playhead = 0; return; }
        previewPlaying = false;
        playhead = Math.max(0, Math.min(idx, workingFrames.size() - 1));
    }

    public static int getInPoint() { return inPoint; }
    public static int getOutPoint() { return outPoint; }

    public static void setInPoint(int idx) {
        if (workingFrames.isEmpty()) return;
        inPoint = Math.max(0, Math.min(idx, workingFrames.size() - 1));
        if (outPoint >= 0 && outPoint < inPoint) outPoint = inPoint;
    }

    public static void setOutPoint(int idx) {
        if (workingFrames.isEmpty()) return;
        outPoint = Math.max(0, Math.min(idx, workingFrames.size() - 1));
        if (inPoint >= 0 && inPoint > outPoint) inPoint = outPoint;
    }

    public static void clearRange() { inPoint = -1; outPoint = -1; }

    public static boolean hasValidRange() {
        return inPoint >= 0 && outPoint >= 0 && inPoint <= outPoint
            && outPoint < workingFrames.size();
    }

    public static boolean hasClipboard() { return !clipboard.isEmpty(); }
    public static int clipboardSize() { return clipboard.size(); }

    //复制区间到剪贴板（时间轴不变）
    public static boolean copyRange() {
        if (recState != RecState.IDLE || !hasValidRange()) return false;
        clipboard.clear();
        for (int i = inPoint; i <= outPoint; i++) clipboard.add(workingFrames.get(i));
        return true;
    }

    //删除区间，后续帧 ripple 前移
    public static boolean deleteRange() {
        if (recState != RecState.IDLE || !hasValidRange()) return false;
        pushUndoSnapshot();
        int at = inPoint;
        workingFrames.subList(inPoint, outPoint + 1).clear();
        afterRippleEdit(at);
        return true;
    }

    //剪切 = 复制 + 删除
    public static boolean cutRange() {
        if (recState != RecState.IDLE || !hasValidRange()) return false;
        return copyRange() && deleteRange();
    }

    //在播放头处插入剪贴板内容，后续帧 ripple 后移
    public static boolean pasteAtPlayhead() {
        if (recState != RecState.IDLE || clipboard.isEmpty()) return false;
        pushUndoSnapshot();
        int at = workingFrames.isEmpty() ? 0 : Math.max(0, Math.min(playhead, workingFrames.size()));
        workingFrames.addAll(at, new ArrayList<>(clipboard));
        afterRippleEdit(at + clipboard.size() - 1);
        return true;
    }

    //结构性增删后统一收尾：清区间、钳播放头、重置选中、置 dirty
    private static void afterRippleEdit(int newPlayheadTarget) {
        rebuildContentCuesFromFrames();
        clearRange();
        if (workingFrames.isEmpty()) {
            playhead = 0;
        } else {
            playhead = Math.max(0, Math.min(newPlayheadTarget, workingFrames.size() - 1));
        }
        selectedKeyframeFrameIndex = -1;
        dirty = true;
    }

    //=== 相机预览 ===
    public static void setPreviewEnabled(boolean v) { previewEnabled = v; }

    //仅在编辑器 Screen 打开、非录制、已有锚点且有帧时介入相机（与播放互斥）
    public static boolean isPreviewActive() {
        return active && previewEnabled && anchorValid
            && recState == RecState.IDLE && !workingFrames.isEmpty();
    }

    //播放头帧的世界空间位姿（原始帧位姿，不做曲线重映射）
    public static BossShowPose computePreviewPose() {
        int p = Math.max(0, Math.min(playhead, workingFrames.size() - 1));
        double cursor = previewPlaying ? previewCursor : p;
        BossShowInterpolator.computePose(workingFrames, cinematic, cursor,
            anchorX, anchorY, anchorZ, anchorYawDeg, previewPose);
        return previewPose;
    }

    private static void resetTimelineEditing() {
        playhead = 0;
        inPoint = -1;
        outPoint = -1;
        previewEnabled = false;
    }
}
