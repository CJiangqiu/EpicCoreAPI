package net.eca.blender.animation.controller;

/** Definitions may be shared; all mutable execution state belongs to the executor. */
public class DefaultBlenderLifecycleController implements BlenderLifecycleController {
    private final BlenderAnimationClip idle;
    private final BlenderAnimationClip moving;
    private final BlenderAnimationClip hurt;
    private final BlenderAnimationClip death;

    public DefaultBlenderLifecycleController(BlenderAnimationClip idle, BlenderAnimationClip moving,
                                             BlenderAnimationClip hurt, BlenderAnimationClip death) {
        if ((hurt != null && hurt.loop()) || (death != null && death.loop())) {
            throw new IllegalArgumentException("Hurt and death animations must not loop");
        }
        this.idle = idle;
        this.moving = moving;
        this.hurt = hurt;
        this.death = death;
    }

    public boolean isMoving(BlenderControllerContext context) {
        return context.entity().getDeltaMovement().horizontalDistanceSqr() > 0.0001;
    }

    @Override
    public BlenderAnimationClip selectBaseAnimation(BlenderControllerContext context) {
        return moving != null && isMoving(context) ? moving : idle;
    }

    @Override
    public BlenderAnimationClip hurtAnimation(BlenderControllerContext context) { return hurt; }

    @Override
    public BlenderAnimationClip deathAnimation(BlenderControllerContext context) { return death; }
}
