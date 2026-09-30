package net.eca.blender.animation.controller;

/** Stateless decisions; return null to leave a lifecycle animation unconfigured. */
public interface BlenderLifecycleController {
    BlenderAnimationClip selectBaseAnimation(BlenderControllerContext context);
    default BlenderAnimationClip hurtAnimation(BlenderControllerContext context) { return null; }
    default BlenderAnimationClip deathAnimation(BlenderControllerContext context) { return null; }
    default boolean isDead(BlenderControllerContext context) { return context.entity().isDeadOrDying(); }
}
