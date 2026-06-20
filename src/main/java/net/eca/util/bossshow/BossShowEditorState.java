package net.eca.util.bossshow;

import net.eca.util.bossshow.BossShowDefinition.Frame;
import net.eca.util.bossshow.BossShowDefinition.Keyframe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("removal")
//客户端 BossShow 编辑器状态单例。仅在客户端逻辑线程访问。
public final class BossShowEditorState {

    public enum RecState { IDLE, RECORDING, PAUSED }

    private static boolean active = false;
    private static ResourceLocation editingId = null;
    private static EntityType<?> targetType = null;
    private static Trigger trigger = new Trigger.Custom("");
    private static boolean cinematic = true;
    private static boolean allowRepeat = false;

    //工作副本：单条帧序列，关键帧内嵌于 Frame
    private static final ArrayList<Frame> workingFrames = new ArrayList<>();
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
    //仅当编辑器 Screen 打开时为 true（由 Screen init/removed 切换）
    private static boolean previewEnabled = false;
    private static final BossShowPose previewPose = new BossShowPose();

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
        selectedKeyframeFrameIndex = -1;
        dirty = false;
        availableDefs.clear();
        if (available != null) availableDefs.addAll(available);
        recState = RecState.IDLE;
        recordingStartTick = 0L;
        backupFrames.clear();
        resetTimelineEditing();
        clipboard.clear();
    }

    public static void enter(BossShowDefinition def) {
        editingId = def.id();
        targetType = def.targetType();
        trigger = def.trigger();
        cinematic = def.cinematic();
        allowRepeat = def.allowRepeat();
        workingFrames.clear();
        workingFrames.addAll(def.frames());
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
        selectedKeyframeFrameIndex = -1;
        dirty = false;
        availableDefs.clear();
        clearAnchor();
        recState = RecState.IDLE;
        backupFrames.clear();
        resetTimelineEditing();
        clipboard.clear();
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
            dirty = true;
        }
        backupFrames.clear();
        recState = RecState.IDLE;
        resetTimelineEditing();
    }

    //ESC 键：放弃录制（还原备份）
    public static void discardRecording() {
        if (recState == RecState.IDLE) return;
        workingFrames.clear();
        workingFrames.addAll(backupFrames);
        backupFrames.clear();
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
        workingFrames.set(frameIdx, new Frame(f.dx(), f.dy(), f.dz(), f.yaw(), f.pitch(), kf));
        dirty = true;
    }

    //移除关键帧标记：保留该帧的位姿，仅清除 keyframe 附加数据
    public static boolean removeKeyframe(int frameIdx) {
        if (frameIdx < 0 || frameIdx >= workingFrames.size()) return false;
        if (workingFrames.get(frameIdx).keyframe() == null) return false;
        Frame f = workingFrames.get(frameIdx);
        workingFrames.set(frameIdx, new Frame(f.dx(), f.dy(), f.dz(), f.yaw(), f.pitch(), null));
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

    public static int frameCount() { return workingFrames.size(); }

    public static int keyframeCount() {
        int count = 0;
        for (Frame f : workingFrames) {
            if (f.keyframe() != null) count++;
        }
        return count;
    }

    public static BossShowDefinition buildDefinition() {
        if (editingId == null) return null;
        return new BossShowDefinition(
            editingId, targetType, trigger, cinematic, allowRepeat,
            new ArrayList<>(workingFrames), BossShowDefinition.Source.CONFIG, anchorYawDeg);
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
        ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(namespace, basePath);
        int n = 2;
        while (idTaken(candidate)) {
            candidate = ResourceLocation.fromNamespaceAndPath(namespace, basePath + "_" + n);
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
        int at = workingFrames.isEmpty() ? 0 : Math.max(0, Math.min(playhead, workingFrames.size()));
        workingFrames.addAll(at, new ArrayList<>(clipboard));
        afterRippleEdit(at + clipboard.size() - 1);
        return true;
    }

    //结构性增删后统一收尾：清区间、钳播放头、重置选中、置 dirty
    private static void afterRippleEdit(int newPlayheadTarget) {
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
        Frame f = workingFrames.get(p);
        Vec3 wp = BossShowInterpolator.anchorToWorld(f.dx(), f.dy(), f.dz(),
            anchorX, anchorY, anchorZ, anchorYawDeg);
        previewPose.x = wp.x;
        previewPose.y = wp.y;
        previewPose.z = wp.z;
        previewPose.yaw = f.yaw() + anchorYawDeg;
        previewPose.pitch = f.pitch();
        previewPose.cinematic = cinematic;
        return previewPose;
    }

    private static void resetTimelineEditing() {
        playhead = 0;
        inPoint = -1;
        outPoint = -1;
        previewEnabled = false;
    }
}
