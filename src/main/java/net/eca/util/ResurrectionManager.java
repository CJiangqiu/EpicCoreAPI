package net.eca.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Independent daemon-thread entity resurrection manager.
 * <p>
 * Maintains its own tracked-entity map and continuously monitors every
 * tracked entity. When an entity dies or is removed from containers,
 * {@link EntityUtil#revive(LivingEntity)} is called to fully restore it.
 * <p>
 * This thread runs entirely outside the Forge event bus and MC tick loop.
 * <h3>Usage</h3>
 * <pre>{@code
 * ResurrectionManager.start();
 * ResurrectionManager.add(entity);
 * // entity is now monitored — any death/removal triggers auto-revival
 * ResurrectionManager.stop();
 * }</pre>
 */
public final class ResurrectionManager {

    private static final long DEFAULT_POLL_INTERVAL_MS = 25L;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicLong totalRevived = new AtomicLong(0);
    private static final AtomicLong totalChecks = new AtomicLong(0);

    private static final Map<UUID, Entity> trackedEntities = new ConcurrentHashMap<>();
    private static final Set<UUID> inProgress = ConcurrentHashMap.newKeySet();

    private static volatile long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
    private static volatile Thread workerThread;

    private ResurrectionManager() {}

    // ==================== 线程控制 ====================

    public static synchronized void start() {
        if (running.getAndSet(true)) {
            EcaLogger.info("[ResurrectionManager] Already running");
            return;
        }

        workerThread = new Thread(() -> {
            EcaLogger.info("[ResurrectionManager] Started, pollInterval={}ms tracked={}",
                    pollIntervalMs, trackedEntities.size());

            while (running.get()) {
                try {
                    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                    if (server == null || !server.isRunning()) {
                        sleepOneCycle();
                        continue;
                    }

                    if (trackedEntities.isEmpty()) {
                        sleepOneCycle();
                        continue;
                    }

                    int revivedThisRound = 0;
                    int checkedThisRound = 0;
                    Map<UUID, Entity> snapshot = Map.copyOf(trackedEntities);

                    for (Map.Entry<UUID, Entity> entry : snapshot.entrySet()) {
                        if (!running.get()) break;
                        UUID uuid = entry.getKey();
                        Entity entity = entry.getValue();

                        if (!inProgress.add(uuid)) continue;
                        try {
                            checkedThisRound++;

                            if (entity == null) {
                                trackedEntities.remove(uuid);
                                continue;
                            }

                            if (EntityUtil.isChangingDimension(entity)) continue;

                            if (!(entity.level() instanceof ServerLevel)) {
                                if (entity.isRemoved()) trackedEntities.remove(uuid);
                                continue;
                            }

                            if (entity instanceof LivingEntity living) {
                                EntityUtil.revive(living);
                                revivedThisRound++;
                            }
                        } catch (Exception e) {
                            EcaLogger.info("[ResurrectionManager] Error uuid={} msg={}",
                                    uuid, e.getMessage());
                        } finally {
                            inProgress.remove(uuid);
                        }
                    }

                    if (revivedThisRound > 0) totalRevived.addAndGet(revivedThisRound);
                    if (checkedThisRound > 0) totalChecks.addAndGet(checkedThisRound);

                } catch (Exception e) {
                    EcaLogger.info("[ResurrectionManager] Loop error: {}", e.getMessage());
                }

                sleepOneCycle();
            }

            EcaLogger.info("[ResurrectionManager] Stopped, totalRevived={} totalChecks={}",
                    totalRevived.get(), totalChecks.get());
        }, "ECA-ResurrectionManager");

        workerThread.setDaemon(true);
        workerThread.setPriority(Thread.NORM_PRIORITY - 1);
        workerThread.start();
    }

    public static synchronized void stop() {
        if (!running.getAndSet(false)) return;

        Thread t = workerThread;
        if (t != null) {
            try {
                t.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        workerThread = null;
        inProgress.clear();
    }

    public static boolean isRunning() { return running.get(); }
    public static long getTotalRevivedCount() { return totalRevived.get(); }
    public static long getTotalCheckCount() { return totalChecks.get(); }

    public static void setPollIntervalMs(long ms) {
        pollIntervalMs = Math.max(1L, Math.min(ms, 10000L));
    }
    public static long getPollIntervalMs() { return pollIntervalMs; }

    // ==================== 实体追踪 ====================

    public static void add(Entity entity) {
        if (entity == null) return;
        trackedEntities.put(entity.getUUID(), entity);
        if (entity instanceof LivingEntity living && EntityUtil.RESURRECTION_TRACKED != null) {
            living.getEntityData().set(EntityUtil.RESURRECTION_TRACKED, true);
        }
    }

    public static void add(UUID uuid) {
        if (uuid != null) trackedEntities.putIfAbsent(uuid, null);
    }

    public static void remove(Entity entity) {
        if (entity == null) return;
        trackedEntities.remove(entity.getUUID());
        if (entity instanceof LivingEntity living && EntityUtil.RESURRECTION_TRACKED != null) {
            living.getEntityData().set(EntityUtil.RESURRECTION_TRACKED, false);
        }
    }

    public static void remove(UUID uuid) {
        if (uuid != null) trackedEntities.remove(uuid);
    }

    public static boolean isTracked(UUID uuid) {
        return uuid != null && trackedEntities.containsKey(uuid);
    }

    public static Set<UUID> getTrackedUUIDs() {
        return Collections.unmodifiableSet(trackedEntities.keySet());
    }

    public static int getTrackedCount() { return trackedEntities.size(); }

    public static void clearAll() { trackedEntities.clear(); }

    // ==================== 单次检查 ====================

    public static Map<String, Boolean> check(ServerLevel level, UUID uuid) {
        return EntityUtil.checkEntityInContainers(level, uuid);
    }

    public static Map<String, Boolean> reviveNow(ServerLevel level, UUID uuid) {
        Entity entity = trackedEntities.get(uuid);
        if (entity == null) entity = EntityUtil.getEntity(level, uuid);
        if (entity == null) {
            EcaLogger.info("[ResurrectionManager] reviveNow: entity not found uuid={}", uuid);
            return Collections.emptyMap();
        }
        if (EntityUtil.isChangingDimension(entity)) {
            EcaLogger.info("[ResurrectionManager] reviveNow: changing dimension uuid={}", uuid);
            return Collections.emptyMap();
        }
        return EntityUtil.checkEntityInServerContainers(level, uuid);
    }

    // ==================== 内部 ====================

    private static void sleepOneCycle() {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(pollIntervalMs));
    }
}
