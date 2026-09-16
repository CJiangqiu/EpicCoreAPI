package net.eca.util.entity_extension;

import net.eca.network.BlenderAnimationSyncPacket;
import net.eca.network.NetworkHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class BlenderAnimationManager {
    private static final Map<UUID, BlenderAnimationState> STATES = new ConcurrentHashMap<>();
    private static final AtomicLong REVISION = new AtomicLong();

    private BlenderAnimationManager() {
    }

    public static boolean play(LivingEntity entity, String animation, float speed, boolean loop) {
        if (!isServerEntity(entity) || animation == null || animation.isBlank()
            || !Float.isFinite(speed) || speed <= 0.0f) {
            return false;
        }
        long revision = REVISION.incrementAndGet();
        BlenderAnimationState state = new BlenderAnimationState(animation, revision,
            entity.level().getGameTime(), 0.0f, speed, loop, false);
        STATES.put(entity.getUUID(), state);
        sync(entity, state, revision);
        return true;
    }

    public static boolean stop(LivingEntity entity) {
        if (!isServerEntity(entity) || STATES.remove(entity.getUUID()) == null) {
            return false;
        }
        long revision = REVISION.incrementAndGet();
        sync(entity, null, revision);
        return true;
    }

    public static boolean pause(LivingEntity entity) {
        if (!isServerEntity(entity)) {
            return false;
        }
        BlenderAnimationState current = STATES.get(entity.getUUID());
        if (current == null || current.paused()) {
            return false;
        }
        long now = entity.level().getGameTime();
        long revision = REVISION.incrementAndGet();
        BlenderAnimationState paused = new BlenderAnimationState(current.animation(), revision, now,
            current.playbackTime(now, 0.0f), current.speed(), current.loop(), true);
        STATES.put(entity.getUUID(), paused);
        sync(entity, paused, revision);
        return true;
    }

    public static boolean resume(LivingEntity entity) {
        if (!isServerEntity(entity)) {
            return false;
        }
        BlenderAnimationState current = STATES.get(entity.getUUID());
        if (current == null || !current.paused()) {
            return false;
        }
        long revision = REVISION.incrementAndGet();
        BlenderAnimationState resumed = new BlenderAnimationState(current.animation(), revision,
            entity.level().getGameTime(), current.elapsedSeconds(), current.speed(), current.loop(), false);
        STATES.put(entity.getUUID(), resumed);
        sync(entity, resumed, revision);
        return true;
    }

    public static boolean isPlaying(LivingEntity entity, String animation) {
        if (!isServerEntity(entity)) {
            return false;
        }
        BlenderAnimationState state = STATES.get(entity.getUUID());
        return state != null && (animation == null || animation.equals(state.animation()));
    }

    public static void syncToPlayer(ServerPlayer player, LivingEntity entity) {
        if (player == null || !isServerEntity(entity)) {
            return;
        }
        BlenderAnimationState state = STATES.get(entity.getUUID());
        long revision = state == null ? REVISION.get() : state.revision();
        NetworkHandler.sendToPlayer(new BlenderAnimationSyncPacket(entity.getUUID(), revision, state), player);
    }

    public static void onEntityLeave(LivingEntity entity) {
        if (entity != null) {
            STATES.remove(entity.getUUID());
        }
    }

    public static void clear() {
        STATES.clear();
        REVISION.set(0L);
    }

    private static boolean isServerEntity(LivingEntity entity) {
        return entity != null && entity.level() instanceof ServerLevel;
    }

    private static void sync(LivingEntity entity, BlenderAnimationState state, long revision) {
        NetworkHandler.sendToTrackingClientsAndSelf(
            new BlenderAnimationSyncPacket(entity.getUUID(), revision, state), entity);
    }
}
