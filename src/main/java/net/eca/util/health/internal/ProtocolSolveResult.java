package net.eca.util.health.internal;

public record ProtocolSolveResult(Object value, ProtocolSolveFailure failure, String detail) {

    public static ProtocolSolveResult success(Object value) {
        return new ProtocolSolveResult(value, ProtocolSolveFailure.NONE, "");
    }

    public static ProtocolSolveResult failure(ProtocolSolveFailure failure, String detail) {
        return new ProtocolSolveResult(null, failure, detail == null ? "" : detail);
    }

    public boolean solved() {
        return failure == ProtocolSolveFailure.NONE && value != null;
    }
}

//符号反演的失败归类，供分流与诊断使用
enum ProtocolSolveFailure {
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


