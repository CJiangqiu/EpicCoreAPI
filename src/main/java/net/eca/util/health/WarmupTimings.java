package net.eca.util.health;

import net.eca.coremod.EarlyLogWriter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

// 仅在预热线程启用，嵌套阶段暂停父级计时，避免转换等待被重复计入分析。
public final class WarmupTimings {
    public enum Stage { ANALYSIS, BRIDGE, BYTECODE, TRANSFORM, SIGNATURES, PARSE, CACHE_CHECK, FRAMES, METHOD_LOOKUP }
    enum CacheEvent {
        HIT, MISS, INVALIDATED, NODE_HIT, SIGNATURE_HIT, EVICTED,
        SNAPSHOT_BYTES_HIT, SNAPSHOT_PARSED_HIT, SNAPSHOT_INVALIDATED,
        FRAME_FIRST, FRAME_REPEAT, FRAME_UNTRACKED, METHOD_RESULT_HIT, SEEDED_CALL
    }
    private static final ThreadLocal<Session> ACTIVE = new ThreadLocal<>();
    private static final Scope DISABLED = new Scope(null, null, null);

    private WarmupTimings() {}

    private static final class Session {
        final long started = System.nanoTime();
        final long[] nanos = new long[Stage.values().length];
        final long[] calls = new long[Stage.values().length];
        final long[] cacheEvents = new long[CacheEvent.values().length];
        final List<SlowTarget> slowest = new ArrayList<>();
        final IdentityHashMap<Object, Boolean> frameMethods = new IdentityHashMap<>();
        Scope current;
        int targets;
    }

    private record SlowTarget(String label, long nanos, long analysis, long bridge,
                              long bytecode, long transform, long signatures, long parse, long cacheCheck,
                              long frames, long methodLookup) {}

    public static final class Scope implements AutoCloseable {
        private final Session session;
        private final Stage stage;
        private final Scope parent;
        private long resumed;

        private Scope(Session session, Stage stage, Scope parent) {
            this.session = session;
            this.stage = stage;
            this.parent = parent;
        }

        @Override
        public void close() {
            if (session == null) return;
            long now = System.nanoTime();
            session.nanos[stage.ordinal()] += now - resumed;
            session.current = parent;
            if (parent != null) parent.resumed = now;
        }
    }

    public static Scope enter(Stage stage) {
        Session session = ACTIVE.get();
        if (session == null) return DISABLED;
        long now = System.nanoTime();
        Scope parent = session.current;
        if (parent != null) session.nanos[parent.stage.ordinal()] += now - parent.resumed;
        Scope scope = new Scope(session, stage, parent);
        scope.resumed = now;
        session.current = scope;
        session.calls[stage.ordinal()]++;
        return scope;
    }

    static void begin() {
        ACTIVE.set(new Session());
    }

    static void count(CacheEvent event) {
        Session session = ACTIVE.get();
        if (session != null) session.cacheEvents[event.ordinal()]++;
    }

    // 仅统计同一目标内的节点重复；参数和上下文不同的求解不代表可共享结果。
    static void recordFrames(Object method) {
        Session session = ACTIVE.get();
        if (session == null) return;
        CacheEvent event;
        if (session.frameMethods.containsKey(method)) event = CacheEvent.FRAME_REPEAT;
        else if (session.frameMethods.size() >= 4096) event = CacheEvent.FRAME_UNTRACKED;
        else {
            session.frameMethods.put(method, Boolean.TRUE);
            event = CacheEvent.FRAME_FIRST;
        }
        session.cacheEvents[event.ordinal()]++;
    }

    static long[] snapshot() {
        Session session = ACTIVE.get();
        return session == null ? null : session.nanos.clone();
    }

    static void recordTarget(Class<?> type, long started, long[] before) {
        Session session = ACTIVE.get();
        if (session == null || before == null) return;
        session.frameMethods.clear();
        long elapsed = System.nanoTime() - started;
        int index = ++session.targets;
        String label = type.getName().startsWith("net.minecraft.") ? type.getName() : "external-target#" + index;
        session.slowest.add(new SlowTarget(label, elapsed,
                session.nanos[0] - before[0], session.nanos[1] - before[1],
                session.nanos[2] - before[2], session.nanos[3] - before[3],
                session.nanos[4] - before[4], session.nanos[5] - before[5], session.nanos[6] - before[6],
                session.nanos[7] - before[7], session.nanos[8] - before[8]));
        session.slowest.sort(Comparator.comparingLong(SlowTarget::nanos).reversed());
        if (session.slowest.size() > 5) session.slowest.remove(5);
    }

    static void finish() {
        Session session = ACTIVE.get();
        ACTIVE.remove();
        if (session == null) return;
        long elapsed = System.nanoTime() - session.started;
        long accounted = 0;
        StringBuilder line = new StringBuilder("[WarmupTiming] targets=").append(session.targets)
                .append(" elapsedMs=").append(elapsed / 1_000_000L);
        for (Stage stage : Stage.values()) {
            long nanos = session.nanos[stage.ordinal()];
            accounted += nanos;
            line.append(' ').append(stage).append("Ms=").append(nanos / 1_000_000L)
                    .append(' ').append(stage).append("Calls=").append(session.calls[stage.ordinal()]);
        }
        line.append(" otherMs=").append(Math.max(0, elapsed - accounted) / 1_000_000L);
        for (CacheEvent event : CacheEvent.values()) {
            line.append(" cache_").append(event).append('=').append(session.cacheEvents[event.ordinal()]);
        }
        EarlyLogWriter.info(line.toString());
        for (SlowTarget target : session.slowest) {
            EarlyLogWriter.info("[WarmupTiming] slow=" + target.label() + " elapsedMs=" + target.nanos() / 1_000_000L
                    + " analysisMs=" + target.analysis() / 1_000_000L + " bridgeMs=" + target.bridge() / 1_000_000L
                    + " bytecodeMs=" + target.bytecode() / 1_000_000L + " transformMs=" + target.transform() / 1_000_000L
                    + " signaturesMs=" + target.signatures() / 1_000_000L + " parseMs=" + target.parse() / 1_000_000L
                    + " cacheCheckMs=" + target.cacheCheck() / 1_000_000L
                    + " framesMs=" + target.frames() / 1_000_000L
                    + " methodLookupMs=" + target.methodLookup() / 1_000_000L);
        }
    }
}
