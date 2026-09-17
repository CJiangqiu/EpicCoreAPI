package net.eca.util.health;

import net.eca.util.EcaLogger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/*
 * 改血延迟复查：写入当场校验只能证明"这一刻写进去了"。挂在实体 tick 上的防护会在下一 tick 把值改回去，
 * 于是通道报成功、玩家看到血量原样弹回——日志里这类假成功与真成功完全无法区分。
 * 校验通过后在此登记一笔，等实体至少经过一次 tick 再复读锚点，据此把假成功揭出来，
 * 并解除"该类已验证可写"的闭锁，使后续改血重新收集证据、允许换通道。
 */
public final class DelayedHealthVerifier {

    private DelayedHealthVerifier() {}

    /* 防护逻辑挂在实体 tick 上，一次 tick 足够让回滚发生，不必等更久。 */
    private static final int VERIFY_DELAY_TICKS = 1;

    /* 待复查上限。逐 tick 改血的调用方按实体去重后只占一条，正常规模远达不到此数。 */
    private static final int MAX_PENDING = 1024;
    private static final int MAX_CONVERGENCE_TICKS = 600;
    private static final int MAX_CONVERGENCE_FAILURES = 3;

    private record Pending(WeakReference<LivingEntity> entity, Class<?> entityClass, float target, int dueTick,
                           Ticket ticket) {}

    private record DeathConvergence(WeakReference<LivingEntity> entity, UUID entityUuid,
                                    HealthDataflowAnalyzer.EffectiveHealthModel model,
                                    int nextTick, int expiresTick, int failures) {}

    public record Ticket(int entityId, UUID entityUuid, long revision) {}

    /* 按实体 id 索引：同一实体在一个 tick 内被反复改血时，只有最后一次的目标值值得复查，
       put 覆盖即可完成去重，同时使上限检查不必遍历链表。
       id 取自 Entity.ENTITY_COUNTER，单次服务器运行内跨维度唯一；重启后会重排，故须在停服时清空。 */
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<Integer, DeathConvergence> DEATH_CONVERGENCE = new ConcurrentHashMap<>();
    private static final Set<String> ROLLBACK_DUMPED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean SATURATION_DUMPED = new AtomicBoolean();
    private static final AtomicLong NEXT_REVISION = new AtomicLong();

    /* 登记一次成功写入，待实体 tick 过后复查。返回是否登记成功——第三阶段的外部联写
       须由本复查裁定提交或撤销，登记不上就不该动世界数据，否则那批快照无人销账。 */
    public static Ticket schedule(LivingEntity entity, float target) {
        if (entity == null || entity instanceof Player) return null;
        if (!Float.isFinite(target)) return null;
        if (entity.level() == null || entity.level().isClientSide) return null;
        MinecraftServer server = entity.level().getServer();
        if (server == null) return null;

        int id = entity.getId();
        // 已在表中的实体只是覆盖，不增长，因此仅新实体受上限约束
        if (PENDING.size() >= MAX_PENDING && !PENDING.containsKey(id)) {
            if (SATURATION_DUMPED.compareAndSet(false, true)) {
                EcaLogger.info("[DelayedVerify] pending table saturated at {} entries, further entities skipped this tick",
                        MAX_PENDING);
            }
            return null;
        }
        Ticket ticket = new Ticket(
                id, entity.getUUID(), NEXT_REVISION.incrementAndGet());
        Pending next = new Pending(new WeakReference<>(entity), entity.getClass(), target,
                server.getTickCount() + VERIFY_DELAY_TICKS, ticket);
        Pending previous = PENDING.put(id, next);
        if (previous != null) {
            if (previous.ticket().entityUuid().equals(ticket.entityUuid())) {
                ExternalMirrorWriter.supersede(previous.ticket(), ticket);
                HealthReportManager.completeDelayed(previous.ticket(), "后续改血取代本次延迟复查", Float.NaN);
            } else {
                ExternalMirrorWriter.revert(previous.ticket());
                HealthReportManager.completeDelayed(previous.ticket(), "实体身份变化，本次延迟复查已撤销", Float.NaN);
            }
        }
        return ticket;
    }

    /* 多阶段实体会在阶段转换中回复已归零的内部生命。零目标来自生命周期阈值时，
       在有限时间内持续收敛同一权威，让实体自行推进阶段与死亡许可。 */
    public static void scheduleDeathConvergence(LivingEntity entity,
                                                HealthDataflowAnalyzer.EffectiveHealthModel model) {
        if (entity == null || model == null || entity instanceof Player) return;
        if (entity.level() == null || entity.level().isClientSide) return;
        MinecraftServer server = entity.level().getServer();
        if (server == null) return;
        if (DEATH_CONVERGENCE.size() >= MAX_PENDING
                && !DEATH_CONVERGENCE.containsKey(entity.getId())) return;
        int now = server.getTickCount();
        DEATH_CONVERGENCE.put(entity.getId(), new DeathConvergence(new WeakReference<>(entity), entity.getUUID(),
                model, now + 1, now + MAX_CONVERGENCE_TICKS, 0));
    }

    /* 服务端 tick 末尾复查到期条目。此时本 tick 的实体 tick 已经跑完，
       登记时若已在实体 tick 之后，到期判定会顺延一轮，因此复查前必然至少经过一次实体 tick。 */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        int now = server.getTickCount();
        /* 先重新施加零目标，再做延迟复查。阶段转换同 tick 的合法回复不能先被误报为回滚。 */
        convergeDeaths(now);
        if (!PENDING.isEmpty()) {
            for (Iterator<Map.Entry<Integer, Pending>> iterator = PENDING.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<Integer, Pending> entry = iterator.next();
                if (now < entry.getValue().dueTick()) continue;
                iterator.remove();
                check(entry.getKey(), entry.getValue());
            }
        }
    }

    //停服时清空：实体 id 会在下次启动重排，残留条目会拿旧目标值去比对新实体
    public static void clear() {
        PENDING.clear();
        DEATH_CONVERGENCE.clear();
        ExternalMirrorWriter.clear();
        HealthReportManager.clear();
        SATURATION_DUMPED.set(false);
    }

    /* 复查一条到期记录，并据结论裁定第三阶段本次的外部联写：留住则提交，仍被回滚则撤销。
       无从判断时一律提交——记录必须销掉，否则快照会一直挂着。 */
    private static void check(int entityId, Pending pending) {
        LivingEntity entity = pending.entity().get();
        Ticket ticket = pending.ticket();
        // 已卸载或已移除的实体无从复查；目标为死亡时实体消失本身就是写入生效
        if (entity == null || entity.isRemoved()) {
            ExternalMirrorWriter.commit(ticket);
            HealthReportManager.completeDelayed(ticket, "实体已移除，按目标已生效处理", Float.NaN);
            return;
        }
        if (entity.getId() != entityId || !entity.getUUID().equals(ticket.entityUuid())) {
            ExternalMirrorWriter.revert(ticket);
            HealthReportManager.completeDelayed(ticket, "实体身份发生变化，无法复查", Float.NaN);
            return;
        }
        /* 锚点已被证明与真实存储解耦时，它读回什么都不构成"被改回去了"的证据。
           此处据它判失败会把诱饵型目标上的每次成功都揭成假成功，并误启外部镜像。 */
        if (EcaSetHealthManager.isAnchorUntrusted(entity)) {
            ExternalMirrorWriter.commit(ticket);
            HealthReportManager.completeDelayed(ticket, "观测出口不可信，无法据此否定写入", Float.NaN);
            return;
        }
        float actual = EcaSetHealthManager.readHealthAnchor(entity);
        if (!Float.isFinite(actual)) {
            ExternalMirrorWriter.commit(ticket);
            HealthReportManager.completeDelayed(ticket, "延迟观测值不可用，保留已提交写入", actual);
            return;
        }
        /* 只认向上偏离：血量自行回升是回滚与强制回血的特征。向下偏离可能只是这一 tick 内的
           正常受伤，据此判失败会把大量真成功误杀。 */
        if (HealthValueSemantics.retainedAfterDelay(actual, pending.target())) {
            EcaSetHealthManager.onDelayedRetained(pending.entityClass());
            ExternalMirrorWriter.commit(ticket);
            HealthReportManager.completeDelayed(ticket, "目标值在下一 tick 后保留", actual);
            return;
        }

        Class<?> cls = pending.entityClass();
        if (ROLLBACK_DUMPED.add(cls.getName())) {
            EcaLogger.info("[DelayedVerify] write rolled back entity={} target={} actual={} delay={}tick",
                    cls.getName(), pending.target(), actual, VERIFY_DELAY_TICKS);
        }
        ExternalMirrorWriter.revert(ticket);
        EcaSetHealthManager.onDelayedRollback(cls);
        HealthReportManager.completeDelayed(ticket, "目标值在下一 tick 被回滚", actual);
    }

    private static void convergeDeaths(int now) {
        if (DEATH_CONVERGENCE.isEmpty()) return;
        for (Iterator<Map.Entry<Integer, DeathConvergence>> iterator =
             DEATH_CONVERGENCE.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<Integer, DeathConvergence> entry = iterator.next();
            DeathConvergence convergence = entry.getValue();
            if (now < convergence.nextTick()) continue;
            LivingEntity entity = convergence.entity().get();
            if (entity == null || entity.isRemoved()
                    || entity.getId() != entry.getKey()
                    || !entity.getUUID().equals(convergence.entityUuid())
                    || !entity.isAlive() || entity.isDeadOrDying()) {
                iterator.remove();
                continue;
            }
            if (now >= convergence.expiresTick()) {
                iterator.remove();
                EcaLogger.info("[EffectiveHealth] death convergence expired entity={} storage={} ticks={}",
                        entity.getClass().getName(), convergence.model().storage().label,
                        MAX_CONVERGENCE_TICKS);
                continue;
            }

            boolean wrote = HealthDataFlow.writeEffective(convergence.model(), entity, 0.0f);
            int failures = wrote ? 0 : convergence.failures() + 1;
            if (failures >= MAX_CONVERGENCE_FAILURES) {
                iterator.remove();
                EcaLogger.info("[EffectiveHealth] death convergence stopped entity={} storage={} failures={}",
                        entity.getClass().getName(), convergence.model().storage().label, failures);
                continue;
            }
            DeathConvergence next = new DeathConvergence(convergence.entity(),
                    convergence.entityUuid(), convergence.model(), now + 1,
                    convergence.expiresTick(), failures);
            DEATH_CONVERGENCE.replace(entry.getKey(), convergence, next);
        }
    }
}
