package net.eca.util.health.internal;

/**
 * Centralizes scalar health comparison rules shared by immediate and delayed verification.
 */
public final class ProtocolValueSemantics {

    private static final float MIN_TOLERANCE = 0.5f;
    private static final float RELATIVE_TOLERANCE = 0.02f;

    private ProtocolValueSemantics() {}

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


