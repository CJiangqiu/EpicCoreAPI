package net.eca.util.health;

import net.minecraft.world.entity.LivingEntity;

// 生命值分析Hook入口
/**
 * Hook handler for health analysis.
 * This class serves as the bridge between LivingEntityTransformer and HealthGetterHook.
 * It will be called every time a LivingEntity's getHealth() method is invoked.
 */
public class HealthAnalysisHook {

    // Hook入口：在getHealth()方法返回前调用
    /**
     * Process the getHealth() return value and trigger health analysis if needed.
     * @param originalHealth the original health value from the method
     * @param entity the living entity instance
     * @param className the internal class name (dot-separated)
     * @return the processed health value (unchanged by default)
     */
    public static float processGetHealth(float originalHealth, LivingEntity entity, String className) {
        if (entity == null) {
            return originalHealth;
        }

        try {
            // 调用HealthGetterHook进行字节码分析
            HealthGetterHook.onGetHealthCalled(entity, className.replace('.', '/'));
        } catch (Throwable t) {
            // 静默失败，不影响游戏运行
        }

        // 返回原始血量（不修改）
        return originalHealth;
    }
}
