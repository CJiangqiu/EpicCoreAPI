package net.eca.util.raid;

import net.minecraft.network.FriendlyByteBuf;

/*
 * 袭击状态快照 —— 随血条同步到客户端，使血条外观可以按袭击进程变化。
 *
 * 客户端本身不持有 RaidInstance（袭击是纯服务端状态），这个快照是
 * RaidBossBarExtension 条件方法唯一能拿到的袭击上下文。
 *
 * 字段保持精简：只放外观决策用得上的信息，不是袭击状态的完整镜像。
 */
public final class RaidBarState {

    private final String definitionId;
    private final RaidStatus status;
    private final int wavesSpawned;
    private final int waveCount;
    private final int aliveRaiders;
    private final int waveTotal;
    private final boolean endless;

    // 创建袭击状态快照
    /**
     * @param definitionId the raid definition id
     * @param status       current lifecycle state
     * @param wavesSpawned how many waves have spawned so far
     * @param waveCount    total declared waves (meaningless for endless raids)
     * @param aliveRaiders raiders still alive
     * @param waveTotal    how many raiders the current wave spawned
     * @param endless      whether this raid cycles its waves forever
     */
    public RaidBarState(String definitionId, RaidStatus status, int wavesSpawned, int waveCount,
                        int aliveRaiders, int waveTotal, boolean endless) {
        this.definitionId = definitionId == null ? "" : definitionId;
        this.status = status == null ? RaidStatus.ONGOING : status;
        this.wavesSpawned = wavesSpawned;
        this.waveCount = waveCount;
        this.aliveRaiders = aliveRaiders;
        // 分母永不为 0，外观代码可以直接拿来算比例
        this.waveTotal = Math.max(1, waveTotal);
        this.endless = endless;
    }

    // 由袭击实例生成快照
    /**
     * Build a snapshot from a live raid.
     *
     * @param raid the raid to snapshot
     * @return the snapshot
     */
    public static RaidBarState of(RaidInstance raid) {
        RaidDefinition def = raid.getDefinition();
        return new RaidBarState(
                raid.getDefinitionId(),
                raid.getStatus(),
                raid.getWavesSpawned(),
                raid.getWaveCount(),
                raid.getAliveRaiderCount(),
                raid.getCurrentWaveTotal(),
                def != null && def.isEndless()
        );
    }

    // 获取袭击定义 ID
    public String getDefinitionId() {
        return definitionId;
    }

    // 获取袭击当前状态
    public RaidStatus getStatus() {
        return status;
    }

    // 获取已生成波次数
    public int getWavesSpawned() {
        return wavesSpawned;
    }

    // 获取定义的总波次数
    public int getWaveCount() {
        return waveCount;
    }

    // 获取当前存活袭击者数量
    public int getAliveRaiders() {
        return aliveRaiders;
    }

    // 获取当前波次生成的袭击者总数
    public int getWaveTotal() {
        return waveTotal;
    }

    // 是否为无限波次袭击
    public boolean isEndless() {
        return endless;
    }

    // 是否处于最后一波（无限波次恒为 false）
    /**
     * @return true if the current wave is the final declared wave
     */
    public boolean isFinalWave() {
        return !endless && waveCount > 0 && wavesSpawned >= waveCount;
    }

    // 袭击是否已分出胜负
    /**
     * @return true if the raid has ended in victory or defeat
     */
    public boolean isOver() {
        return status == RaidStatus.VICTORY || status == RaidStatus.DEFEAT;
    }

    // 当前波次的剩余比例（1.0 表示满员，0.0 表示已清空）
    /**
     * @return remaining fraction of the current wave, clamped to [0, 1]
     */
    public float getWaveProgress() {
        float progress = (float) aliveRaiders / (float) waveTotal;
        return progress < 0.0f ? 0.0f : (progress > 1.0f ? 1.0f : progress);
    }

    public static void encode(RaidBarState state, FriendlyByteBuf buf) {
        buf.writeUtf(state.definitionId);
        buf.writeEnum(state.status);
        buf.writeVarInt(state.wavesSpawned);
        buf.writeVarInt(state.waveCount);
        buf.writeVarInt(state.aliveRaiders);
        buf.writeVarInt(state.waveTotal);
        buf.writeBoolean(state.endless);
    }

    public static RaidBarState decode(FriendlyByteBuf buf) {
        return new RaidBarState(
                buf.readUtf(),
                buf.readEnum(RaidStatus.class),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean()
        );
    }
}
