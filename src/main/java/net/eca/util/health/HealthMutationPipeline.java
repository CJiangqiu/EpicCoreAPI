package net.eca.util.health;

import net.eca.util.health.report.HealthReportText;

import net.eca.util.health.report.HealthReportManager;

import static net.eca.util.health.report.HealthReportText.tr;

import net.eca.config.EcaConfiguration;
import net.eca.util.EntityUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/*
 * 即时改血的统一入口：在同一个 HealthMutationContext 中依次协调原版写入、数据流、外部扫描、
 * 方法探针和数值反演，使各通道共享证据，并受配置、存储范围和剩余预算约束。
 * 探针发现新证据后只回访一次前置通道，避免失败路径循环互调；具体求解和写入仍交给对应模块。
 * 返回的是当场校验结果，跨 tick 是否保持由 DelayedHealthVerifier 另行复查。
 */
public final class HealthMutationPipeline {
    private HealthMutationPipeline() {}

    public static Result apply(LivingEntity entity, float target) {
        if (entity == null) return new Result(false, Float.NaN);
        try (HealthMutationContext context = HealthMutationContext.open(entity)) {
            EcaSetHealthManager.warmAnchorTrust(entity);
            float before = EcaSetHealthManager.safeGetHealth(entity);
            HealthReportManager.recordInitialValue(entity, before);
            if (EcaSetHealthManager.verify(entity, target)) {
                HealthReportManager.recordAlreadySatisfied(entity);
                return new Result(true, before, true);
            }
            EntityUtil.setBasicHealth(entity, target);
            boolean vanilla = EcaSetHealthManager.verify(entity, target);
            HealthReportManager.recordAttempt(entity, "channel.vanilla", vanilla,
                    vanilla ? tr("anchor.matches") : tr("anchor.mismatch"));
            if (vanilla || entity instanceof Player) return new Result(vanilla, before);
            boolean radical = EcaConfiguration.getAttackEnableRadicalLogicSafely();
            if (enabled(context, entity, "channel.dataflow", EcaConfiguration.getAttackSetHealthEnableDataflowSafely())) {
                boolean success = EcaSetHealthManager.applyDataflow(entity, target);
                HealthReportManager.recordAttempt(entity, "channel.dataflow", success,
                        success ? tr("write.verified") : tr("write.no_submission"));
                if (success && !HealthMutationContext.stopped()) return new Result(true, before);
            }
            if (enabled(context, entity, "channel.external", radical && EcaConfiguration.getAttackSetHealthEnableExternalScanSafely())) {
                if (EcaSetHealthManager.applyExternalScan(entity, target) && !HealthMutationContext.stopped())
                    return new Result(true, before);
            }
            int beforeProbe = context.revision();
            if (enabled(context, entity, "channel.probe", radical && EcaConfiguration.getAttackSetHealthEnableMethodProbeSafely())) {
                boolean success = EcaSetHealthManager.applyMethodProbe(entity, target);
                HealthReportManager.recordAttempt(entity, "channel.probe", success,
                        success ? tr("write.verified") : tr("probe.unverified"));
                if (success && !HealthMutationContext.stopped()) return new Result(true, before);
            }
            // 新运行期对象可能使此前无法解析的调用可求值；只回访一次，避免失败通道循环互调。
            if (context.revision() > beforeProbe && !HealthMutationContext.stopped()) {
                if (enabled(context, entity, "channel.dataflow", EcaConfiguration.getAttackSetHealthEnableDataflowSafely())) {
                    boolean success = EcaSetHealthManager.applyDataflow(entity, target);
                    HealthReportManager.recordAttempt(entity, "channel.dataflow", success,
                            success ? tr("write.new_evidence_success") : tr("write.new_evidence_failed"));
                    if (success && !HealthMutationContext.stopped()) return new Result(true, before);
                }
                if (enabled(context, entity, "channel.external", radical && EcaConfiguration.getAttackSetHealthEnableExternalScanSafely())
                        && EcaSetHealthManager.applyExternalScan(entity, target) && !HealthMutationContext.stopped())
                    return new Result(true, before);
            }
            if (enabled(context, entity, "channel.numeric", radical && EcaConfiguration.getAttackSetHealthEnableNumericInversionSafely())) {
                boolean success = EcaSetHealthManager.applyNumericInversion(entity, target);
                HealthReportManager.recordAttempt(entity, "channel.numeric", success,
                        success ? tr("numeric.solved") : tr("numeric.failed"));
                if (success && !HealthMutationContext.stopped()) return new Result(true, before);
            }
            if (!HealthMutationContext.stopped() && !context.storageSearchPending()) EcaSetHealthManager.scheduleEffectiveModelAnalysis(entity);
            return new Result(false, before);
        }
    }

    private static boolean enabled(HealthMutationContext context, LivingEntity entity, String channel, boolean enabled) {
        if (context.storageSearchPending()) {
            HealthReportManager.recordSkipped(entity, channel, tr("slice.skip_unrelated"));
            return false;
        }
        if (context.hasBoundedNumericRead() && channel.equals("channel.probe")
                && EcaConfiguration.getAttackSetHealthEnableNumericInversionSafely()) {
            HealthReportManager.recordSkipped(entity, channel, tr("analysis.bounded_read"));
            return false;
        }
        if (context.hasReadSlice() && channel.equals("channel.probe")) {
            HealthReportManager.recordSkipped(entity, channel, tr("slice.skip_probe"));
            return false;
        }
        if (!enabled) {
            HealthReportManager.recordSkipped(entity, channel, tr("config.disabled"));
            return false;
        }
        return context.enter(channel);
    }

    public record Result(boolean success, float before, boolean alreadySatisfied) {
        public Result(boolean success, float before) { this(success, before, false); }
    }
}
