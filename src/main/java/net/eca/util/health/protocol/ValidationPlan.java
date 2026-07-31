package net.eca.util.health.protocol;

import java.util.List;
import java.util.Objects;

public record ValidationPlan(List<Check> checks) {
    public ValidationPlan {
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        if (checks.isEmpty() || checks.stream().noneMatch(check -> check.stage() == Stage.IMMEDIATE_READBACK)) {
            throw new IllegalArgumentException("validation plan requires immediate readback");
        }
    }

    public record Check(Stage stage, int delayTicks, boolean required) {
        public Check {
            Objects.requireNonNull(stage, "stage");
            if (delayTicks < 0) {
                throw new IllegalArgumentException("validation delay cannot be negative");
            }
            if (stage == Stage.IMMEDIATE_READBACK && delayTicks != 0) {
                throw new IllegalArgumentException("immediate readback cannot be delayed");
            }
        }
    }

    public enum Stage {
        IMMEDIATE_READBACK,
        AFTER_TICK,
        LIFECYCLE,
        CLIENT_SYNC,
        PERSISTENCE_RELOAD
    }
}
