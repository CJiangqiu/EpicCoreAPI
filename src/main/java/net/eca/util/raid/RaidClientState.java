package net.eca.util.raid;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 袭击的客户端状态 —— BossEvent UUID → 袭击状态快照。
 *
 * 客户端不持有 RaidInstance，这张表是渲染层判断"这条血条属于哪场袭击、
 * 袭击进行到什么程度"的唯一依据，由 RaidBossBarSyncPacket 维护。
 */
@OnlyIn(Dist.CLIENT)
public final class RaidClientState {

    private static final Map<UUID, RaidBarState> BAR_STATES = new ConcurrentHashMap<>();

    private RaidClientState() {}

    // 写入或更新一条血条对应的袭击状态
    /**
     * @param bossEventId the boss event UUID
     * @param state       the raid snapshot, or null to drop the mapping
     */
    public static void setBarState(UUID bossEventId, RaidBarState state) {
        if (bossEventId == null) {
            return;
        }
        if (state == null) {
            BAR_STATES.remove(bossEventId);
            return;
        }
        BAR_STATES.put(bossEventId, state);
    }

    // 获取一条血条对应的袭击状态
    /**
     * @param bossEventId the boss event UUID
     * @return the raid snapshot, or null if this bar does not belong to a raid
     */
    public static RaidBarState getBarState(UUID bossEventId) {
        if (bossEventId == null) {
            return null;
        }
        return BAR_STATES.get(bossEventId);
    }

    // 移除一条映射
    /**
     * @param bossEventId the boss event UUID
     */
    public static void removeBarState(UUID bossEventId) {
        if (bossEventId != null) {
            BAR_STATES.remove(bossEventId);
        }
    }

    // 清除全部客户端状态，断开连接时调用，防止单人模式下静态状态跨存档残留
    public static void clearAll() {
        BAR_STATES.clear();
    }
}
