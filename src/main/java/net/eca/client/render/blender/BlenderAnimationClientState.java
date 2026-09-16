package net.eca.client.render.blender;

import net.eca.util.entity_extension.BlenderAnimationState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public final class BlenderAnimationClientState {
    private static final Map<UUID, BlenderAnimationState> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> REVISIONS = new ConcurrentHashMap<>();

    private BlenderAnimationClientState() {
    }

    public static void apply(UUID entityId, long revision, BlenderAnimationState state) {
        if (entityId == null || revision < REVISIONS.getOrDefault(entityId, Long.MIN_VALUE)) {
            return;
        }
        REVISIONS.put(entityId, revision);
        if (state == null) {
            STATES.remove(entityId);
        } else {
            STATES.put(entityId, state);
        }
    }

    public static BlenderAnimationState get(LivingEntity entity) {
        return entity == null ? null : STATES.get(entity.getUUID());
    }

    public static void clear() {
        STATES.clear();
        REVISIONS.clear();
    }
}
