package net.eca.util.bossshow;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

//服务端正在播放的一个会话
public final class BossShowSession implements BossShowContext {

    public final ServerPlayer viewer;
    public final LivingEntity target;
    public final UUID targetUuid;
    public final BossShowDefinition definition;

    public final double anchorX;
    public final double anchorY;
    public final double anchorZ;
    public final float anchorYaw;

    public int ticksElapsed = 0;
    //下一个待检查关键帧派发的帧下标（exclusive upper bound of already-dispatched frames）
    public int nextDispatchIndex = 0;
    public boolean finished = false;

    public BossShowSession(ServerPlayer viewer, LivingEntity target, BossShowDefinition definition,
                           double anchorX, double anchorY, double anchorZ, float anchorYaw) {
        this.viewer = viewer;
        this.target = target;
        this.targetUuid = target != null ? target.getUUID() : null;
        this.definition = definition;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.anchorYaw = anchorYaw;
    }

    @Override public LivingEntity target() { return target; }
    @Override public ServerPlayer viewer() { return viewer; }
    @Override public int currentTick() { return ticksElapsed; }
    @Override public BossShowDefinition definition() { return definition; }
}
