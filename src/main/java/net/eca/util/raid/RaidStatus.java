package net.eca.util.raid;

/**
 * Lifecycle state of a single raid.
 */
public enum RaidStatus {

    // 进行中：波次推进与胜负判定均在此状态下进行
    ONGOING,

    // 胜利：防守方达成胜利条件，进入庆祝期后停止
    VICTORY,

    // 失败：袭击目标失守或自定义失败条件成立
    DEFEAT,

    // 已停止：不再 tick，等待从活跃表中移除
    STOPPED
}
