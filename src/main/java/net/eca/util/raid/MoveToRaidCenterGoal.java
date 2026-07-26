package net.eca.util.raid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/*
 * 引导袭击者前往袭击中心。
 *
 * 注入到每个生成的袭击者身上，使任意实体类型无需实现任何接口即可参与袭击。
 * 优先级由 RaidDefinition.getRaiderGoalPriority() 决定（默认 3，对齐原版 PathfindToRaidGoal），
 * 低于典型的近战攻击 Goal，因此袭击者优先攻击眼前目标，无目标时才向中心推进。
 */
public class MoveToRaidCenterGoal extends Goal {

    // 抵达判定距离：进入该范围后不再寻路，避免全部袭击者挤在中心点
    private static final double ARRIVAL_DISTANCE_SQ = 12.0 * 12.0;
    // 路径重算间隔，避免每 tick 重新寻路
    private static final int RECALC_INTERVAL = 40;

    private final Mob mob;
    private final RaidInstance raid;
    private final double speedModifier;
    private int recalcCooldown;

    // 创建前往袭击中心的 Goal
    /**
     * @param mob           the raider to steer
     * @param raid          the raid providing the destination
     * @param speedModifier movement speed multiplier
     */
    public MoveToRaidCenterGoal(Mob mob, RaidInstance raid, double speedModifier) {
        this.mob = mob;
        this.raid = raid;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (raid.getStatus() != RaidStatus.ONGOING) return false;
        if (!mob.isAlive() || mob.getTarget() != null) return false;
        return mob.distanceToSqr(centerVecX(), centerVecY(), centerVecZ()) > ARRIVAL_DISTANCE_SQ;
    }

    @Override
    public boolean canContinueToUse() {
        // 不检查导航是否结束：寻路失败时由 tick 周期性重算，否则会与 canUse 形成启停抖动
        return canUse();
    }

    @Override
    public void start() {
        recalcCooldown = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (--recalcCooldown > 0) return;
        recalcCooldown = RECALC_INTERVAL;
        mob.getNavigation().moveTo(centerVecX(), centerVecY(), centerVecZ(), speedModifier);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return false;
    }

    private double centerVecX() {
        return raid.getCenter().getX() + 0.5;
    }

    private double centerVecY() {
        return raid.getCenter().getY();
    }

    private double centerVecZ() {
        return raid.getCenter().getZ() + 0.5;
    }

    // 获取该 Goal 服务的袭击实例
    /**
     * @return the raid this goal steers toward
     */
    public RaidInstance getRaid() {
        return raid;
    }

    // 获取袭击中心（便于调试与外部查询）
    /**
     * @return the current raid center
     */
    public BlockPos getCenter() {
        return raid.getCenter();
    }
}
