package net.eca.util.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

/*
 * 阵营全局持久化 — SavedData 存储于主世界 DataStorage。
 *
 * 每个阵营是一个自包含的 tag：定义、关系、首领、成员表全部嵌套其中。
 * 不再维护独立的成员映射表，成员归属只有阵营内的 members 一个权威来源，
 * 成员→阵营的反向索引由 FactionManager 在加载时派生。
 *
 * 不存储于实体 NBT；所有阵营信息由本类统一管理。
 */
public class FactionSavedData extends SavedData {

    private static final String DATA_NAME = "eca_factions";
    private static final String NBT_FACTIONS = "factions";

    static final String NBT_DISPLAY_NAME = "displayName";
    static final String NBT_COLOR        = "color";
    static final String NBT_DEFAULT_REL  = "defaultRelation";
    static final String NBT_RELATIONS    = "relations";
    static final String NBT_LEADER       = "leader";
    static final String NBT_MEMBERS      = "members";

    // factionId → 该阵营的完整 tag
    private final Map<String, CompoundTag> factionTags = new LinkedHashMap<>();

    // ==================== SavedData 生命周期 ====================

    public FactionSavedData() {}

    public static FactionSavedData load(CompoundTag tag) {
        FactionSavedData data = new FactionSavedData();
        if (tag.contains(NBT_FACTIONS, Tag.TAG_COMPOUND)) {
            CompoundTag factionsTag = tag.getCompound(NBT_FACTIONS);
            for (String factionId : factionsTag.getAllKeys()) {
                CompoundTag factionTag = factionsTag.getCompound(factionId);
                if (!factionTag.isEmpty()) {
                    data.factionTags.put(factionId, factionTag);
                }
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag factionsTag = new CompoundTag();
        for (Map.Entry<String, CompoundTag> entry : factionTags.entrySet()) {
            factionsTag.put(entry.getKey(), entry.getValue().copy());
        }
        if (!factionsTag.isEmpty()) {
            tag.put(NBT_FACTIONS, factionsTag);
        }
        return tag;
    }

    public static FactionSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            FactionSavedData::load,
            FactionSavedData::new,
            DATA_NAME
        );
    }

    // ==================== 阵营序列化 ====================

    // 将一个阵营完整写入（定义 + 关系 + 首领 + 成员）
    void putFaction(Faction faction) {
        factionTags.put(faction.getId(), serialize(faction));
        setDirty();
    }

    void removeFaction(String factionId) {
        if (factionTags.remove(factionId) != null) {
            setDirty();
        }
    }

    private static CompoundTag serialize(Faction faction) {
        CompoundTag tag = new CompoundTag();
        tag.putString(NBT_DISPLAY_NAME, faction.getDisplayName());
        tag.putInt(NBT_COLOR, faction.getColor());
        tag.putString(NBT_DEFAULT_REL, faction.getDefaultRelation().name());

        CompoundTag relTag = new CompoundTag();
        for (Map.Entry<String, FactionRelation> rel : faction.getRelations().entrySet()) {
            relTag.putString(rel.getKey(), rel.getValue().name());
        }
        if (!relTag.isEmpty()) {
            tag.put(NBT_RELATIONS, relTag);
        }

        FactionMember leader = faction.getLeader();
        if (leader != null) {
            tag.put(NBT_LEADER, leader.save(new CompoundTag()));
        }

        ListTag memberList = new ListTag();
        for (FactionMember member : faction.getMembers().values()) {
            memberList.add(member.save(new CompoundTag()));
        }
        if (!memberList.isEmpty()) {
            tag.put(NBT_MEMBERS, memberList);
        }
        return tag;
    }

    private static Faction deserialize(String id, CompoundTag tag) {
        FactionRelation defaultRel;
        try {
            defaultRel = FactionRelation.valueOf(tag.getString(NBT_DEFAULT_REL));
        } catch (IllegalArgumentException e) {
            defaultRel = FactionRelation.HOSTILE;
        }

        Faction faction = new Faction(id, tag.getString(NBT_DISPLAY_NAME), tag.getInt(NBT_COLOR), defaultRel);

        if (tag.contains(NBT_RELATIONS, Tag.TAG_COMPOUND)) {
            CompoundTag relTag = tag.getCompound(NBT_RELATIONS);
            for (String otherId : relTag.getAllKeys()) {
                try {
                    faction.setRelation(otherId, FactionRelation.valueOf(relTag.getString(otherId)));
                } catch (IllegalArgumentException ignored) {
                    // 非法关系名，跳过该条覆盖
                }
            }
        }

        if (tag.contains(NBT_LEADER, Tag.TAG_COMPOUND)) {
            faction.setLeader(FactionMember.load(tag.getCompound(NBT_LEADER)));
        }

        if (tag.contains(NBT_MEMBERS, Tag.TAG_LIST)) {
            ListTag memberList = tag.getList(NBT_MEMBERS, Tag.TAG_COMPOUND);
            for (int i = 0; i < memberList.size(); i++) {
                FactionMember member = FactionMember.load(memberList.getCompound(i));
                if (member != null) {
                    faction.addMember(member);
                }
            }
        }
        return faction;
    }

    // 加载全部阵营到 FactionManager
    void loadFactions() {
        for (Map.Entry<String, CompoundTag> entry : factionTags.entrySet()) {
            FactionManager.putLoadedFaction(deserialize(entry.getKey(), entry.getValue()));
        }
    }
}
