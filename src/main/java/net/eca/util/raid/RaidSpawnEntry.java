package net.eca.util.raid;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.function.Consumer;

/**
 * One explicit spawn instruction inside a {@link RaidWave}: an entity type, how many to
 * spawn, and an optional callback applied to each spawned mob (equipment, attributes, NBT).
 */
public class RaidSpawnEntry {

    private final EntityType<?> type;
    private final int count;
    private final Consumer<Mob> postSpawn;

    // 创建一条生成项
    /**
     * @param type  the entity type to spawn
     * @param count how many of this type to spawn in the wave
     */
    public RaidSpawnEntry(EntityType<?> type, int count) {
        this(type, count, null);
    }

    // 创建一条生成项（附生成后处理）
    /**
     * @param type      the entity type to spawn
     * @param count     how many of this type to spawn in the wave
     * @param postSpawn applied to each spawned mob after it joins the level; may be null
     */
    public RaidSpawnEntry(EntityType<?> type, int count, Consumer<Mob> postSpawn) {
        this.type = type;
        this.count = count;
        this.postSpawn = postSpawn;
    }

    // 获取实体类型
    public EntityType<?> getType() {
        return type;
    }

    // 获取生成数量
    public int getCount() {
        return count;
    }

    // 获取生成后处理回调（可能为 null）
    public Consumer<Mob> getPostSpawn() {
        return postSpawn;
    }
}
