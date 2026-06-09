package net.eca.util.health;

public record HealthSolveResult(Object value, HealthSolveFailure failure, String detail) {

    public static HealthSolveResult success(Object value) {
        return new HealthSolveResult(value, HealthSolveFailure.NONE, "");
    }

    public static HealthSolveResult failure(HealthSolveFailure failure, String detail) {
        return new HealthSolveResult(null, failure, detail == null ? "" : detail);
    }

    public boolean solved() {
        return failure == HealthSolveFailure.NONE && value != null;
    }
}
