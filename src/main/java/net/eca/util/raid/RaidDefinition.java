package net.eca.util.raid;

import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Base class for declarative raid definitions discovered via {@link net.eca.api.RegisterRaid}.
 *
 * <p>Only {@link #getId()}, {@link #getDisplayName()} and {@link #getWaves()} are required.
 * Everything else has a working default modelled on the vanilla raid, and every rule that
 * governs how the raid progresses or ends can be replaced by overriding a single method.</p>
 *
 * <p><b>Targeting.</b> A raid is normally anchored to a structure — override
 * {@link #getTargetStructure()} or {@link #getTargetStructureTag()}. Anchoring drives the
 * default defeat condition: the raid is lost when the target structure no longer covers
 * the raid center. A definition that declares neither runs unanchored, in which case
 * {@link #checkDefeat} must be overridden or the raid can only end by victory, timeout,
 * or an explicit call to end it.</p>
 *
 * <p><b>Waves.</b> Each {@link RaidWave} mixes explicit entity entries with faction-drawn
 * groups. Faction draws require the faction to declare
 * {@link net.eca.util.faction.FactionDefinition#getMemberEntityTypes()}.</p>
 *
 * <p><b>Example</b></p>
 * <pre>{@code
 * @RegisterRaid
 * public class UndeadSiege extends RaidDefinition {
 *
 *     @Override public String getId() { return "undead_siege"; }
 *     @Override public String getDisplayName() { return "raid.mymod.undead_siege"; }
 *     @Override public String getRaiderFactionId() { return "undead_legion"; }
 *
 *     @Override public ResourceKey<Structure> getTargetStructure() {
 *         return ResourceKey.create(Registries.STRUCTURE, new ResourceLocation("minecraft", "village_plains"));
 *     }
 *
 *     @Override public List<RaidWave> getWaves() {
 *         return List.of(
 *             new RaidWave().addEntry(EntityType.ZOMBIE, 6),
 *             new RaidWave().addFaction("undead_legion", 10),
 *             new RaidWave().addEntry(EntityType.WITHER_SKELETON, 4).addFaction("undead_legion", 8)
 *         );
 *     }
 * }
 * }</pre>
 *
 * @see net.eca.api.RegisterRaid
 * @see RaidManager
 */
public abstract class RaidDefinition {

    // ==================== 必须覆写 ====================

    // 袭击唯一 ID
    /**
     * @return unique raid identifier, used for registration and lookups
     */
    public abstract String getId();

    // 袭击显示名 / 翻译键
    /**
     * @return human-readable name or translation key, shown on the boss bar
     */
    public abstract String getDisplayName();

    // 袭击的波次列表
    /**
     * Define the raid's waves in order. Called once when the raid starts.
     * For endless raids (see {@link #isEndless()}) the list is cycled.
     *
     * @return ordered wave list; an empty list makes the raid end immediately
     */
    public abstract List<RaidWave> getWaves();

    // ==================== 可选覆写：目标锚定 ====================

    // 目标结构（与 getTargetStructureTag 二选一）
    /**
     * Anchor this raid to a specific structure.
     *
     * @return the target structure key, or null if anchoring by tag or not at all
     */
    public ResourceKey<Structure> getTargetStructure() {
        return null;
    }

    // 目标结构标签（与 getTargetStructure 二选一，允许一个袭击匹配多种结构）
    /**
     * Anchor this raid to any structure carrying a tag, letting one raid apply to
     * several structure types. Ignored when {@link #getTargetStructure()} is non-null.
     *
     * @return the target structure tag, or null
     */
    public TagKey<Structure> getTargetStructureTag() {
        return null;
    }

    // ==================== 可选覆写：袭击者 ====================

    // 袭击者所属阵营 ID
    /**
     * Faction that spawned raiders are bound to. Binding is what makes vanilla AI,
     * target selectors and ECA's attack rules treat raiders as hostile to defenders,
     * so a raid without a faction relies entirely on each entity's own AI.
     *
     * @return the raider faction id, or null to skip faction binding
     */
    public String getRaiderFactionId() {
        return null;
    }

    // 注入的"前往袭击中心"Goal 优先级
    /**
     * Priority of the injected {@code MoveToRaidCenterGoal}. The default of 3 matches
     * vanilla's {@code PathfindToRaidGoal}, which sits below the typical melee attack
     * goal (2) — raiders fight what is in front of them and only path to the center
     * when they have no target.
     *
     * @return goal priority; return a negative value to skip goal injection entirely
     */
    public int getRaiderGoalPriority() {
        return 3;
    }

    // ==================== 可选覆写：流程控制 ====================

    // 是否为无限波次袭击
    /**
     * Endless raids cycle {@link #getWaves()} forever and never satisfy the default
     * victory condition — they end only via timeout, defeat, or an explicit call to
     * {@link RaidManager#endRaid}, which clears any surviving raiders.
     *
     * @return true for an endless raid
     */
    public boolean isEndless() {
        return false;
    }

    // 是否应推进到下一波
    /**
     * Decide whether the next wave should spawn. Defaults to "the previous wave is dead",
     * matching vanilla. Override for timed waves, overlapping waves, or waves gated on
     * arbitrary world state.
     *
     * @param ctx the raid context
     * @return true to spawn the next wave
     */
    public boolean shouldAdvanceWave(RaidContext ctx) {
        return ctx.getAliveRaiderCount() == 0;
    }

    // 是否达成胜利条件
    /**
     * Decide whether the defenders have won. Defaults to "all waves spawned and every
     * raider dead". Never called for endless raids unless overridden to return true.
     *
     * @param ctx the raid context
     * @return true if the raid is won
     */
    public boolean checkVictory(RaidContext ctx) {
        if (isEndless()) return false;
        return ctx.isAllWavesSpawned() && ctx.getAliveRaiderCount() == 0;
    }

    // 是否达成失败条件
    /**
     * Decide whether the defenders have lost. Defaults to "the target structure no longer
     * covers the raid center", mirroring vanilla's "the center is no longer a village".
     * Unanchored raids never lose by default.
     *
     * @param ctx the raid context
     * @return true if the raid is lost
     */
    public boolean checkDefeat(RaidContext ctx) {
        return !ctx.isTargetIntact();
    }

    // ==================== 可选覆写：生命周期回调 ====================

    // 袭击开始时调用
    /**
     * @param ctx the raid context
     */
    public void onStart(RaidContext ctx) {
    }

    // 每波生成后调用
    /**
     * @param ctx       the raid context
     * @param waveIndex zero-based index of the wave that just spawned
     */
    public void onWaveStart(RaidContext ctx, int waveIndex) {
    }

    // 胜利时调用，发放奖励
    /**
     * Award victory rewards here. {@link RaidContext#getNearbyPlayers()} gives the
     * participating players.
     *
     * @param ctx the raid context
     */
    public void onVictory(RaidContext ctx) {
    }

    // 失败时调用
    /**
     * @param ctx the raid context
     */
    public void onDefeat(RaidContext ctx) {
    }

    // 袭击停止时调用（胜利、失败、超时、主动结束都会经过）
    /**
     * Final callback for any termination path — victory, defeat, timeout or an explicit
     * end call. Use it for cleanup that must run regardless of outcome.
     *
     * @param ctx the raid context
     */
    public void onStop(RaidContext ctx) {
    }

    // ==================== 可选覆写：参数与表现 ====================

    // Boss 血条颜色
    /**
     * @return boss bar color, defaults to red
     */
    public BossEvent.BossBarColor getBossBarColor() {
        return BossEvent.BossBarColor.RED;
    }

    // 袭击最长持续时间（tick），超时按失败处理
    /**
     * @return maximum raid duration in ticks; defaults to 48000 as in vanilla.
     *         Return a non-positive value for no time limit.
     */
    public int getMaxDurationTicks() {
        return 48000;
    }

    // 两波之间的冷却时间（tick）
    /**
     * @return ticks between one wave clearing and the next spawning; defaults to 300
     */
    public int getWaveCooldownTicks() {
        return 300;
    }

    // 参与半径：决定谁看到血条、谁获得奖励
    /**
     * @return radius in blocks around the raid center used to determine participating
     *         players for the boss bar and victory rewards
     */
    public double getParticipantRadius() {
        return 96.0;
    }

    // 胜负结算后血条保留的庆祝时长（tick）
    /**
     * @return how long the result stays on the boss bar before the raid is removed
     */
    public int getCelebrationTicks() {
        return 600;
    }

    // ==================== 可选覆写：血条外观 ====================

    // 自定义 Boss 血条外观（仅客户端）
    /**
     * Provide a custom appearance for this raid's boss bar. Returning null — the default —
     * leaves the vanilla bar in place, styled only by {@link #getBossBarColor()}.
     * <p>
     * The returned extension also needs {@link RaidBossBarExtension#enabled()} to return true
     * before it takes over rendering. Its condition methods receive a {@link RaidBarState}
     * snapshot synced from the server, so the bar can react to wave progress and outcome.
     *
     * @return the custom bar appearance, or null to keep the vanilla bar
     */
    @OnlyIn(Dist.CLIENT)
    public RaidBossBarExtension bossBarExtension() {
        return null;
    }
}
