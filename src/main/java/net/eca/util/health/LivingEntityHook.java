package net.eca.util.health;

import net.eca.api.EcaAPI;
import net.minecraft.world.entity.LivingEntity;

/**
 * Unified hook handler for LivingEntity bytecode injection.
 * All methods are called via INVOKESTATIC from HEAD hook injection.
 *
 * Convention:
 * - Float hooks return NaN for passthrough (let original method run)
 * - Boolean hooks return int: -1 = passthrough, 0 = false, 1 = true
 */
public final class LivingEntityHook {

    private LivingEntityHook() {
    }

    // ==================== getHealth() hook ====================

    // 处理 getHealth()：分析 + 锁血 + 禁疗，NaN 表示放行
    /**
     * Process getHealth() at method HEAD.
     * Returns a float value to short-circuit, or NaN to fall through to original method.
     *
     * Priority: analysis → health lock → heal ban → passthrough
     *
     * @param entity the living entity
     * @return locked/banned health value, or NaN for passthrough
     */
    public static float processGetHealth(LivingEntity entity) {
        // 始终触发分析（缓存机制会避免重复分析）
        try {
            String className = entity.getClass().getName().replace('.', '/');
            HealthAnalyzerManager.onGetHealthCalled(entity, className);
        } catch (Throwable t) {
        }

        // 锁血：直接返回锁定值
        Float locked = HealthLockManager.getLock(entity);
        if (locked != null) {
            return locked;
        }

        // 禁疗：直接返回禁疗时记录的血量值
        Float healBan = HealthLockManager.getHealBan(entity);
        if (healBan != null) {
            return healBan;
        }

        // 放行：让原始方法体执行
        return Float.NaN;
    }

    // ==================== getMaxHealth() hook ====================

    /**
     * Process getMaxHealth() at method HEAD.
     * Returns locked max health or NaN for passthrough.
     *
     * @param entity the living entity
     * @return locked max health, or NaN for passthrough
     */
    public static float processGetMaxHealth(LivingEntity entity) {
        if (entity == null) {
            return Float.NaN;
        }
        Float locked = HealthLockManager.getMaxHealthLock(entity);
        if (locked != null) {
            return locked;
        }
        return Float.NaN;
    }

    // ==================== isDeadOrDying() hook ====================

    /**
     * Process isDeadOrDying() at method HEAD.
     * Returns 0 (false, not dead) when entity is invulnerable or has positive health lock.
     * Returns -1 for passthrough.
     *
     * @param entity the living entity
     * @return 0 for "not dead", -1 for passthrough
     */
    public static int processIsDeadOrDying(LivingEntity entity) {
        if (entity == null) {
            return -1;
        }
        if (EcaAPI.isInvulnerable(entity)) {
            return 0;
        }
        Float locked = HealthLockManager.getLock(entity);
        if (locked != null && locked > 0.0f) {
            return 0;
        }
        return -1;
    }

    // ==================== isAlive() hook ====================

    /**
     * Process isAlive() at method HEAD.
     * Returns 1 (true, alive) when entity is invulnerable or has positive health lock.
     * Returns -1 for passthrough.
     *
     * @param entity the living entity
     * @return 1 for "alive", -1 for passthrough
     */
    public static int processIsAlive(LivingEntity entity) {
        if (entity == null) {
            return -1;
        }
        if (EcaAPI.isInvulnerable(entity)) {
            return 1;
        }
        Float locked = HealthLockManager.getLock(entity);
        if (locked != null && locked > 0.0f) {
            return 1;
        }
        return -1;
    }
}
