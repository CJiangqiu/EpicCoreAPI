package net.eca.util.bossshow;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * History tracker for BossShow playback. Mode is per-definition via {@link BossShowDefinition#allowRepeat()}:
 * <ul>
 *   <li><b>allowRepeat=false</b> (default) — Per-player: once a player has seen a cutscene id, they never
 *       see it again on the same character. Stored as a string set on player persistent NBT.</li>
 *   <li><b>allowRepeat=true</b> — Per-target-entity: a cutscene can re-play for the same player against a
 *       different entity. Stored as a UUID set per cutscene on entity NBT. Requires a non-null target.</li>
 * </ul>
 */
public final class BossShowHistory {

    private static final String ECA_ROOT = "eca_bossshow";
    //allowRepeat=false 模式：玩家 NBT 下的 string list of cutscene ids
    private static final String PLAYER_SEEN = "seen";
    //allowRepeat=true 模式：实体 NBT 下 map key=cutsceneId -> list of player UUIDs
    private static final String ENTITY_SEEN_BY_CUTSCENE = "seen_by";

    private BossShowHistory() {}

    //检查 player 对 target 是否已经看过 def
    public static boolean hasPlayed(ServerPlayer player, BossShowDefinition def, LivingEntity target) {
        if (player == null || def == null) return false;
        ResourceLocation cutsceneId = def.id();
        if (cutsceneId == null) return false;

        if (def.allowRepeat()) {
            //true：看 entity NBT 中 cutscene→uuids 里是否含有该玩家；无 target 等价于"没看过"
            if (target == null) return false;
            CompoundTag root = target.getPersistentData().getCompound(ECA_ROOT);
            CompoundTag seenMap = root.getCompound(ENTITY_SEEN_BY_CUTSCENE);
            ListTag uuids = seenMap.getList(cutsceneId.toString(), Tag.TAG_STRING);
            String pidStr = player.getUUID().toString();
            for (int i = 0; i < uuids.size(); i++) {
                if (pidStr.equals(uuids.getString(i))) return true;
            }
            return false;
        } else {
            //false：看 player NBT 中的 seen list
            CompoundTag root = player.getPersistentData().getCompound(ECA_ROOT);
            ListTag seen = root.getList(PLAYER_SEEN, Tag.TAG_STRING);
            String cid = cutsceneId.toString();
            for (int i = 0; i < seen.size(); i++) {
                if (cid.equals(seen.getString(i))) return true;
            }
            return false;
        }
    }

    //标记 player 对 target 已播放过 def
    public static void markPlayed(ServerPlayer player, BossShowDefinition def, LivingEntity target) {
        if (player == null || def == null) return;
        ResourceLocation cutsceneId = def.id();
        if (cutsceneId == null) return;

        if (def.allowRepeat()) {
            if (target == null) return;
            CompoundTag persistent = target.getPersistentData();
            CompoundTag root = persistent.getCompound(ECA_ROOT);
            CompoundTag seenMap = root.getCompound(ENTITY_SEEN_BY_CUTSCENE);
            ListTag uuids = seenMap.getList(cutsceneId.toString(), Tag.TAG_STRING);

            String pidStr = player.getUUID().toString();
            for (int i = 0; i < uuids.size(); i++) {
                if (pidStr.equals(uuids.getString(i))) return;
            }
            uuids.add(StringTag.valueOf(pidStr));

            seenMap.put(cutsceneId.toString(), uuids);
            root.put(ENTITY_SEEN_BY_CUTSCENE, seenMap);
            persistent.put(ECA_ROOT, root);
        } else {
            CompoundTag persistent = player.getPersistentData();
            CompoundTag root = persistent.getCompound(ECA_ROOT);
            ListTag seen = root.getList(PLAYER_SEEN, Tag.TAG_STRING);

            String cid = cutsceneId.toString();
            for (int i = 0; i < seen.size(); i++) {
                if (cid.equals(seen.getString(i))) return;
            }
            seen.add(StringTag.valueOf(cid));

            root.put(PLAYER_SEEN, seen);
            persistent.put(ECA_ROOT, root);
        }
    }

    //清除 player 的全部历史（OFF 模式）
    public static void clearPlayerHistory(ServerPlayer player) {
        if (player == null) return;
        CompoundTag persistent = player.getPersistentData();
        CompoundTag root = persistent.getCompound(ECA_ROOT);
        root.remove(PLAYER_SEEN);
        persistent.put(ECA_ROOT, root);
    }

    //清除指定 target 实体上的 cutscene 历史（ON 模式）
    public static void clearEntityHistory(LivingEntity target, ResourceLocation cutsceneId) {
        if (target == null || cutsceneId == null) return;
        CompoundTag persistent = target.getPersistentData();
        CompoundTag root = persistent.getCompound(ECA_ROOT);
        CompoundTag seenMap = root.getCompound(ENTITY_SEEN_BY_CUTSCENE);
        seenMap.remove(cutsceneId.toString());
        root.put(ENTITY_SEEN_BY_CUTSCENE, seenMap);
        persistent.put(ECA_ROOT, root);
    }

    //仅用于调试：获取玩家 UUID（保留引用避免未使用警告）
    public static UUID asUUID(ServerPlayer player) {
        return player.getUUID();
    }
}
