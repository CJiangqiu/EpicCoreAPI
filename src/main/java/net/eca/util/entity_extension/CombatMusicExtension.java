package net.eca.util.entity_extension;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

public interface CombatMusicExtension {

    default boolean enabled() {
        return false;
    }

    default ResourceLocation soundEventId() {
        return null;
    }

    default SoundSource soundSource() {
        return SoundSource.MUSIC;
    }

    default float volume() {
        return 1.0f;
    }

    default float pitch() {
        return 1.0f;
    }

    default boolean loop() {
        return true;
    }

    default boolean strictMusicLock() {
        return false;
    }
}
