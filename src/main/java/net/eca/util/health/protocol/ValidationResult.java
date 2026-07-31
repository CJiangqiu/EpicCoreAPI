package net.eca.util.health.protocol;

import java.util.List;
import java.util.Objects;

public record ValidationResult(Status status, List<Observation> observations, String explanation) {
    public ValidationResult {
        Objects.requireNonNull(status, "status");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (explanation == null) {
            explanation = "";
        }
    }

    public boolean passedRequiredChecks() {
        return status == Status.PASSED
                && observations.stream().filter(Observation::required)
                .allMatch(observation -> observation.outcome() == Outcome.MATCHED);
    }

    public record Observation(ValidationPlan.Stage stage, Outcome outcome, boolean required,
                              Object expected, Object observed, String detail) {
        public Observation {
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(outcome, "outcome");
            if (detail == null) {
                detail = "";
            }
        }
    }

    public enum Status {
        PASSED,
        FAILED,
        PENDING,
        ROLLED_BACK
    }

    public enum Outcome {
        MATCHED,
        MISMATCHED,
        PENDING,
        UNAVAILABLE
    }
}
