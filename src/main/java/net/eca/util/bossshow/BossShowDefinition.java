package net.eca.util.bossshow;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/* 不可变 BossShow 定义：JSON 加载或 Java 注册都生成此对象。
 * 时间轴以帧为基本单位，1 tick = 1 帧；帧下标即时间偏移，totalDuration = frames.size()。
 * 事件和字幕通过独立轨道按 tick 触发；帧内附加数据仅用于旧格式兼容。 */
public final class BossShowDefinition {

    /* 单帧：每 tick 一帧，承载镜头位姿（anchor-local 坐标系）。
     * keyframe != null 表示该帧被显式标记为关键帧。 */
    public record Frame(double dx, double dy, double dz, float yaw, float pitch,
                        Keyframe keyframe) {}

    /* 关键帧附加数据：仅显式标记的帧持有。
     * curve 控制本关键帧 → 下一个关键帧之间那段帧的镜头/朝向变化趋势。
     * eventId 与 subtitleText 完全独立，可任意组合。 */
    public record Keyframe(String eventId, String subtitleText, Curve curve) {
        public Keyframe {
            if (eventId != null && eventId.isEmpty()) eventId = null;
            if (subtitleText != null && subtitleText.isEmpty()) subtitleText = null;
            if (curve == null) curve = Curve.NONE;
        }
    }

    //独立事件轨道上的一个时间点。
    public record EventCue(int tick, String eventId) {
        public EventCue {
            if (tick < 0) tick = 0;
            if (eventId != null && eventId.isEmpty()) eventId = null;
        }
    }

    //独立字幕轨道上的一个时间点。
    public record SubtitleCue(int tick, String text) {
        public SubtitleCue {
            if (tick < 0) tick = 0;
            if (text != null && text.isEmpty()) text = null;
        }
    }

    public enum Source {
        MOD,    // 来自 mod jar 内 data/<modid>/eca/bossshow/ 或兼容旧目录
        CONFIG, //来自 config/eca/bossshow/<ns>/<name>.json（覆盖 MOD）
        CODE    //纯 Java @RegisterBossShow 类声明的
    }

    private final ResourceLocation id;
    private final EntityType<?> targetType;
    private final Trigger trigger;
    private final boolean cinematic;
    //true = 同一 viewer 可对不同 target 多次播放；false（默认）= 终身只看一次
    private final boolean allowRepeat;
    private final List<Frame> frames;
    private final List<EventCue> eventCues;
    private final List<SubtitleCue> subtitleCues;
    private final List<BossShowEffectCue> effectCues;
    private final Source source;
    //录制时烤入的 anchor yaw：frame 的 anchor-local 编码参考系
    private final float anchorYawDeg;

    public BossShowDefinition(ResourceLocation id,
                              EntityType<?> targetType,
                              Trigger trigger,
                              boolean cinematic,
                              boolean allowRepeat,
                              List<Frame> frames,
                              Source source,
                              float anchorYawDeg) {
        this(id, targetType, trigger, cinematic, allowRepeat, frames, source, anchorYawDeg,
            deriveEventCues(frames), deriveSubtitleCues(frames), List.of());
    }

    public BossShowDefinition(ResourceLocation id,
                              EntityType<?> targetType,
                              Trigger trigger,
                              boolean cinematic,
                              boolean allowRepeat,
                              List<Frame> frames,
                              Source source,
                              float anchorYawDeg,
                              List<EventCue> eventCues,
                              List<SubtitleCue> subtitleCues) {
        this(id, targetType, trigger, cinematic, allowRepeat, frames, source, anchorYawDeg,
            eventCues, subtitleCues, List.of());
    }

    public BossShowDefinition(ResourceLocation id,
                              EntityType<?> targetType,
                              Trigger trigger,
                              boolean cinematic,
                              boolean allowRepeat,
                              List<Frame> frames,
                              Source source,
                              float anchorYawDeg,
                              List<EventCue> eventCues,
                              List<SubtitleCue> subtitleCues,
                              List<BossShowEffectCue> effectCues) {
        this.id = id;
        this.targetType = targetType;
        this.trigger = trigger;
        this.cinematic = cinematic;
        this.allowRepeat = allowRepeat;
        this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
        this.eventCues = Collections.unmodifiableList(new ArrayList<>(eventCues != null ? eventCues : List.of()));
        this.subtitleCues = Collections.unmodifiableList(new ArrayList<>(subtitleCues != null ? subtitleCues : List.of()));
        this.effectCues = Collections.unmodifiableList(new ArrayList<>(effectCues != null ? effectCues : List.of()));
        this.source = source;
        this.anchorYawDeg = anchorYawDeg;
    }

    public ResourceLocation id() { return id; }
    public EntityType<?> targetType() { return targetType; }
    public Trigger trigger() { return trigger; }
    public boolean cinematic() { return cinematic; }
    public boolean allowRepeat() { return allowRepeat; }
    public List<Frame> frames() { return frames; }
    public List<EventCue> eventCues() { return eventCues; }
    public List<SubtitleCue> subtitleCues() { return subtitleCues; }
    public List<BossShowEffectCue> effectCues() { return effectCues; }
    public Source source() { return source; }
    public float anchorYawDeg() { return anchorYawDeg; }

    public int totalDurationTicks() { return frames.size(); }

    public boolean isEmpty() { return frames.isEmpty(); }

    private static List<EventCue> deriveEventCues(List<Frame> frames) {
        List<EventCue> result = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            Keyframe keyframe = frames.get(i).keyframe();
            if (keyframe != null && keyframe.eventId() != null) {
                result.add(new EventCue(i, keyframe.eventId()));
            }
        }
        return result;
    }

    private static List<SubtitleCue> deriveSubtitleCues(List<Frame> frames) {
        List<SubtitleCue> result = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            Keyframe keyframe = frames.get(i).keyframe();
            if (keyframe != null && keyframe.subtitleText() != null) {
                result.add(new SubtitleCue(i, keyframe.subtitleText()));
            }
        }
        return result;
    }
}
