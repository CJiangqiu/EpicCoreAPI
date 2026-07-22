package net.eca.util.faction;

/**
 * Defines the relationship between two factions, or between a faction and an entity with no faction.
 */
public enum FactionRelation {

    // 敌对：可正常攻击与被攻击
    HOSTILE,

    // 中立：不会主动设为目标，但误伤仍会生效
    NEUTRAL,

    // 友好：不设目标、不造成伤害（不同阵营但结盟）
    FRIENDLY,

    // 同阵营：完全免伤且不设目标
    SAME_FACTION
}
