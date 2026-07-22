package net.eca.util.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/*
 * 阵营全局持久化 — SavedData 存储于主世界 DataStorage。
 *
 * 存储内容：
 *   - 阵营定义：id → { displayName, color, defaultRelation, relations }
 *   - 实体绑定：entity UUID string → factionId
 *
 * 不存储于实体 NBT；所有阵营信息由本类统一管理。
 */
public class FactionSavedData extends SavedData {

    private static final String DATA_NAME = "eca_factions";
    private static final String NBT_FACTIONS = "factions";
    private static final String NBT_MEMBERS  = "members";

    static final String NBT_DISPLAY_NAME    = "displayName";
    static final String NBT_COLOR           = "color";
    static final String NBT_DEFAULT_REL     = "defaultRelation";
    static final String NBT_RELATIONS       = "relations";

    // 阵营定义
    private final Map<String, CompoundTag> factionTags = new LinkedHashMap<>();
    // 实体 UUID → factionId
    private final Map<UUID, String> memberMap = new HashMap<>();

    // ==================== SavedData 生命周期 ====================

    public FactionSavedData() {}

    public static FactionSavedData load(CompoundTag tag) {
        FactionSavedData data = new FactionSavedData();

        if (tag.contains(NBT_FACTIONS, 10)) {
            CompoundTag factionsTag = tag.getCompound(NBT_FACTIONS);
            for (String factionId : factionsTag.getAllKeys()) {
                CompoundTag factionTag = factionsTag.getCompound(factionId);
                if (!factionTag.isEmpty()) {
                    data.factionTags.put(factionId, factionTag);
                }
            }
        }

        if (tag.contains(NBT_MEMBERS, 10)) {
            CompoundTag membersTag = tag.getCompound(NBT_MEMBERS);
            for (String uuidStr : membersTag.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    String factionId = membersTag.getString(uuidStr);
                    if (!factionId.isEmpty()) {
                        data.memberMap.put(uuid, factionId);
                    }
                } catch (IllegalArgumentException ignored) {
                    // 非法 UUID 字符串，跳过
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

        CompoundTag membersTag = new CompoundTag();
        for (Map.Entry<UUID, String> entry : memberMap.entrySet()) {
            membersTag.putString(entry.getKey().toString(), entry.getValue());
        }
        if (!membersTag.isEmpty()) {
            tag.put(NBT_MEMBERS, membersTag);
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

    // ==================== 阵营定义操作 ====================

    void putFaction(Faction faction) {
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
        factionTags.put(faction.getId(), tag);
        setDirty();
    }

    void removeFaction(String factionId) {
        if (factionTags.remove(factionId) != null) {
            setDirty();
        }
    }

    void putRelation(String factionId, Faction faction) {
        // 全量重写该阵营的 tag
        putFaction(faction);
    }

    // 加载全部阵营定义到 FactionManager
    void loadFactionsInto(FactionManager target) {
        for (Map.Entry<String, CompoundTag> entry : factionTags.entrySet()) {
            String id = entry.getKey();
            CompoundTag tag = entry.getValue();
            String displayName = tag.getString(NBT_DISPLAY_NAME);
            int color = tag.getInt(NBT_COLOR);
            FactionRelation defaultRel;
            try {
                defaultRel = FactionRelation.valueOf(tag.getString(NBT_DEFAULT_REL));
            } catch (IllegalArgumentException e) {
                defaultRel = FactionRelation.HOSTILE;
            }
            Faction faction = new Faction(id, displayName, color, defaultRel);
            if (tag.contains(NBT_RELATIONS, 10)) {
                CompoundTag relTag = tag.getCompound(NBT_RELATIONS);
                for (String otherId : relTag.getAllKeys()) {
                    try {
                        faction.setRelation(otherId, FactionRelation.valueOf(relTag.getString(otherId)));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
            target.putLoadedFaction(faction);
        }
    }

    // 是否有阵营定义（用于判断是否需要初始化加载）
    boolean hasFactions() {
        return !factionTags.isEmpty();
    }

    // ==================== 实体绑定操作 ====================

    void addMember(UUID entityUuid, String factionId) {
        String old = memberMap.put(entityUuid, factionId);
        if (!factionId.equals(old)) {
            setDirty();
        }
    }

    void removeMember(UUID entityUuid) {
        if (memberMap.remove(entityUuid) != null) {
            setDirty();
        }
    }

    String getMemberFaction(UUID entityUuid) {
        return memberMap.get(entityUuid);
    }

    // 加载全部成员映射到 FactionManager
    void loadMembersInto(FactionManager target) {
        for (Map.Entry<UUID, String> entry : memberMap.entrySet()) {
            target.putLoadedMember(entry.getKey(), entry.getValue());
        }
    }

    boolean hasMember(UUID entityUuid) {
        return memberMap.containsKey(entityUuid);
    }
}
