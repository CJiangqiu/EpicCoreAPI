package net.eca.util.bossshow;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.Collections;
import java.util.List;

//不可变 BossShow 定义：JSON 加载或 Java 注册都生成此对象
//新数据模型：samples 是逐 tick 完整轨迹，markers 是稀疏事件点
public final class BossShowDefinition {

    //单 tick 摄像机位姿（anchor-local 坐标系）
    //每个 Sample 对应录制时间轴的一个游戏 tick；total duration = samples.size() ticks
    public record Sample(double dx, double dy, double dz, float yaw, float pitch) {}

    //稀疏元数据点：附在某个 sample tick 上承载事件 id / 字幕 / 曲线等信息
    //tickOffset 必须 ∈ [0, samples.size())；非法 marker 在加载时被丢弃
    //curve 控制从该 marker tick 到下一个 marker tick 之间的播放速度缓动
    //eventId 和 subtitleText 完全独立：可同时有、都没有、只有其一
    public record Marker(int tickOffset, String eventId, String subtitleText, Curve curve) {
        public Marker {
            if (tickOffset < 0) tickOffset = 0;
            if (eventId != null && eventId.isEmpty()) eventId = null;
            if (subtitleText != null && subtitleText.isEmpty()) subtitleText = null;
            if (curve == null) curve = Curve.NONE;
        }
    }

    //定义来源
    public enum Source {
        MOD,      //来自 mod jar 内 data/<modid>/bossshow/
        CONFIG,   //来自 config/eca/bossshow/<ns>/<name>.json（覆盖 MOD）
        CODE      //纯 Java @RegisterBossShow 类声明的
    }

    private final ResourceLocation id;
    private final EntityType<?> targetType;
    private final Trigger trigger;
    private final boolean cinematic;
    //每个 def 自己声明：true = 同一 viewer 可对不同 target 多次播放（NBT 记到实体上）
    //false（默认）= 一个 viewer 终身只看一次
    private final boolean allowRepeat;
    private final List<Sample> samples;
    private final List<Marker> markers;
    private final Source source;
    //录制时烤入的 anchor yaw：sample 的 anchor-local 编码参考系
    //播放时直接用这个 yaw 解码，保证和录制时完全一致
    private final float anchorYawDeg;

    public BossShowDefinition(ResourceLocation id,
                              EntityType<?> targetType,
                              Trigger trigger,
                              boolean cinematic,
                              boolean allowRepeat,
                              List<Sample> samples,
                              List<Marker> markers,
                              Source source,
                              float anchorYawDeg) {
        this.id = id;
        this.targetType = targetType;
        this.trigger = trigger;
        this.cinematic = cinematic;
        this.allowRepeat = allowRepeat;
        this.samples = Collections.unmodifiableList(samples);
        this.markers = Collections.unmodifiableList(markers);
        this.source = source;
        this.anchorYawDeg = anchorYawDeg;
    }

    public ResourceLocation id() { return id; }
    public EntityType<?> targetType() { return targetType; }
    public Trigger trigger() { return trigger; }
    public boolean cinematic() { return cinematic; }
    public boolean allowRepeat() { return allowRepeat; }
    public List<Sample> samples() { return samples; }
    public List<Marker> markers() { return markers; }
    public Source source() { return source; }
    public float anchorYawDeg() { return anchorYawDeg; }

    //总时长 = sample 数（每个 sample 占 1 tick）
    public int totalDurationTicks() {
        return samples.size();
    }

    public boolean isEmpty() {
        return samples.isEmpty();
    }
}
