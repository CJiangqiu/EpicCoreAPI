package net.eca.client;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 阵营发光数据 — 客户端侧存储实体 ID → 发光颜色的映射。
 *
 * 由 FactionGlowSyncPacket 更新，由 EntityMixin 的 isCurrentlyGlowing / getTeamColor 注入消费。
 * 复用原版队伍发光描边系统，无需自定义渲染。
 */
public class FactionGlowData {

    // entityId → ARGB color
    private static final Map<Integer, Integer> GLOW_MAP = new ConcurrentHashMap<>();
    private static long expireTime = 0;

    private FactionGlowData() {}

    // 全量替换发光映射，并设置过期时间
    /**
     * Replace the glow map with new scan results and set an expiry timestamp.
     *
     * @param entityColorMap entity id → ARGB color
     * @param durationTicks  how long this data is valid, in ticks
     */
    public static void update(Map<Integer, Integer> entityColorMap, int durationTicks) {
        GLOW_MAP.clear();
        if (entityColorMap != null) {
            GLOW_MAP.putAll(entityColorMap);
        }
        expireTime = System.currentTimeMillis() + durationTicks * 50L;
    }

    // 检查实体是否应发光（在有效期内且存在于映射中）
    /**
     * Check whether an entity should render with a faction glow outline.
     *
     * @param entityId the entity's network id
     * @return true if the entity is within the glow map and the data hasn't expired
     */
    public static boolean isGlowing(int entityId) {
        return System.currentTimeMillis() < expireTime && GLOW_MAP.containsKey(entityId);
    }

    // 获取实体发光颜色（未找到返回不透明白色）
    /**
     * Get the ARGB glow color for an entity.
     *
     * @param entityId the entity's network id
     * @return ARGB color int, or opaque white if not found
     */
    public static int getColor(int entityId) {
        return GLOW_MAP.getOrDefault(entityId, 0xFFFFFFFF);
    }

    // 获取当前发光映射（只读）
    /**
     * Get an unmodifiable view of the current glow map.
     *
     * @return read-only entity id → color map
     */
    public static Map<Integer, Integer> getGlowMap() {
        return Collections.unmodifiableMap(GLOW_MAP);
    }

    // 清空全部数据（登出时调用）
    /**
     * Clear all glow data. Called on client logout to prevent stale state.
     */
    public static void clear() {
        GLOW_MAP.clear();
        expireTime = 0;
    }
}
