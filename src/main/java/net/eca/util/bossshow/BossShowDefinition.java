package net.eca.util.bossshow;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.Collections;
import java.util.List;

/* 不可变 BossShow 定义：JSON 加载或 Java 注册都生成此对象。
 * 时间轴以帧为基本单位，1 tick = 1 帧；帧下标即时间偏移，totalDuration = frames.size()。
 * keyframe != null 的帧被显式标记为关键帧，携带事件/字幕/曲线附加数据。 */
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

    public enum Source {
        MOD,    //来自 mod jar 内 data/<modid>/bossshow/
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
        this.id = id;
        this.targetType = targetType;
        this.trigger = trigger;
        this.cinematic = cinematic;
        this.allowRepeat = allowRepeat;
        this.frames = Collections.unmodifiableList(frames);
        this.source = source;
        this.anchorYawDeg = anchorYawDeg;
    }

    public ResourceLocation id() { return id; }
    public EntityType<?> targetType() { return targetType; }
    public Trigger trigger() { return trigger; }
    public boolean cinematic() { return cinematic; }
    public boolean allowRepeat() { return allowRepeat; }
    public List<Frame> frames() { return frames; }
    public Source source() { return source; }
    public float anchorYawDeg() { return anchorYawDeg; }

    public int totalDurationTicks() { return frames.size(); }

    public boolean isEmpty() { return frames.isEmpty(); }
}
