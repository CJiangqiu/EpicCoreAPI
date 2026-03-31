package net.eca.coremod;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.minecraft.world.entity.Entity;

/**
 * Hook handler for Entity bytecode injection.
 * Called via INVOKESTATIC from EntityTransformer HEAD hook injection.
 *
 * Returns int: -1 = passthrough, 0 = false (not removed), 1 = true (removed)
 */
public final class EntityHook {

    private EntityHook() {
    }

    // 处理 isRemoved()：无敌实体强制清除 removalReason 并返回 false
    /**
     * Process isRemoved() at method HEAD.
     * For invulnerable entities (not changing dimension), clears stale removalReason
     * and returns 0 (false) to keep the entity active.
     * Returns -1 for passthrough.
     *
     * @param entity the entity
     * @return 0 for "not removed", -1 for passthrough
     */
    public static int processIsRemoved(Entity entity) {
        if (entity == null) {
            return -1;
        }
        if (!EcaAPI.isInvulnerable(entity) || EntityUtil.isChangingDimension(entity)) {
            return -1;
        }
        if (entity.getRemovalReason() != null) {
            entity.removalReason = null;
        }
        return 0;
    }
}
