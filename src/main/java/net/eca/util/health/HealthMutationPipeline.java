package net.eca.util.health;

import static net.eca.util.health.HealthReportText.tr;

import net.eca.config.EcaConfiguration;
import net.eca.util.EntityUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** Runs all immediate mutation channels with one operation-local evidence context. */
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
