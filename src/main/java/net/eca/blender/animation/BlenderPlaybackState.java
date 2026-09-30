package net.eca.blender.animation;

public record BlenderPlaybackState(
    String animation,
    long revision,
    long referenceGameTime,
    float elapsedSeconds,
    float speed,
    boolean loop,
    boolean paused
) {
    public float playbackTime(long gameTime, float partialTick) {
        if (paused) {
            return elapsedSeconds;
        }
        float elapsedTicks = (float) Math.max(0.0, (double) (gameTime - referenceGameTime) + partialTick);
        return elapsedSeconds + elapsedTicks / 20.0f * speed;
    }
}
