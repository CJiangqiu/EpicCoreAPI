package net.eca.util.health;

import net.eca.api.EcaAPI;
import net.minecraft.world.entity.LivingEntity;

/**
 * Unified hook handler for LivingEntity bytecode injection.
 * Handles getHealth(), isAlive(), and isDeadOrDying() hooks.
 * All methods are called via INVOKESTATIC from LivingEntityTransformer bytecode injection.
 */
public final class LivingEntityHook {

    private LivingEntityHook() {
    }

    // ==================== getHealth() hook ====================

    // 处理 getHealth() 返回值（分析 + 锁定）
    /**
     * Process getHealth() return value.
     * This hook performs two tasks in order:
     * 1. Health Analysis: Triggers bytecode analysis to cache health storage location
     * 2. Health Lock: Checks and applies health lock if set
     *
     * @param entity the living entity
     * @param originalHealth the original health value from getHealth() calculation
     * @return the final health value (locked value if set, otherwise original)
     */
    public static float processGetHealth(LivingEntity entity, float originalHealth) {
        // 第一步：始终触发分析（缓存机制会避免重复分析）
        // 即使实体被锁血，也要完成分析，这样解锁后才能正确修改血量
        try {
            String className = entity.getClass().getName().replace('.', '/');
            HealthAnalyzerManager.onGetHealthCalled(entity, className);
        } catch (Throwable t) {
            // 静默失败，不影响游戏运行
        }

        // 第二步：检查是否有锁定值（优先级高于原始值）
        Float locked = HealthLockManager.getLock(entity);
        if (locked != null) {
            return locked;
        }

        // 第三步：返回原始值
        return originalHealth;
    }

    // ==================== isDeadOrDying() hook ====================

    /**
     * Process isDeadOrDying() return value.
     * Returns false when entity is invulnerable or has positive health lock.
     *
     * @param entity the living entity
     * @param original the original isDeadOrDying() result
     * @return false if entity should be kept alive, otherwise original
     */
    public static boolean processIsDeadOrDying(LivingEntity entity, boolean original) {
        if (entity == null) {
            return original;
        }
        if (EcaAPI.isInvulnerable(entity)) {
            return false;
        }
        Float locked = HealthLockManager.getLock(entity);
        if (locked != null && locked > 0.0f) {
            return false;
        }
        return original;
    }

    // ==================== isAlive() hook ====================

    /**
     * Process isAlive() return value.
     * Returns true when entity is invulnerable or has positive health lock.
     *
     * @param entity the living entity
     * @param original the original isAlive() result
     * @return true if entity should be kept alive, otherwise original
     */
    public static boolean processIsAlive(LivingEntity entity, boolean original) {
        if (entity == null) {
            return original;
        }
        if (EcaAPI.isInvulnerable(entity)) {
            return true;
        }
        Float locked = HealthLockManager.getLock(entity);
        if (locked != null && locked > 0.0f) {
            return true;
        }
        return original;
    }
}
