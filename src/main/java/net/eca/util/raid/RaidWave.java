package net.eca.util.raid;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/*
 * 一个袭击波次的内容。
 *
 * 两种生成源可在同一波内混用：
 *   - 显式条目：直接指定 EntityType 与数量
 *   - 阵营抽取：指定阵营 ID 与总数，按 FactionDefinition.getMemberEntityTypes() 的权重抽取
 *
 * 阵营抽取要求该阵营通过 @RegisterFaction 注册并声明了成员类型池，否则该条目在生成时被跳过并记日志。
 */
public class RaidWave {

    private final List<RaidSpawnEntry> entries = new ArrayList<>();
    private final Map<String, Integer> factionCounts = new LinkedHashMap<>();
    private int spawnDelayTicks = 0;
    private double spawnRadius = 24.0;

    // 添加显式实体条目
    /**
     * Add an explicit entity type to this wave.
     *
     * @param type  the entity type to spawn
     * @param count how many to spawn
     * @return this wave, for chaining
     */
    public RaidWave addEntry(EntityType<?> type, int count) {
        return addEntry(type, count, null);
    }

    // 添加显式实体条目（附生成后处理）
    /**
     * Add an explicit entity type with a per-mob post-spawn callback.
     *
     * @param type      the entity type to spawn
     * @param count     how many to spawn
     * @param postSpawn applied to each spawned mob; may be null
     * @return this wave, for chaining
     */
    public RaidWave addEntry(EntityType<?> type, int count, Consumer<Mob> postSpawn) {
        if (type != null && count > 0) {
            entries.add(new RaidSpawnEntry(type, count, postSpawn));
        }
        return this;
    }

    // 添加阵营抽取条目：从该阵营的成员类型池按权重抽取指定数量
    /**
     * Add a faction-drawn group to this wave. Instead of naming entity types, the wave
     * draws {@code count} entities from the faction's member pool by weight.
     * Repeated calls for the same faction accumulate.
     *
     * @param factionId the faction to draw from (must declare a member entity type pool)
     * @param count     how many entities to draw
     * @return this wave, for chaining
     */
    public RaidWave addFaction(String factionId, int count) {
        if (factionId != null && !factionId.isEmpty() && count > 0) {
            factionCounts.merge(factionId, count, Integer::sum);
        }
        return this;
    }

    // 设置本波生成前的额外延迟（tick）
    /**
     * Set an extra delay before this wave spawns, on top of the raid's wave cooldown.
     *
     * @param ticks delay in ticks
     * @return this wave, for chaining
     */
    public RaidWave spawnDelay(int ticks) {
        this.spawnDelayTicks = Math.max(0, ticks);
        return this;
    }

    // 设置本波围绕袭击中心的生成半径
    /**
     * Set the radius around the raid center within which this wave's entities spawn.
     *
     * @param radius spawn radius in blocks
     * @return this wave, for chaining
     */
    public RaidWave spawnRadius(double radius) {
        this.spawnRadius = Math.max(1.0, radius);
        return this;
    }

    // 获取全部显式条目（只读）
    public List<RaidSpawnEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    // 获取全部阵营抽取条目（阵营 ID → 数量，只读）
    public Map<String, Integer> getFactionCounts() {
        return Collections.unmodifiableMap(factionCounts);
    }

    // 获取本波额外生成延迟
    public int getSpawnDelayTicks() {
        return spawnDelayTicks;
    }

    // 获取本波生成半径
    public double getSpawnRadius() {
        return spawnRadius;
    }

    // 本波预计生成的实体总数（显式条目 + 阵营抽取）
    /**
     * Total number of entities this wave will attempt to spawn.
     *
     * @return combined count of explicit entries and faction-drawn entities
     */
    public int getTotalCount() {
        int total = 0;
        for (RaidSpawnEntry entry : entries) {
            total += entry.getCount();
        }
        for (Integer count : factionCounts.values()) {
            total += count;
        }
        return total;
    }
}
