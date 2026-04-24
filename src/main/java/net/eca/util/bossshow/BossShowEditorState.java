package net.eca.util.bossshow;

import net.eca.util.bossshow.BossShowDefinition.Marker;
import net.eca.util.bossshow.BossShowDefinition.Sample;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("removal")
//客户端 BossShow 编辑器状态单例。仅在客户端逻辑线程访问。
//新模型：连续 sample 录制 + 稀疏 marker 元数据
public final class BossShowEditorState {

    public enum RecState { IDLE, RECORDING, PAUSED }

    private static boolean active = false;
    private static ResourceLocation editingId = null;
    private static EntityType<?> targetType = null;
    private static Trigger trigger = new Trigger.Custom("");
    private static boolean cinematic = true;
    private static boolean allowRepeat = false;

    //工作副本
    private static final ArrayList<Sample> workingSamples = new ArrayList<>();
    private static final ArrayList<Marker> workingMarkers = new ArrayList<>();
    private static int selectedMarker = -1;
    private static boolean dirty = false;

    //Home 缓存
    private static final ArrayList<BossShowDefinition> availableDefs = new ArrayList<>();

    //=== 选择模式 ===
    public enum SelectionKind { NONE, CREATE_NEW, PLAY }
    private static SelectionKind selectionKind = SelectionKind.NONE;
    private static ResourceLocation pendingPlayDefId = null;
    private static UUID hoveredEntityUuid = null;

    //=== 锚点 ===
    //anchorEntityUuid == null 表示该 anchor 不绑定任何实体（自由空间锚点）
    //渲染器（BossShowAnchorRenderer）只看 anchor 坐标，不关心是否绑定实体
    private static UUID anchorEntityUuid = null;
    private static double anchorX, anchorY, anchorZ;
    private static float anchorYawDeg = 0f;
    private static boolean anchorValid = false;

    //=== 录制状态 ===
    private static RecState recState = RecState.IDLE;
    private static long recordingStartTick = 0L;
    //备份用于 ESC 取消还原
    private static final ArrayList<Sample> backupSamples = new ArrayList<>();
    private static final ArrayList<Marker> backupMarkers = new ArrayList<>();

    private BossShowEditorState() {}

    //=== Session ===
    public static void beginSession(Collection<BossShowDefinition> available) {
        active = true;
        editingId = null;
        targetType = null;
        trigger = new Trigger.Custom("");
        cinematic = true;
        allowRepeat = false;
        workingSamples.clear();
        workingMarkers.clear();
        selectedMarker = -1;
        dirty = false;
        availableDefs.clear();
        if (available != null) availableDefs.addAll(available);
        //会话开始时清空录制状态，避免上次客户端会话残留
        recState = RecState.IDLE;
        recordingStartTick = 0L;
        backupSamples.clear();
        backupMarkers.clear();
    }

    public static void enter(BossShowDefinition def) {
        editingId = def.id();
        targetType = def.targetType();
        trigger = def.trigger();
        cinematic = def.cinematic();
        allowRepeat = def.allowRepeat();
        workingSamples.clear();
        workingSamples.addAll(def.samples());
        workingMarkers.clear();
        workingMarkers.addAll(def.markers());
        selectedMarker = workingMarkers.isEmpty() ? -1 : 0;
        dirty = false;
        active = true;
        //def 已有 sample 时，新录制的 sample 必须复用同一 anchor yaw，否则坐标系错乱
        //空 def 留给后续 setAnchor 去定 yaw
        if (!def.samples().isEmpty()) {
            anchorYawDeg = def.anchorYawDeg();
        }
        //重置录制状态，避免上一个 def 的 recState 泄漏到新 def
        recState = RecState.IDLE;
        recordingStartTick = 0L;
        backupSamples.clear();
        backupMarkers.clear();
    }

    public static BossShowDefinition createBlank(ResourceLocation id, EntityType<?> type) {
        return new BossShowDefinition(id, type, new Trigger.Custom(""), true, false,
            new ArrayList<>(), new ArrayList<>(), BossShowDefinition.Source.CONFIG, 0f);
    }

    public static List<BossShowDefinition> getAvailableDefs() {
        return Collections.unmodifiableList(availableDefs);
    }

    //保存后乐观更新本地缓存：已存在则替换，否则追加
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
        workingSamples.clear();
        workingMarkers.clear();
        selectedMarker = -1;
        dirty = false;
        availableDefs.clear();
        clearAnchor();
        recState = RecState.IDLE;
        backupSamples.clear();
        backupMarkers.clear();
    }

    //=== 锚点 ===
    //uuid 可为 null：表示锚点不绑定任何实体（纯空间坐标锚点）
    public static void setAnchor(UUID uuid, double x, double y, double z, float yawDeg) {
        anchorEntityUuid = uuid;
        anchorX = x;
        anchorY = y;
        anchorZ = z;
        anchorYawDeg = yawDeg;
        anchorValid = true;
    }

    //编辑已有 def 时使用：只更新 anchor 位置，保留当前 yaw
    //避免覆写 def 烤入的 yaw 导致新旧 sample 坐标系错乱
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

    //点击 Record 按钮：进入待录制 standby（HUD 显示为 PAUSED，不采样）
    //备份现有数据并清空 working，等用户按 J 才真正开始捕获
    public static void enterRecordingStandby(long currentGameTick) {
        if (recState != RecState.IDLE) return;
        backupSamples.clear();
        backupSamples.addAll(workingSamples);
        backupMarkers.clear();
        backupMarkers.addAll(workingMarkers);
        workingSamples.clear();
        workingMarkers.clear();
        selectedMarker = -1;
        recordingStartTick = currentGameTick;
        recState = RecState.PAUSED;
    }

    //J 键：开始/恢复
    public static void startOrResumeRecording(long currentGameTick) {
        if (recState == RecState.IDLE) {
            backupSamples.clear();
            backupSamples.addAll(workingSamples);
            backupMarkers.clear();
            backupMarkers.addAll(workingMarkers);
            workingSamples.clear();
            workingMarkers.clear();
            selectedMarker = -1;
            recordingStartTick = currentGameTick;
        }
        recState = RecState.RECORDING;
    }

    //I 键：暂停
    public static void pauseRecording() {
        if (recState == RecState.RECORDING) recState = RecState.PAUSED;
    }

    //ENTER 键：保存退出（保留当前 sample/marker 数据）
    //如果整个 session 没采到任何 sample（典型场景：进入 standby 后没按 J 就 ENTER），
    //把 backup 还原回去，避免静默清空原有数据
    public static void finishRecording() {
        if (recState == RecState.IDLE) return;
        if (workingSamples.isEmpty() && !backupSamples.isEmpty()) {
            workingSamples.addAll(backupSamples);
            workingMarkers.addAll(backupMarkers);
            selectedMarker = workingMarkers.isEmpty() ? -1 : 0;
        } else if (!workingSamples.isEmpty()) {
            dirty = true;
        }
        backupSamples.clear();
        backupMarkers.clear();
        recState = RecState.IDLE;
    }

    //ESC 键：放弃录制（还原备份）
    public static void discardRecording() {
        if (recState == RecState.IDLE) return;
        workingSamples.clear();
        workingSamples.addAll(backupSamples);
        workingMarkers.clear();
        workingMarkers.addAll(backupMarkers);
        backupSamples.clear();
        backupMarkers.clear();
        selectedMarker = workingMarkers.isEmpty() ? -1 : 0;
        recState = RecState.IDLE;
    }

    //每 tick 由事件处理器调用：把当前摄像机捕获为一个 sample（仅 RECORDING 状态）
    public static void captureSampleFromCamera(double camX, double camY, double camZ, float camYaw, float camPitch) {
        if (recState != RecState.RECORDING || !anchorValid) return;
        double rad = Math.toRadians(anchorYawDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double ox = camX - anchorX;
        double oy = camY - anchorY;
        double oz = camZ - anchorZ;
        //逆旋转
        double dx = ox * cos - oz * sin;
        double dz = ox * sin + oz * cos;
        float localYaw = camYaw - anchorYawDeg;
        workingSamples.add(new Sample(dx, oy, dz, localYaw, camPitch));
    }

    //K 键：在当前 sample tick 添加 marker（录制和暂停态都允许）
    //返回新 marker 的 tickOffset；samples 为空时返回 -1
    public static int addMarkerAtCurrentTick() {
        if (recState == RecState.IDLE) return -1;
        if (workingSamples.isEmpty()) return -1;
        int t = workingSamples.size() - 1;
        //避免在同一 tick 重复添加
        for (Marker existing : workingMarkers) {
            if (existing.tickOffset() == t) return t;
        }
        workingMarkers.add(new Marker(t, null, null, Curve.NONE));
        sortMarkers();
        selectedMarker = indexOfMarkerAt(t);
        return t;
    }

    //=== Marker 编辑 API（编辑器 GUI 使用） ===
    public static List<Marker> getMarkers() {
        return Collections.unmodifiableList(workingMarkers);
    }

    public static int getSelectedMarker() { return selectedMarker; }

    public static void setSelectedMarker(int idx) {
        if (workingMarkers.isEmpty()) { selectedMarker = -1; return; }
        if (idx < 0) idx = 0;
        if (idx >= workingMarkers.size()) idx = workingMarkers.size() - 1;
        selectedMarker = idx;
    }

    public static Marker getSelectedMarkerObj() {
        if (selectedMarker < 0 || selectedMarker >= workingMarkers.size()) return null;
        return workingMarkers.get(selectedMarker);
    }

    public static void replaceMarker(int idx, Marker m) {
        if (idx < 0 || idx >= workingMarkers.size() || m == null) return;
        workingMarkers.set(idx, m);
        dirty = true;
    }

    public static boolean removeMarker(int idx) {
        if (idx < 0 || idx >= workingMarkers.size()) return false;
        workingMarkers.remove(idx);
        if (selectedMarker >= workingMarkers.size()) selectedMarker = workingMarkers.size() - 1;
        dirty = true;
        return true;
    }

    private static void sortMarkers() {
        workingMarkers.sort((a, b) -> Integer.compare(a.tickOffset(), b.tickOffset()));
    }

    private static int indexOfMarkerAt(int t) {
        for (int i = 0; i < workingMarkers.size(); i++) {
            if (workingMarkers.get(i).tickOffset() == t) return i;
        }
        return -1;
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
    public static void setCinematic(boolean v) {
        cinematic = v;
        dirty = true;
    }

    public static boolean isAllowRepeat() { return allowRepeat; }
    public static void setAllowRepeat(boolean v) {
        allowRepeat = v;
        dirty = true;
    }

    public static List<Sample> getSamples() {
        return Collections.unmodifiableList(workingSamples);
    }

    public static int sampleCount() { return workingSamples.size(); }

    public static BossShowDefinition buildDefinition() {
        if (editingId == null) return null;
        return new BossShowDefinition(
            editingId,
            targetType,
            trigger,
            cinematic,
            allowRepeat,
            new ArrayList<>(workingSamples),
            new ArrayList<>(workingMarkers),
            BossShowDefinition.Source.CONFIG,
            anchorYawDeg
        );
    }

    //=== 选择模式 ===
    public static SelectionKind getSelectionKind() { return selectionKind; }
    public static boolean isPlaySelectionMode() { return selectionKind == SelectionKind.PLAY; }
    public static boolean isAnySelectionMode() { return selectionKind != SelectionKind.NONE; }

    public static void enterSelectionMode() {
        selectionKind = SelectionKind.CREATE_NEW;
        pendingPlayDefId = null;
        hoveredEntityUuid = null;
        //彻底清除上一次会话的残留：anchor + 录制状态。
        //避免上一个 def 的 hasAnchor 在 selection 模式下让 J 键 / 采样意外激活
        clearAnchor();
        recState = RecState.IDLE;
        recordingStartTick = 0L;
        backupSamples.clear();
        backupMarkers.clear();
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
}
