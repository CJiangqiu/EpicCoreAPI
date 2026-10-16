package net.eca.util.raid;

/** A transient glow source owned by the entity's raid, never persisted as a vanilla tag. */
public interface RaidGlowState {
    void eca$setRaidGlow(boolean glowing);
}
