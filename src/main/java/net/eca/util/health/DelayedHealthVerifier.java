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
import java.util.function.Consumer;

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

    private record Pending(WeakReference<LivingEntity> entity, Class<?> entityClass, float before, float target,
                            int dueTick, Ticket ticket, Consumer<Outcome> listener) {}

    public enum Outcome { PERSISTED, ROLLED_BACK, SUPERSEDED, INDETERMINATE }

    public record Ticket(int entityId, UUID entityUuid, long revision,
                         EcaSetHealthManager.ExternalCoWriteCoverage externalCoverage, int retryAttempt) {}

    /* 按实体 id 索引：同一实体在一个 tick 内被反复改血时，只有最后一次的目标值值得复查，
       put 覆盖即可完成去重，同时使上限检查不必遍历链表。
       id 取自 Entity.ENTITY_COUNTER，单次服务器运行内跨维度唯一；重启后会重排，故须在停服时清空。 */
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Set<String> ROLLBACK_DUMPED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean SATURATION_DUMPED = new AtomicBoolean();
    private static final AtomicLong NEXT_REVISION = new AtomicLong();

    /* 登记一次成功写入，待实体 tick 过后复查。返回是否登记成功——第三阶段的外部联写
       须由本复查裁定提交或撤销，登记不上就不该动世界数据，否则那批快照无人销账。 */
    public static Ticket schedule(LivingEntity entity, float before, float target,
                                  EcaSetHealthManager.ExternalCoWriteCoverage externalCoverage,
                                  int retryAttempt, Consumer<Outcome> listener) {
        if (entity == null || entity instanceof Player) return null;
        if (!Float.isFinite(before) || !Float.isFinite(target)) return null;
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
                id, entity.getUUID(), NEXT_REVISION.incrementAndGet(), externalCoverage, retryAttempt);
        Pending next = new Pending(new WeakReference<>(entity), entity.getClass(), before, target,
                server.getTickCount() + VERIFY_DELAY_TICKS, ticket, listener);
        Pending previous = PENDING.put(id, next);
        if (previous != null) {
            if (previous.ticket().entityUuid().equals(ticket.entityUuid())) {
                ExternalMirrorWriter.supersede(previous.ticket(), ticket);
                ExternalMirrorWriter.retarget(ticket, target);
            } else {
                ExternalMirrorWriter.revert(previous.ticket());
            }
            notifyOutcome(previous.listener(), Outcome.SUPERSEDED);
        }
        return ticket;
    }

    /* 服务端 tick 末尾复查到期条目。此时本 tick 的实体 tick 已经跑完，
       登记时若已在实体 tick 之后，到期判定会顺延一轮，因此复查前必然至少经过一次实体 tick。 */
    public static void onServerTick(MinecraftServer server) {
        if (server == null || PENDING.isEmpty()) return;
        int now = server.getTickCount();
        for (Iterator<Map.Entry<Integer, Pending>> iterator = PENDING.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<Integer, Pending> entry = iterator.next();
            if (now < entry.getValue().dueTick()) continue;
            iterator.remove();
            check(entry.getKey(), entry.getValue());
        }
    }

    //停服时清空：实体 id 会在下次启动重排，残留条目会拿旧目标值去比对新实体
    public static void clear() {
        for (Pending pending : PENDING.values()) notifyOutcome(pending.listener(), Outcome.INDETERMINATE);
        PENDING.clear();
        ExternalMirrorWriter.clear();
        SATURATION_DUMPED.set(false);
    }

    /* 复查一条到期记录，并据结论裁定第三阶段本次的外部联写：留住则提交，仍被回滚则撤销。
       无从判断时撤销推测性外部写入，但不据此否定已经完成的实体内写入。 */
    private static void check(int entityId, Pending pending) {
        LivingEntity entity = pending.entity().get();
        Ticket ticket = pending.ticket();
        // 目标为死亡时实体消失本身就是写入生效；正血目标无法据移除确认
        if (entity == null || entity.isRemoved()) {
            if (pending.target() <= 0.0f) {
                ExternalMirrorWriter.commit(ticket);
                notifyOutcome(pending.listener(), Outcome.PERSISTED);
            } else {
                ExternalMirrorWriter.revert(ticket);
                notifyOutcome(pending.listener(), Outcome.INDETERMINATE);
            }
            return;
        }
        if (entity.getId() != entityId || !entity.getUUID().equals(ticket.entityUuid())) {
            ExternalMirrorWriter.revert(ticket);
            notifyOutcome(pending.listener(), Outcome.INDETERMINATE);
            return;
        }
        /* 锚点已被证明与真实存储解耦时，它读回什么都不构成"被改回去了"的证据。
           此处据它判失败会把诱饵型目标上的每次成功都揭成假成功，并误启外部镜像。 */
        if (EcaSetHealthManager.isAnchorUntrusted(entity)) {
            ExternalMirrorWriter.revert(ticket);
            notifyOutcome(pending.listener(), Outcome.INDETERMINATE);
            return;
        }
        float actual = EcaSetHealthManager.readHealthAnchor(entity);
        if (!Float.isFinite(actual)) {
            ExternalMirrorWriter.revert(ticket);
            notifyOutcome(pending.listener(), Outcome.INDETERMINATE);
            return;
        }
        /* 沿本次写入方向检查留存：降血允许后续正常受伤，升血则不能把回落到旧值误认为成功。 */
        if (HealthValueSemantics.retainedAfterDelay(actual, pending.before(), pending.target())) {
            EcaSetHealthManager.onDelayedRetained(pending.entityClass());
            ExternalMirrorWriter.commit(ticket);
            notifyOutcome(pending.listener(), Outcome.PERSISTED);
            return;
        }

        Class<?> cls = pending.entityClass();
        if (ROLLBACK_DUMPED.add(cls.getName())) {
            EcaLogger.info("[DelayedVerify] write rolled back entity={} before={} target={} actual={} delay={}tick",
                    cls.getName(), pending.before(), pending.target(), actual, VERIFY_DELAY_TICKS);
        }
        ExternalMirrorWriter.revert(ticket);
        EcaSetHealthManager.onDelayedRollback(cls);
        if (!EcaSetHealthManager.retryAfterDelayedRollback(entity, pending.target(), ticket.retryAttempt(),
                pending.listener())) {
            notifyOutcome(pending.listener(), Outcome.ROLLED_BACK);
        }
    }

    public static void notifyOutcome(Consumer<Outcome> listener, Outcome outcome) {
        if (listener == null) return;
        try {
            listener.accept(outcome);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.info("[DelayedVerify] outcome listener failed type={} msg={}",
                    t.getClass().getName(), t.getMessage());
        }
    }
}
