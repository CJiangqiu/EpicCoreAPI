package net.eca.util.health;

import net.minecraft.world.entity.LivingEntity;

/*
 * 统一血量校验与容差。各改血策略共用，避免各处 verify 标准漂移。
 * 校验一律用实体自身 getHealth()，绝不读 DATA_HEALTH_ID——后者只是原版存储，被重写血量的实体会绕过它。
 */
public final class HealthVerify {

    private HealthVerify() {}

    //相对容差：低血量不致因绝对阈值过宽而误判，高血量按 2% 放宽
    public static float tolerance(float target) {
        return Math.max(1.0f, Math.abs(target) * 0.02f);
    }

    //getHealth 落在目标容差内即命中
    public static boolean matches(LivingEntity entity, float target) {
        float h = safeGetHealth(entity);
        return Float.isFinite(h) && Math.abs(h - target) <= tolerance(target);
    }

    //安全读取 getHealth：重写血量的实体可能在读取链上抛异常，失败返回 NaN
    public static float safeGetHealth(LivingEntity entity) {
        try {
            return entity.getHealth();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return Float.NaN;
        }
    }
}
