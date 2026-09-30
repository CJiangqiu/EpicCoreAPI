package net.eca.util.entity_extension;

import net.eca.blender.animation.BlenderPlaybackState;
public record BlenderAnimationState(
    String animation,
    long revision,
    long referenceGameTime,
    float elapsedSeconds,
    float speed,
    boolean loop,
    boolean paused
) {
    public float playbackTime(long gameTime, float partialTick) {
        return new BlenderPlaybackState(animation, revision, referenceGameTime, elapsedSeconds, speed, loop, paused)
            .playbackTime(gameTime, partialTick);
    }
}
