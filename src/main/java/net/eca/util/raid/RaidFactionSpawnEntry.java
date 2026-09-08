package net.eca.util.raid;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A faction-bound spawn group with its own per-raid entity weights.
 */
public final class RaidFactionSpawnEntry {

    private final String factionId;
    private final int count;
    private final Map<EntityType<?>, Integer> typeWeights;

    // 创建一条袭击阵营生成项
    /**
     * Create a faction-bound spawn group with weights owned by the raid definition.
     *
     * @param factionId   faction to bind spawned entities to
     * @param count       number of entities to spawn
     * @param typeWeights entity type to relative weight map
     */
    public RaidFactionSpawnEntry(String factionId, int count,
                                 Map<EntityType<?>, Integer> typeWeights) {
        this.factionId = factionId;
        this.count = count;
        Map<EntityType<?>, Integer> weights = new LinkedHashMap<>();
        if (typeWeights != null) {
            for (Map.Entry<EntityType<?>, Integer> entry : typeWeights.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                    weights.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.typeWeights = Collections.unmodifiableMap(weights);
    }

    // 获取袭击阵营 ID
    /**
     * @return faction id to bind spawned entities to
     */
    public String getFactionId() {
        return factionId;
    }

    // 获取生成数量
    /**
     * @return number of entities to spawn
     */
    public int getCount() {
        return count;
    }

    // 获取本条目的实体类型权重
    /**
     * @return immutable entity type to relative weight map
     */
    public Map<EntityType<?>, Integer> getTypeWeights() {
        return typeWeights;
    }

    // 检查是否存在可用权重
    /**
     * @return true when at least one entity type has a positive weight
     */
    public boolean hasUsableWeights() {
        return !typeWeights.isEmpty();
    }

    // 按本条目权重随机抽取实体类型
    /**
     * Pick one entity type using only this raid entry's weights.
     *
     * @param random random source used for selection
     * @return a weighted-random entity type, or null when no usable weight exists
     */
    public EntityType<?> rollType(RandomSource random) {
        if (random == null || typeWeights.isEmpty()) return null;
        int totalWeight = 0;
        for (Integer weight : typeWeights.values()) {
            totalWeight += weight;
        }
        int roll = random.nextInt(totalWeight);
        for (Map.Entry<EntityType<?>, Integer> entry : typeWeights.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) return entry.getKey();
        }
        return null;
    }
}
