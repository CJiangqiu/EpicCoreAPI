package net.eca.util.bossshow;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Context passed to {@link BossShow#onMarkerEvent(String, BossShowContext)} when a marker
 * with an event id is reached on the server.
 */
public interface BossShowContext {
    //触发演出的目标实体
    LivingEntity target();

    //观看演出的玩家
    ServerPlayer viewer();

    //当前已播放的 tick 数（0 起，从 onStart 之后开始累加）
    int currentTick();

    //定义本身
    BossShowDefinition definition();
}
