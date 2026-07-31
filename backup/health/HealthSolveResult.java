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

//符号反演的失败归类，供分流与诊断使用
enum HealthSolveFailure {
    NONE,
    LOCATION_NOT_FOUND,
    CALL_NOT_RESOLVED,
    INVERTER_MISSING,
    MULTI_LOCATION_UNSUPPORTED,
    VALUE_NOT_REPRESENTABLE,
    WRITE_FAILED,
    VERIFY_FAILED,
    ROLLBACK_FAILED
}
