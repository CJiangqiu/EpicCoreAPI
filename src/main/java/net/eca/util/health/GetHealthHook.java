package net.eca.util.health;

import net.eca.util.EcaLogger;
import net.minecraft.world.entity.LivingEntity;

//getHealth() 完整钩子处理器（分析 + 锁定）
public class GetHealthHook {

    private static boolean debugLogged = false;

    // 处理 getHealth() 返回值（供 Transformer 调用）
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
        // 调试日志（只打印一次避免刷屏）
        if (!debugLogged) {
            EcaLogger.info("[GetHealthHook] processGetHealth CALLED! Entity: {}, Original: {}",
                          entity.getClass().getSimpleName(), originalHealth);
            debugLogged = true;
        }

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
            EcaLogger.info("[GetHealthHook] LOCK ACTIVE! Entity: {}, Locked: {}, Original: {}",
                          entity.getName().getString(), locked, originalHealth);
            return locked;
        }

        // 第三步：返回原始值
        return originalHealth;
    }
}
