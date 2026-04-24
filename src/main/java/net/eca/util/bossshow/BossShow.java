package net.eca.util.bossshow;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

/**
 * Base class for Java-side BossShow definitions.
 *
 * Subclasses should be annotated with {@link net.eca.api.RegisterBossShow} and register
 * themselves in a static initializer via {@link BossShowManager#register(BossShow)}.
 *
 * Each BossShow must match a JSON file at {@code data/<namespace>/bossshow/<path>.json}
 * (or an override at {@code config/eca/bossshow/<namespace>/<path>.json}). The Java class
 * exists so that cutscenes can react to marker events server-side.
 */
public abstract class BossShow {

    private final ResourceLocation id;
    private final EntityType<?> targetType;

    protected BossShow(ResourceLocation id, EntityType<?> targetType) {
        if (id == null) throw new IllegalArgumentException("BossShow id must not be null");
        if (targetType == null) throw new IllegalArgumentException("BossShow targetType must not be null");
        this.id = id;
        this.targetType = targetType;
    }

    public final ResourceLocation id() { return id; }

    public final EntityType<?> targetType() { return targetType; }

    /**
     * Called on the server when a marker with a non-empty eventId is reached.
     * Default implementation does nothing. Override to react to marker events.
     */
    public void onMarkerEvent(String eventId, BossShowContext ctx) {
    }

    /**
     * Called on the server when playback begins for a viewer.
     */
    public void onStart(BossShowContext ctx) {
    }

    /**
     * Called on the server when playback ends (naturally or via skip).
     *
     * @param skipped true if the viewer pressed ESC to skip
     */
    public void onEnd(BossShowContext ctx, boolean skipped) {
    }
}
