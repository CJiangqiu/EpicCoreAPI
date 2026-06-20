package net.eca.util.health;

import net.eca.util.health.HealthAnalyzer.Source;
import net.minecraft.world.entity.LivingEntity;

public final class SourceNumericSolver {

    private SourceNumericSolver() {}

    private static final int MAX_PASSES = 16;
    private static final int BACKTRACK_STEPS = 16;
    private static final float[] PROBE_STEPS = {1f, 16f, 256f, 4096f};

    public static boolean solve(LivingEntity entity, Source source, float target) {
        Object snapshot = source.read(entity);
        if (!(snapshot instanceof Number baseline)) return false;
        double current = baseline.doubleValue();
        boolean success = false;
        try {
            for (int pass = 0; pass < MAX_PASSES; pass++) {
                float health = HealthVerify.safeGetHealth(entity);
                if (!Float.isFinite(health)) break;
                if (HealthVerify.matches(entity, target)) {
                    success = true;
                    return true;
                }

                double slope = slope(entity, source, current, health);
                if (!Double.isFinite(slope) || slope == 0.0) break;
                double step = (target - health) / slope;
                double error = Math.abs(target - health);
                boolean improved = false;
                double fraction = 1.0;
                for (int i = 0; i < BACKTRACK_STEPS; i++) {
                    if (!source.write(entity, current + step * fraction)) break;
                    float next = HealthVerify.safeGetHealth(entity);
                    if (Float.isFinite(next) && Math.abs(target - next) < error) {
                        Object value = source.read(entity);
                        if (value instanceof Number number) current = number.doubleValue();
                        improved = true;
                        break;
                    }
                    fraction *= 0.5;
                }
                if (!improved) break;
            }
            success = HealthVerify.matches(entity, target);
            return success;
        } finally {
            if (!success) source.write(entity, snapshot);
        }
    }

    private static double slope(LivingEntity entity, Source source, double current, float health) {
        for (float step : PROBE_STEPS) {
            if (!source.write(entity, current + step)) continue;
            float changed = HealthVerify.safeGetHealth(entity);
            source.write(entity, current);
            if (Float.isFinite(changed) && Math.abs(changed - health) > 1.0e-3f) {
                return (changed - health) / step;
            }
        }
        return 0.0;
    }
}
