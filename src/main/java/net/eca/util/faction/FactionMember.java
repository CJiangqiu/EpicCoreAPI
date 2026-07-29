package net.eca.util.faction;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;
import java.util.UUID;

/**
 * One entry in a faction's member table.
 *
 * <p>Records the entity's type alongside its UUID so that membership can be inspected
 * without loading the entity — listing a faction's roster, counting members by type, or
 * recalling a summoned creature that is currently in an unloaded chunk all work purely
 * from this table.</p>
 *
 * <p>Players are flagged explicitly because resolving one goes through the server's
 * player list rather than a level's entity lookup.</p>
 */
public final class FactionMember {

    private static final String NBT_UUID = "uuid";
    private static final String NBT_TYPE = "type";
    private static final String NBT_PLAYER = "player";

    private final UUID uuid;
    private final String typeId;
    private final boolean player;

    // 创建一条成员记录
    /**
     * @param uuid   the entity's UUID
     * @param typeId the entity type's registry id, e.g. {@code "minecraft:zombie"}
     * @param player whether this member is a player
     */
    public FactionMember(UUID uuid, String typeId, boolean player) {
        this.uuid = uuid;
        this.typeId = typeId == null ? "" : typeId;
        this.player = player;
    }

    // 由实体创建成员记录
    /**
     * Build a member record from a live entity, capturing its type for later offline use.
     *
     * @param entity the entity to record
     * @return the member record, or null if the entity is null
     */
    public static FactionMember of(Entity entity) {
        if (entity == null) return null;
        return new FactionMember(
                entity.getUUID(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                entity instanceof Player
        );
    }

    // 获取成员 UUID
    public UUID getUuid() {
        return uuid;
    }

    // 获取成员实体类型注册 ID
    /**
     * @return the entity type's registry id, e.g. {@code "minecraft:zombie"}
     */
    public String getTypeId() {
        return typeId;
    }

    // 是否为玩家成员
    /**
     * @return true if this member is a player, which must be resolved via the player list
     */
    public boolean isPlayer() {
        return player;
    }

    // 写入 NBT
    /**
     * @param tag the tag to write into
     * @return the same tag, for chaining
     */
    public CompoundTag save(CompoundTag tag) {
        tag.putUUID(NBT_UUID, uuid);
        tag.putString(NBT_TYPE, typeId);
        tag.putBoolean(NBT_PLAYER, player);
        return tag;
    }

    // 从 NBT 读取
    /**
     * @param tag the tag to read from
     * @return the member record, or null if the tag carries no valid UUID
     */
    public static FactionMember load(CompoundTag tag) {
        if (tag == null || !tag.hasUUID(NBT_UUID)) return null;
        return new FactionMember(
                tag.getUUID(NBT_UUID),
                tag.getString(NBT_TYPE),
                tag.getBoolean(NBT_PLAYER)
        );
    }

    // 成员身份由 UUID 唯一确定，类型与玩家标志只是附带信息
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FactionMember member)) return false;
        return Objects.equals(uuid, member.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(uuid);
    }

    @Override
    public String toString() {
        return "FactionMember{" + uuid + ", " + typeId + (player ? ", player" : "") + "}";
    }
}
