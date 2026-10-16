package net.eca.coremod;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.health.health_lock.HealthLockManager;
import net.minecraft.world.entity.LivingEntity;

/**
 * Unified hook handler for LivingEntity bytecode injection.
 * All methods are called via INVOKESTATIC from HEAD hook injection.
 * Convention:
 * - Float hooks return NaN for passthrough (let original method run)
 * - Boolean hooks return int: -1 = passthrough, 0 = false, 1 = true
 */
public final class LivingEntityHook {

    private LivingEntityHook() {
    }

    /* ECA 内部原始读：改血 verify 读 getHealth 时置位，令 processGetHealth 放行禁疗/血锁、暴露真实存储值。
       否则 verify 恒读到自家禁疗/血锁记录值，正确写入被误判失败并回滚。 */
    private static final ThreadLocal<HealthReadContext> HEALTH_READ_CONTEXT =
            ThreadLocal.withInitial(HealthReadContext::new);

    public static void beginRawHealthRead() { HEALTH_READ_CONTEXT.get().raw = true; }

    public static void endRawHealthRead() { HEALTH_READ_CONTEXT.get().raw = false; }

    public static void beginProvisionalHealthWrite(LivingEntity entity, float health) {
        HEALTH_READ_CONTEXT.get().provisional = new ProvisionalHealth(entity, health);
    }

    public static void endProvisionalHealthWrite() {
        HEALTH_READ_CONTEXT.get().provisional = null;
    }

    private static final class HealthReadContext {
        private boolean raw;
        private ProvisionalHealth provisional;
    }

    // ==================== getHealth() hook ====================

    // 处理 getHealth() 入口：锁血直接裁决，禁疗留到真实返回值上限幅
    /**
     * Process getHealth() at method HEAD.
     * Returns a float value to short-circuit, or NaN to fall through to original method.
     * Priority: provisional write → health lock → passthrough
     * @param entity the living entity
     * @return provisional/locked health value, or NaN for passthrough
     */
    public static float processGetHealth(LivingEntity entity) {
        // 关联存储提交期间屏蔽可重入读取，避免完整性校验观察到只写了一半的状态。
        HealthReadContext context = HEALTH_READ_CONTEXT.get();
        ProvisionalHealth provisional = context.provisional;
        if (provisional != null && provisional.entity == entity) {
            return provisional.health;
        }
        // ECA 内部原始读：放行禁疗/血锁，回落真实 getHealth，供改血 verify 读到真实存储值
        if (context.raw) {
            return Float.NaN;
        }
        // 锁血：直接返回锁定值
        Float locked = HealthLockManager.getLock(entity);
        if (locked != null) {
            EntityUtil.repairBasicHealth(entity, locked);
            return locked;
        }

        // 放行：让原始方法体执行（常数覆盖已下沉至 CONSTANT 实体的 getHealth 方法体内）
        return Float.NaN;
    }

    // 禁疗是血量上限，真实掉血必须依然对外可见
    public static float processGetHealthResult(LivingEntity entity, float health) {
        HealthReadContext context = HEALTH_READ_CONTEXT.get();
        if (entity == null || context.raw) {
            return health;
        }
        ProvisionalHealth provisional = context.provisional;
        if (provisional != null && provisional.entity == entity) {
            return provisional.health;
        }
        Float locked = HealthLockManager.getLock(entity);
        if (locked != null) {
            EntityUtil.repairBasicHealth(entity, locked);
            return locked;
        }
        Float healBan = HealthLockManager.getHealBan(entity);
        return healBan == null ? health : Math.min(health, healBan);
    }

    private record ProvisionalHealth(LivingEntity entity, float health) {}

    // ==================== getMaxHealth() hook ====================

    /**
     * Process getMaxHealth() at method HEAD.
     * Returns locked max health or NaN for passthrough.
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
