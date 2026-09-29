package net.eca.util.health;

import net.eca.util.health.HealthDataflowAnalyzer.Expr;
import net.eca.util.health.HealthDataflowAnalyzer.Source;

public record HealthSolveResult(Object value, HealthSolveFailure failure, String detail, FailureSite site) {

    public HealthSolveResult(Object value, HealthSolveFailure failure, String detail) {
        this(value, failure, detail, null);
    }

    HealthSolveResult at(Expr expression, Source source, Object target) {
        if (site != null || expression == null || solved()) return this;
        return new HealthSolveResult(value, failure, detail, new FailureSite(expression, source, target));
    }

    public record FailureSite(Expr expression, Source source, Object intermediateTarget) {}

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
    BUDGET_EXHAUSTED,
    VALUE_NOT_REPRESENTABLE,
    WRITE_FAILED,
    VERIFY_FAILED,
    ROLLBACK_FAILED
}
