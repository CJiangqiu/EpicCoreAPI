package net.eca.util.bossshow;

import net.eca.config.EcaConfiguration;
import net.eca.network.BossShowStartPacket;
import net.eca.network.BossShowStopPacket;
import net.eca.network.BossShowSubtitlePacket;
import net.eca.network.NetworkHandler;
import net.eca.util.EcaLogger;
import net.eca.util.bossshow.BossShowDefinition.Marker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

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
 * Each session is advanced one tick per server tick, dispatching marker events as
 * they are crossed and ending when all samples have been consumed.
 */
public final class BossShowPlaybackTracker {

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

        double ax = target.getX();
        double ay = target.getY();
        double az = target.getZ();
        //anchor yaw 直接用 def 录制时烤入的值；保证回放和录制用同一坐标系
        float ayaw = def.anchorYawDeg();

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
        if (session == null) return;
        session.finished = true;

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
    }

    public static void onClientSkip(ServerPlayer viewer) {
        stop(viewer, true);
    }

    public static void onPlayerLogout(ServerPlayer viewer) {
        ACTIVE.remove(viewer.getUUID());
    }

    public static boolean isPlaying(ServerPlayer viewer) {
        return viewer != null && ACTIVE.containsKey(viewer.getUUID());
    }

    public static BossShowSession getActiveSession(ServerPlayer viewer) {
        return viewer != null ? ACTIVE.get(viewer.getUUID()) : null;
    }

    //服务端每 tick 调用一次（与维度数量无关）：推进所有 session + 周期性扫描所有维度的 range 触发器
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

        //事件/字幕分发：所有 tickOffset <= ticksElapsed 的 marker 都派发
        //两个字段独立：一个 marker 可同时触发字幕 + 用户事件 hook
        List<Marker> markers = session.definition.markers();
        while (session.nextMarkerIndex < markers.size()
            && markers.get(session.nextMarkerIndex).tickOffset() <= session.ticksElapsed) {
            Marker m = markers.get(session.nextMarkerIndex);
            if (m.subtitleText() != null) {
                dispatchSubtitle(session, m.subtitleText());
            }
            if (m.eventId() != null) {
                dispatchMarkerEvent(session, m.eventId());
            }
            session.nextMarkerIndex++;
        }

        if (session.ticksElapsed >= total) {
            toFinish.add(session);
        }
    }

    //字幕文本特殊值 "clear" 表示清空当前字幕（与空串等价，但语义更明确）
    private static void dispatchSubtitle(BossShowSession session, String text) {
        if ("clear".equals(text)) text = "";
        NetworkHandler.sendToPlayer(new BossShowSubtitlePacket(text), session.viewer);
    }

    private static void dispatchMarkerEvent(BossShowSession session, String eventId) {
        BossShow hook = BossShowManager.getCodeHook(session.definition.id());
        if (hook == null) return;
        try {
            hook.onMarkerEvent(eventId, session);
        } catch (Throwable t) {
            EcaLogger.error("BossShow {} onMarkerEvent({}) threw: {}", session.definition.id(), eventId, t.getMessage());
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
        ACTIVE.clear();
    }
}
