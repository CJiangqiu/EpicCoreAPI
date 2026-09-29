package net.eca.util.health;

/*
 * 血量数值校验的公共规则，避免即时写入和延迟复查各自使用不一致的误差标准。
 * 普通比较使用绝对与相对容差，死亡目标可单独按非正值判断，并统一拒绝非有限数值。
 * 延迟保持检查允许血量继续下降，防止后续伤害被误判为写入回滚。
 */
public final class HealthValueSemantics {

    private static final float MIN_TOLERANCE = 0.5f;
    private static final float RELATIVE_TOLERANCE = 0.02f;

    private HealthValueSemantics() {}

    public static float tolerance(float target) {
        return Math.max(MIN_TOLERANCE, Math.abs(target) * RELATIVE_TOLERANCE);
    }

    public static boolean matches(float actual, float target) {
        return Float.isFinite(actual)
                && Float.isFinite(target)
                && Math.abs(actual - target) <= tolerance(target);
    }

    public static boolean matchesWithDeathSemantics(float actual, float target) {
        if (!Float.isFinite(actual) || !Float.isFinite(target)) return false;
        return target <= 0.0f ? actual <= 0.0f : matches(actual, target);
    }

    public static boolean retainedAfterDelay(float actual, float target) {
        return Float.isFinite(actual)
                && Float.isFinite(target)
                && actual <= target + tolerance(target);
    }
}
