package net.eca.util.entity_extension;

import net.eca.blender.animation.BlenderAnimations;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/** Compatibility facade; playback state is owned by the Blender core. */
public final class BlenderAnimationManager {
    private BlenderAnimationManager() { }
    public static boolean play(LivingEntity entity, String animation, float speed, boolean loop) { return BlenderAnimations.play(entity, animation, speed, loop); }
    public static boolean stop(LivingEntity entity) { return BlenderAnimations.stop(entity); }
    public static boolean pause(LivingEntity entity) { return BlenderAnimations.pause(entity); }
    public static boolean resume(LivingEntity entity) { return BlenderAnimations.resume(entity); }
    public static boolean isPlaying(LivingEntity entity, String animation) { return BlenderAnimations.isPlaying(entity, animation); }
    public static void syncToPlayer(ServerPlayer player, LivingEntity entity) { BlenderAnimations.syncToPlayer(player, entity); }
    public static void onEntityLeave(LivingEntity entity) { BlenderAnimations.onEntityLeave(entity); }
    public static void clear() { BlenderAnimations.clear(); }
}
