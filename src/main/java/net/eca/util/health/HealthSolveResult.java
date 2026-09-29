package net.eca.util.health;

import net.eca.util.health.HealthDataflowAnalyzer.Expr;
import net.eca.util.health.HealthDataflowAnalyzer.Source;

/*
 * 符号求解的结构化结果：成功时携带待写值，失败时保留原因及表达式、存储来源和中间目标。
 * 将失败位置与诊断文字分开，便于共享上下文向后续通道传递可继续分析的线索，
 * 而不必从日志文本反推求解过程；求解成功本身不代表写入与校验已经完成。
 */
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

/*
 * 求解与写入链路的失败原因，区分来源缺失、反演能力不足、预算耗尽及写入校验失败，
 * 供通道分流和报告诊断共用，避免把所有失败都归为无法求逆。
 */
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
