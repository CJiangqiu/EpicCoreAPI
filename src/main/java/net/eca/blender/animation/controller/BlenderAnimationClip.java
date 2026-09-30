package net.eca.blender.animation.controller;

/** Duration and markers use animation ticks at speed one, independently of resource FPS. */
public record BlenderAnimationClip(String animation, float speed, boolean loop, int durationTicks) {
    public BlenderAnimationClip {
        if (animation == null || animation.isBlank() || animation.length() > 256
            || !Float.isFinite(speed) || speed <= 0 || durationTicks < 0 || (!loop && durationTicks == 0)) {
            throw new IllegalArgumentException("Invalid controller animation clip");
        }
    }

    public static BlenderAnimationClip looping(String animation) {
        return new BlenderAnimationClip(animation, 1, true, 0);
    }

    public static BlenderAnimationClip once(String animation, int durationTicks) {
        return new BlenderAnimationClip(animation, 1, false, durationTicks);
    }
}
