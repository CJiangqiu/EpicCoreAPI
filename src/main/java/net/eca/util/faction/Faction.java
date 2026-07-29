package net.eca.util.faction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A faction (阵营) is a named group that entities can belong to.
 * <p>
 * Each faction has a unique {@code id}, a human-readable {@code displayName}, and an ARGB {@code color}.
 * Factions can define a default relation toward entities that have no faction,
 * as well as per-faction relation overrides via {@link #getRelation}.
 */
public class Faction {

    private final String id;
    private String displayName;
    private int color;
    private FactionRelation defaultRelation;
    private final Map<String, FactionRelation> relations;

    // 阵营首领，可为空；被永久移除时自动清空
    private FactionMember leader;

    // 成员表：本阵营成员的权威记录，成员→阵营的反向索引由 FactionManager 派生维护
    private final Map<UUID, FactionMember> members = new ConcurrentHashMap<>();

    // 创建一个阵营
    /**
     * Create a new faction.
     *
     * @param id          unique faction identifier (used for entity binding and relation lookups)
     * @param displayName human-readable display name
     * @param color       ARGB color for UI display (e.g. name tags)
     */
    public Faction(String id, String displayName, int color) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
        this.defaultRelation = FactionRelation.HOSTILE;
        this.relations = new HashMap<>();
    }

    // 创建一个阵营（指定默认对外关系）
    /**
     * Create a new faction with a custom default relation toward neutral entities.
     *
     * @param id              unique faction identifier
     * @param displayName     human-readable display name
     * @param color           ARGB color
     * @param defaultRelation the relation toward entities that belong to no faction
     */
    public Faction(String id, String displayName, int color, FactionRelation defaultRelation) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
        this.defaultRelation = defaultRelation;
        this.relations = new HashMap<>();
    }

    // ==================== 基础属性 ====================

    // 获取阵营唯一 ID
    public String getId() {
        return id;
    }

    // 获取阵营显示名称
    public String getDisplayName() {
        return displayName;
    }

    // 设置阵营显示名称
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    // 获取阵营颜色（ARGB）
    public int getColor() {
        return color;
    }

    // 设置阵营颜色（ARGB）
    public void setColor(int color) {
        this.color = color;
    }

    // ==================== 关系查询 ====================

    // 获取该阵营对"无阵营实体"的默认态度
    /**
     * Get the default relation this faction has toward entities that belong to no faction.
     *
     * @return default relation, never null
     */
    public FactionRelation getDefaultRelation() {
        return defaultRelation;
    }

    // 设置该阵营对"无阵营实体"的默认态度
    /**
     * Set the default relation toward entities that belong to no faction.
     *
     * @param defaultRelation the new default relation
     */
    public void setDefaultRelation(FactionRelation defaultRelation) {
        this.defaultRelation = defaultRelation;
    }

    // 查询该阵营对另一指定阵营的关系（无覆盖返回 null）
    /**
     * Get the relation this faction has toward another specific faction.
     * Returns {@code null} if no explicit relation has been set — callers should
     * fall back to symmetrically checking the other faction, then to {@link #getDefaultRelation}.
     *
     * @param otherFactionId the other faction's id
     * @return the explicit relation, or null if none set
     */
    public FactionRelation getRelation(String otherFactionId) {
        return relations.get(otherFactionId);
    }

    // 设置该阵营对另一阵营的关系
    /**
     * Set the relation this faction has toward another specific faction.
     *
     * @param otherFactionId the other faction's id
     * @param relation       the relation to set
     */
    public void setRelation(String otherFactionId, FactionRelation relation) {
        relations.put(otherFactionId, relation);
    }

    // 移除该阵营对另一阵营的关系覆盖
    /**
     * Remove an explicit relation override toward another faction,
     * reverting to symmetric fallback or default behavior.
     *
     * @param otherFactionId the other faction's id
     */
    public void removeRelation(String otherFactionId) {
        relations.remove(otherFactionId);
    }

    // 获取全部关系覆盖（只读）
    /**
     * Get an unmodifiable view of all per-faction relation overrides.
     *
     * @return read-only relations map
     */
    public Map<String, FactionRelation> getRelations() {
        return Collections.unmodifiableMap(relations);
    }

    // ==================== 首领 ====================

    // 获取阵营首领（无首领返回 null）
    /**
     * @return the faction leader, or null if this faction has none
     */
    public FactionMember getLeader() {
        return leader;
    }

    // 设置阵营首领
    /**
     * Set or clear the faction leader. The leader is not implicitly added to the member
     * table — {@link FactionManager} keeps the two consistent.
     *
     * @param leader the new leader, or null to clear
     */
    public void setLeader(FactionMember leader) {
        this.leader = leader;
    }

    // 是否有首领
    public boolean hasLeader() {
        return leader != null;
    }

    // 判断指定 UUID 是否为本阵营首领
    /**
     * @param uuid the entity UUID to test
     * @return true if this faction's leader has that UUID
     */
    public boolean isLeader(UUID uuid) {
        return leader != null && uuid != null && leader.getUuid().equals(uuid);
    }

    // ==================== 成员 ====================

    // 加入一名成员（同 UUID 覆盖，用于刷新类型信息）
    /**
     * @param member the member to add
     */
    public void addMember(FactionMember member) {
        if (member != null && member.getUuid() != null) {
            members.put(member.getUuid(), member);
        }
    }

    // 移除一名成员
    /**
     * @param uuid the member's UUID
     * @return the removed member, or null if it was not in this faction
     */
    public FactionMember removeMember(UUID uuid) {
        return uuid == null ? null : members.remove(uuid);
    }

    // 获取一名成员记录
    /**
     * @param uuid the member's UUID
     * @return the member record, or null
     */
    public FactionMember getMember(UUID uuid) {
        return uuid == null ? null : members.get(uuid);
    }

    // 是否包含指定成员
    public boolean hasMember(UUID uuid) {
        return uuid != null && members.containsKey(uuid);
    }

    // 获取全部成员记录（只读）
    /**
     * @return read-only view of every member record
     */
    public Map<UUID, FactionMember> getMembers() {
        return Collections.unmodifiableMap(members);
    }

    // 获取全部成员 UUID（只读）
    /**
     * @return read-only view of every member UUID
     */
    public Set<UUID> getMemberUuids() {
        return Collections.unmodifiableSet(members.keySet());
    }

    // 按实体类型筛选成员
    /**
     * Filter members by entity type without loading any entity.
     *
     * @param typeId the entity type registry id, e.g. {@code "minecraft:zombie"}
     * @return matching member records
     */
    public List<FactionMember> getMembersByType(String typeId) {
        List<FactionMember> result = new ArrayList<>();
        if (typeId == null) return result;
        for (FactionMember member : members.values()) {
            if (typeId.equals(member.getTypeId())) {
                result.add(member);
            }
        }
        return result;
    }

    // 获取成员数量
    public int getMemberCount() {
        return members.size();
    }

    // 清空全部成员
    public void clearMembers() {
        members.clear();
    }
}
