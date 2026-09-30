package net.eca.util.bossshow;

import net.eca.config.EcaConfiguration;
import net.eca.network.BossShowStartPacket;
import net.eca.network.BossShowStopPacket;
import net.eca.network.BossShowSubtitlePacket;
import net.eca.network.NetworkHandler;
import net.eca.util.EcaLogger;
import net.eca.util.bossshow.BossShowDefinition.Frame;
import net.eca.util.bossshow.BossShowDefinition.EventCue;
import net.eca.util.bossshow.BossShowDefinition.SubtitleCue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side BossShow playback engine.
 *
 * Each session is advanced one tick per server tick, dispatching keyframe events as
 * they are crossed and ending when all frames have been consumed.
 */
public final class BossShowPlaybackTracker {

    private static final String NBT_PLAYBACK_MODE = "eca_bossshow_playback";
    private static final String NBT_PREVIOUS_GAME_MODE = "previous_game_mode";
    private static final Map<UUID, BossShowSession> ACTIVE = new ConcurrentHashMap<>();
    private static int rangeScanTickCounter = 0;

    private BossShowPlaybackTracker() {}

    public static boolean start(ServerPlayer viewer, LivingEntity target, BossShowDefinition def, boolean bypassHistory) {
        if (viewer == null || def == null || target == null) return false;
        if (ACTIVE.containsKey(viewer.getUUID())) {
            EcaLogger.info("BossShow skipped: viewer {} already has active session", viewer.getName().getString());
            return false;
        }
        if (!bypassHistory && BossShowHistory.hasPlayed(viewer, def, target)) {
            return false;
        }
        if (def.isEmpty()) return false;
        if (!enterPlaybackMode(viewer)) return false;

        double ax = target.getX();
        double ay = target.getY();
        double az = target.getZ();
        //帧位姿位于目标实体局部坐标系，播放开始时按触发实体朝向还原。
        float ayaw = target.getYRot();

        BossShowSession session = new BossShowSession(viewer, target, def, ax, ay, az, ayaw);
        ACTIVE.put(viewer.getUUID(), session);

        NetworkHandler.sendToPlayer(new BossShowStartPacket(def, target.getUUID(), ax, ay, az, ayaw), viewer);

        BossShow hook = BossShowManager.getCodeHook(def.id());
        if (hook != null) {
            try {
                hook.onStart(session);
            } catch (Throwable t) {
                EcaLogger.error("BossShow {} onStart hook threw: {}", def.id(), t.getMessage());
            }
        }
        return true;
    }

    public static void stop(ServerPlayer viewer, boolean skipped) {
        if (viewer == null) return;
        BossShowSession session = ACTIVE.remove(viewer.getUUID());
        if (session == null) {
            restorePlaybackMode(viewer);
            return;
        }
        session.finished = true;

        try {
            BossShowHistory.markPlayed(viewer, session.definition, session.target);
            NetworkHandler.sendToPlayer(new BossShowStopPacket(session.definition.id(), skipped), viewer);

            BossShow hook = BossShowManager.getCodeHook(session.definition.id());
            if (hook != null) {
                try {
                    hook.onEnd(session, skipped);
                } catch (Throwable t) {
                    EcaLogger.error("BossShow {} onEnd hook threw: {}", session.definition.id(), t.getMessage());
                }
            }
        } finally {
            restorePlaybackMode(viewer);
        }
    }

    public static void onClientSkip(ServerPlayer viewer) {
        stop(viewer, true);
    }

    public static void onPlayerLogout(ServerPlayer viewer) {
        BossShowSession session = ACTIVE.remove(viewer.getUUID());
        if (session != null) {
            session.finished = true;
        }
        restorePlaybackMode(viewer);
    }

    // 登录时残留标记说明上次播放未正常结束，必须恢复进入播放前的模式。
    public static void recoverStaleSession(ServerPlayer viewer) {
        if (viewer != null && viewer.getPersistentData().contains(NBT_PLAYBACK_MODE, Tag.TAG_COMPOUND)) {
            restorePlaybackMode(viewer);
        }
    }

    public static boolean isPlaying(ServerPlayer viewer) {
        return viewer != null && ACTIVE.containsKey(viewer.getUUID());
    }

    public static BossShowSession getActiveSession(ServerPlayer viewer) {
        return viewer != null ? ACTIVE.get(viewer.getUUID()) : null;
    }

    //服务端每 tick 调用一次：推进所有 session + 周期性扫描所有维度的 range 触发器
    public static void onServerTick(net.minecraft.server.MinecraftServer server) {
        if (!ACTIVE.isEmpty()) {
            List<BossShowSession> toFinish = new ArrayList<>();
            for (BossShowSession session : ACTIVE.values()) {
                if (session.finished) continue;
                tickSession(session, toFinish);
            }
            for (BossShowSession finish : toFinish) {
                stop(finish.viewer, false);
            }
        }

        if (server != null) {
            rangeScanTickCounter++;
            if (rangeScanTickCounter >= EcaConfiguration.getBossShowRangeScanIntervalTicksSafely()) {
                rangeScanTickCounter = 0;
                for (ServerLevel level : server.getAllLevels()) {
                    scanRangeTriggers(level);
                }
            }
        }
    }

    private static void tickSession(BossShowSession session, List<BossShowSession> toFinish) {
        ServerPlayer viewer = session.viewer;
        if (viewer == null || viewer.hasDisconnected()) {
            toFinish.add(session);
            return;
        }

        int total = session.definition.totalDurationTicks();
        session.ticksElapsed++;

        //逐帧推进派发指针，按独立内容轨道触发事件/字幕
        //条件 nextDispatchIndex < ticksElapsed 保证帧 i 在第 i+1 tick 时派发
        List<Frame> frames = session.definition.frames();
        while (session.nextDispatchIndex < frames.size()
            && session.nextDispatchIndex < session.ticksElapsed) {
            int tick = session.nextDispatchIndex;
            for (SubtitleCue cue : session.definition.subtitleCues()) {
                if (cue.tick() == tick && cue.text() != null) {
                    dispatchSubtitle(session, cue.text());
                }
            }
            for (EventCue cue : session.definition.eventCues()) {
                if (cue.tick() == tick && cue.eventId() != null) {
                    dispatchKeyframeEvent(session, cue.eventId());
                }
            }
            session.nextDispatchIndex++;
        }

        if (session.ticksElapsed >= total) {
            toFinish.add(session);
        }
    }

    //字幕文本特殊值 "clear" 表示清空当前字幕
    private static void dispatchSubtitle(BossShowSession session, String text) {
        if ("clear".equals(text)) text = "";
        NetworkHandler.sendToPlayer(new BossShowSubtitlePacket(text), session.viewer);
    }

    private static void dispatchKeyframeEvent(BossShowSession session, String eventId) {
        BossShow hook = BossShowManager.getCodeHook(session.definition.id());
        if (hook == null) return;
        try {
            hook.onKeyframeEvent(eventId, session);
        } catch (Throwable t) {
            EcaLogger.error("BossShow {} onKeyframeEvent({}) threw: {}", session.definition.id(), eventId, t.getMessage());
        }
    }

    private static void scanRangeTriggers(ServerLevel level) {
        Map<EntityType<?>, List<BossShowDefinition>> grouped = new HashMap<>();
        for (BossShowDefinition def : BossShowManager.getAllDefinitions().values()) {
            if (def.targetType() == null) continue;
            if (def.trigger() instanceof Trigger.Range) {
                grouped.computeIfAbsent(def.targetType(), k -> new ArrayList<>()).add(def);
            }
        }
        if (grouped.isEmpty()) return;

        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) continue;
            List<BossShowDefinition> candidates = grouped.get(entity.getType());
            if (candidates == null) continue;

            for (BossShowDefinition def : candidates) {
                Trigger.Range range = (Trigger.Range) def.trigger();
                double radiusSq = range.effectRadius() * range.effectRadius();

                for (ServerPlayer player : level.players()) {
                    if (ACTIVE.containsKey(player.getUUID())) continue;
                    if (player.distanceToSqr(living) > radiusSq) continue;
                    if (BossShowHistory.hasPlayed(player, def, living)) continue;
                    start(player, living, def, false);
                    break;
                }
            }
        }
    }

    public static Map<UUID, BossShowSession> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(ACTIVE));
    }

    public static void clearAll() {
        for (BossShowSession session : ACTIVE.values()) {
            session.finished = true;
            restorePlaybackMode(session.viewer);
        }
        ACTIVE.clear();
    }

    // 播放模式使用独立快照，使编辑器内试播结束后仍回到编辑器的旁观状态。
    private static boolean enterPlaybackMode(ServerPlayer viewer) {
        CompoundTag persistent = viewer.getPersistentData();
        boolean createdSnapshot = !persistent.contains(NBT_PLAYBACK_MODE, Tag.TAG_COMPOUND);
        if (createdSnapshot) {
            CompoundTag root = new CompoundTag();
            root.putString(NBT_PREVIOUS_GAME_MODE, viewer.gameMode.getGameModeForPlayer().getName());
            persistent.put(NBT_PLAYBACK_MODE, root);
        }
        if (viewer.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
            return true;
        }
        if (viewer.setGameMode(GameType.SPECTATOR)) {
            return true;
        }
        if (createdSnapshot) {
            persistent.remove(NBT_PLAYBACK_MODE);
        }
        EcaLogger.info("BossShow start rejected because spectator mode could not be applied, uuid={}",
                viewer.getUUID());
        return false;
    }

    private static void restorePlaybackMode(ServerPlayer viewer) {
        if (viewer == null) return;
        CompoundTag persistent = viewer.getPersistentData();
        if (!persistent.contains(NBT_PLAYBACK_MODE, Tag.TAG_COMPOUND)) return;

        CompoundTag root = persistent.getCompound(NBT_PLAYBACK_MODE);
        GameType previous = GameType.byName(
                root.getString(NBT_PREVIOUS_GAME_MODE), GameType.SURVIVAL);
        persistent.remove(NBT_PLAYBACK_MODE);
        if (viewer.gameMode.getGameModeForPlayer() != previous && !viewer.setGameMode(previous)) {
            EcaLogger.info("BossShow could not restore game mode, uuid={}, mode={}",
                    viewer.getUUID(), previous.getName());
        }
    }
}
