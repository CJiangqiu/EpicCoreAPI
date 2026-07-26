package net.eca.util.raid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/*
 * 传给 RaidDefinition 各回调的上下文快照。
 *
 * 提供袭击的只读视图与常用查询，使可插拔的波次推进、胜负判定和奖励回调
 * 无需直接操作 RaidInstance 的内部状态。
 */
public class RaidContext {

    private final ServerLevel level;
    private final RaidInstance raid;

    RaidContext(ServerLevel level, RaidInstance raid) {
        this.level = level;
        this.raid = raid;
    }

    // 获取袭击所在的服务端世界
    public ServerLevel getLevel() {
        return level;
    }

    // 获取袭击实例本体
    public RaidInstance getRaid() {
        return raid;
    }

    // 获取袭击定义
    public RaidDefinition getDefinition() {
        return raid.getDefinition();
    }

    // 获取袭击中心坐标
    public BlockPos getCenter() {
        return raid.getCenter();
    }

    // 获取已生成的波次数量
    /**
     * @return how many waves have been spawned so far (0 before the first wave)
     */
    public int getWavesSpawned() {
        return raid.getWavesSpawned();
    }

    // 获取定义的总波次数量
    /**
     * @return the number of waves this raid's definition declares
     */
    public int getWaveCount() {
        return raid.getWaveCount();
    }

    // 是否已生成全部波次（无限波次袭击恒为 false）
    /**
     * @return true if every declared wave has been spawned; always false for endless raids
     */
    public boolean isAllWavesSpawned() {
        return raid.isAllWavesSpawned();
    }

    // 获取当前存活的袭击者数量
    public int getAliveRaiderCount() {
        return raid.getAliveRaiderCount();
    }

    // 获取全部袭击者 UUID（只读）
    public Set<UUID> getRaiderUuids() {
        return raid.getRaiderUuids();
    }

    // 获取当前仍存在于世界中的袭击者实体
    /**
     * Resolve the tracked raider UUIDs to live entities. Raiders in unloaded chunks
     * resolve to null and are omitted, so this list may be shorter than
     * {@link #getAliveRaiderCount()}.
     *
     * @return currently resolvable raider entities
     */
    public List<Entity> getAliveRaiders() {
        List<Entity> result = new ArrayList<>();
        for (UUID uuid : raid.getRaiderUuids()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null && entity.isAlive()) {
                result.add(entity);
            }
        }
        return result;
    }

    // 获取袭击已持续的 tick 数
    public long getTicksActive() {
        return raid.getTicksActive();
    }

    // 袭击目标是否完好（结构锚定袭击查询结构是否仍在中心处）
    /**
     * @return true if the raid's target structure still covers the raid center;
     *         always true for raids started without structure anchoring
     */
    public boolean isTargetIntact() {
        return raid.isTargetIntact(level);
    }

    // 获取参与范围内的玩家
    /**
     * Get players within the definition's participant radius of the raid center.
     * These are the players shown the boss bar and eligible for victory rewards.
     *
     * @return participating players, may be empty
     */
    public List<ServerPlayer> getNearbyPlayers() {
        double radius = getDefinition().getParticipantRadius();
        double radiusSq = radius * radius;
        BlockPos center = getCenter();
        List<ServerPlayer> result = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            if (player.distanceToSqr(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5) > radiusSq) {
                continue;
            }
            result.add(player);
        }
        return result;
    }
}
