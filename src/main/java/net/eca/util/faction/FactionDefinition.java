package net.eca.util.faction;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Base class for declarative faction definitions discovered via {@link net.eca.api.RegisterFaction}.
 *
 * <p>Overriding the base methods provides static faction metadata (id, name, color, preset relations).
 * Overriding the conditional methods provides dynamic, per-entity or per-target relation logic —
 * return {@code null} to fall back to the static defaults.</p>
 *
 * <p>Relation resolution priority in {@link FactionManager#getEffectiveRelation}:</p>
 * <ol>
 *   <li>SAME_FACTION (same faction id, always honoured)</li>
 *   <li>{@link #getRelation(LivingEntity, Entity)} — dynamic per-target override</li>
 *   <li>Static {@code hostileTo / friendlyTo / neutralTo} arrays</li>
 *   <li>Symmetric fallback (check the other faction's definition)</li>
 *   <li>{@link #getDefaultRelation(LivingEntity, Entity)} — dynamic per-target default override</li>
 *   <li>Static {@link #getDefaultRelation()}</li>
 * </ol>
 */
public abstract class FactionDefinition {

    // ==================== 必须覆写 ====================

    // 阵营唯一 ID
    /**
     * @return unique faction identifier (used for entity binding and relation lookups)
     */
    public abstract String getId();

    // 阵营显示名 / 翻译键
    /**
     * @return human-readable display name, or a translation key (e.g. {@code "faction.mymod.undead.name"})
     */
    public abstract String getDisplayName();

    // ==================== 可选覆写：静态元数据 ====================

    // 阵营颜色（ARGB），默认白色
    /**
     * @return ARGB color for UI display (name tags, glow outline, etc.). Defaults to opaque white.
     */
    public int getColor() {
        return 0xFFFFFFFF;
    }

    // 对无阵营实体的默认态度
    /**
     * @return the default relation this faction has toward entities that belong to no faction.
     *         Defaults to {@link FactionRelation#HOSTILE}.
     */
    public FactionRelation getStaticDefaultRelation() {
        return FactionRelation.HOSTILE;
    }

    // ==================== 可选覆写：预设固定关系 ====================

    // 预设敌对阵营 ID 列表
    /**
     * @return faction ids that this faction is hostile toward by default. Defaults to empty.
     */
    public String[] getHostileTo() {
        return new String[0];
    }

    // 预设友好阵营 ID 列表
    /**
     * @return faction ids that this faction is friendly toward by default. Defaults to empty.
     */
    public String[] getFriendlyTo() {
        return new String[0];
    }

    // 预设中立阵营 ID 列表
    /**
     * @return faction ids that this faction is neutral toward by default. Defaults to empty.
     */
    public String[] getNeutralTo() {
        return new String[0];
    }

    // ==================== 可选覆写：条件方法 ====================

    /*
     * 动态判断本阵营对某个具体实体的关系。
     * 返回 null → 回退到静态 hostileTo/friendlyTo/neutralTo 预设。
     * self 为本阵营内某个具体成员（可能为 null 当无实体上下文时）。
     */
    /**
     * Dynamically determine this faction's relation toward a specific target entity.
     * Override to implement per-entity conditional logic (e.g. player level check, weather, etc.).
     *
     * @param self   a faction member entity (may be null if the query has no self-entity context)
     * @param target the target entity to evaluate
     * @return the relation, or {@code null} to fall back to the static preset arrays
     */
    public FactionRelation getRelation(LivingEntity self, Entity target) {
        return null;
    }

    /*
     * 动态判断本阵营对无阵营实体的态度。
     * 返回 null → 回退到 getStaticDefaultRelation()。
     */
    /**
     * Dynamically determine this faction's default relation toward an entity that has no faction.
     * Override to implement conditional logic (e.g. weather-based, time-of-day-based).
     *
     * @param self   a faction member entity (may be null)
     * @param target the target entity (guaranteed to have no faction)
     * @return the relation, or {@code null} to fall back to {@link #getStaticDefaultRelation()}
     */
    public FactionRelation getDefaultRelation(LivingEntity self, Entity target) {
        return null;
    }
}
