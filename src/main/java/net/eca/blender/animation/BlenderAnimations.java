package net.eca.blender.animation;

import net.eca.network.BlenderAnimationSyncPacket;
import net.eca.network.NetworkHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class BlenderAnimations {
    private static final Map<UUID, BlenderPlaybackState> STATES = new ConcurrentHashMap<>();
    private static final AtomicLong REVISION = new AtomicLong();

    private BlenderAnimations() {
    }

    public static boolean play(LivingEntity entity, String animation, float speed, boolean loop) {
        if (!isServerEntity(entity) || animation == null || animation.isBlank()
            || animation.length() > 256 || !Float.isFinite(speed) || speed <= 0.0f) {
            return false;
        }
        if (!BlenderControllers.beforeManualPlay(entity)) return false;
        return playControlled(entity, animation, speed, loop);
    }

    static BlenderPlaybackState state(LivingEntity entity) { return STATES.get(entity.getUUID()); }

    static boolean playControlled(LivingEntity entity, String animation, float speed, boolean loop) {
        if (!isServerEntity(entity)) return false;
        long revision = REVISION.incrementAndGet();
        BlenderPlaybackState state = new BlenderPlaybackState(animation, revision,
            entity.level().getGameTime(), 0.0f, speed, loop, false);
        STATES.put(entity.getUUID(), state);
        sync(entity, state, revision);
        return true;
    }

    public static boolean stop(LivingEntity entity) {
        if (!isServerEntity(entity) || !STATES.containsKey(entity.getUUID()) || !BlenderControllers.beforeManualStop(entity)) {
            return false;
        }
        return stopControlled(entity);
    }

    static boolean stopControlled(LivingEntity entity) {
        if (STATES.remove(entity.getUUID()) == null) return false;
        long revision = REVISION.incrementAndGet();
        sync(entity, null, revision);
        return true;
    }

    public static boolean pause(LivingEntity entity) {
        if (!isServerEntity(entity) || !BlenderControllers.canChangePlayback(entity)) {
            return false;
        }
        BlenderPlaybackState current = STATES.get(entity.getUUID());
        if (current == null || current.paused()) {
            return false;
        }
        long now = entity.level().getGameTime();
        long revision = REVISION.incrementAndGet();
        BlenderPlaybackState paused = new BlenderPlaybackState(current.animation(), revision, now,
            current.playbackTime(now, 0.0f), current.speed(), current.loop(), true);
        STATES.put(entity.getUUID(), paused);
        sync(entity, paused, revision);
        return true;
    }

    public static boolean resume(LivingEntity entity) {
        if (!isServerEntity(entity) || !BlenderControllers.canChangePlayback(entity)) {
            return false;
        }
        BlenderPlaybackState current = STATES.get(entity.getUUID());
        if (current == null || !current.paused()) {
            return false;
        }
        long revision = REVISION.incrementAndGet();
        BlenderPlaybackState resumed = new BlenderPlaybackState(current.animation(), revision,
            entity.level().getGameTime(), current.elapsedSeconds(), current.speed(), current.loop(), false);
        STATES.put(entity.getUUID(), resumed);
        sync(entity, resumed, revision);
        return true;
    }

    public static boolean isPlaying(LivingEntity entity, String animation) {
        if (!isServerEntity(entity)) {
            return false;
        }
        BlenderPlaybackState state = STATES.get(entity.getUUID());
        return state != null && (animation == null || animation.equals(state.animation()));
    }

    public static void syncToPlayer(ServerPlayer player, LivingEntity entity) {
        if (player == null || !isServerEntity(entity)) {
            return;
        }
        BlenderPlaybackState state = STATES.get(entity.getUUID());
        long revision = state == null ? REVISION.get() : state.revision();
        NetworkHandler.sendToPlayer(new BlenderAnimationSyncPacket(entity.getUUID(), revision, state), player);
    }

    public static void onEntityLeave(LivingEntity entity) {
        if (entity != null) {
            BlenderControllers.onEntityLeave(entity);
            STATES.remove(entity.getUUID());
        }
    }

    public static void clear() {
        BlenderControllers.clear();
        STATES.clear();
        REVISION.set(0L);
    }

    private static boolean isServerEntity(LivingEntity entity) {
        return entity != null && entity.level() instanceof ServerLevel level
            && level.getServer().isSameThread() && !entity.isRemoved();
    }

    private static void sync(LivingEntity entity, BlenderPlaybackState state, long revision) {
        NetworkHandler.sendToTrackingClientsAndSelf(
            new BlenderAnimationSyncPacket(entity.getUUID(), revision, state), entity);
    }
}
