package net.eca.util.raid;

import net.eca.util.entity_extension.CombatMusicExtension;

/**
 * Music settings for an ongoing raid. Only participating players receive the request.
 * Entity extension music takes precedence over raids regardless of raid priority.
 */
public interface RaidMusicExtension extends CombatMusicExtension {

    @Override
    default boolean enabled() {
        return true;
    }

    @Override
    default boolean strictMusicLock() {
        return true;
    }

    // 优先级仅在多个袭击之间比较，不改变实体全局效果的选择。
    /**
     * Higher values win between raids; equal priorities retain the current raid.
     * @return the raid music priority, defaulting to zero
     */
    default int priority() {
        return 0;
    }
}
