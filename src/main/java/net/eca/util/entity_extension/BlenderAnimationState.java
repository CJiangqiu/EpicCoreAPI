package net.eca.util.entity_extension;

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
        if (paused) {
            return elapsedSeconds;
        }
        float elapsedTicks = Math.max(0.0f, gameTime + partialTick - referenceGameTime);
        return elapsedSeconds + elapsedTicks / 20.0f * speed;
    }
}
