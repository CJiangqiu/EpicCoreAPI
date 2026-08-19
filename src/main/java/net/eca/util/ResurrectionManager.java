package net.eca.util;

import net.eca.api.EcaAPI;
import net.eca.config.EcaConfiguration;
import net.eca.util.health.HealthLockManager;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Collections;
import java.util.List;
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
 * Each poll cycle checks whether every tracked entity is still present on both the
 * server and the client. While it is intact, the entity state is snapshotted into a
 * detached record; once anything is missing, the record drives the rebuild of exactly
 * the side that lost it.
 * <p>
 * The record never depends on the live instance surviving: a hostile implementation may
 * clear every container, mark the entity removed and wipe the states ECA attached to it,
 * so the snapshot carries the full NBT and the intended lock values on its own.
 */
public final class ResurrectionManager {

    private static final long DEFAULT_POLL_INTERVAL_MS = 25L;
    /* 客户端在场只能靠一次网络往返得知，往返本身就比服务端轮询慢一到两个数量级，
       跟着服务端间隔发只会堆积无效请求，故单独一个远更长的间隔。 */
    private static final long DEFAULT_CLIENT_POLL_INTERVAL_MS = 1000L;
    /* 快照要序列化整份 NBT，比在场检查贵得多，按自己的节奏走。 */
    private static final long DEFAULT_SNAPSHOT_INTERVAL_MS = 500L;
    private static final long CLIENT_ANSWER_TIMEOUT_MS = 3000L;
    /* 重建失败（类型加载不出来、join 被拒）会在下一轮原样重来，不设冷却就是按轮询频率
       往主线程队列灌任务并刷日志。 */
    private static final long REBUILD_COOLDOWN_MS = 1000L;
    /* 维度未知时要遍历所有世界做兜底查找，代价远高于常规巡检，不能跟着轮询走。 */
    private static final long LEVEL_SCAN_COOLDOWN_MS = 1000L;

    private static final String CLIENT_PRESENCE_KEY = "ClientLevel.getEntity(uuid)";

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicLong totalChecks = new AtomicLong(0);
    private static final AtomicLong totalSnapshots = new AtomicLong(0);
    private static final AtomicLong totalServerRepairs = new AtomicLong(0);
    private static final AtomicLong totalRebuilds = new AtomicLong(0);
    private static final AtomicLong totalClientRepairs = new AtomicLong(0);

    private static final Map<UUID, ResurrectionRecord> records = new ConcurrentHashMap<>();
    private static final Set<UUID> inProgress = ConcurrentHashMap.newKeySet();

    private static volatile long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
    private static volatile long clientPollIntervalMs = DEFAULT_CLIENT_POLL_INTERVAL_MS;
    private static volatile long snapshotIntervalMs = DEFAULT_SNAPSHOT_INTERVAL_MS;
    private static volatile Thread workerThread;

    private ResurrectionManager() {}

    // ==================== 线程控制 ====================

    public static synchronized void start() {
        if (running.getAndSet(true)) {
            EcaLogger.info("[ResurrectionManager] Already running");
            return;
        }

        workerThread = new Thread(() -> {
            EcaLogger.info("[ResurrectionManager] Started, pollInterval={}ms clientPollInterval={}ms tracked={}",
                    pollIntervalMs, clientPollIntervalMs, records.size());

            while (running.get()) {
                try {
                    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                    if (server == null || !server.isRunning() || records.isEmpty()) {
                        sleepOneCycle();
                        continue;
                    }

                    for (ResurrectionRecord record : records.values()) {
                        if (!running.get()) break;
                        if (!inProgress.add(record.uuid)) continue;
                        try {
                            totalChecks.incrementAndGet();
                            processRecord(server, record);
                        } catch (Exception e) {
                            EcaLogger.info("[ResurrectionManager] Error uuid={} msg={}", record.uuid, e.getMessage());
                        } finally {
                            inProgress.remove(record.uuid);
                        }
                    }
                } catch (Exception e) {
                    EcaLogger.info("[ResurrectionManager] Loop error: {}", e.getMessage());
                }

                sleepOneCycle();
            }

            EcaLogger.info("[ResurrectionManager] Stopped, checks={} snapshots={} serverRepairs={} rebuilds={} clientRepairs={}",
                    totalChecks.get(), totalSnapshots.get(), totalServerRepairs.get(),
                    totalRebuilds.get(), totalClientRepairs.get());
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
    public static long getTotalRevivedCount() { return totalServerRepairs.get() + totalRebuilds.get(); }
    public static long getTotalCheckCount() { return totalChecks.get(); }
    public static long getTotalSnapshotCount() { return totalSnapshots.get(); }
    public static long getTotalServerRepairCount() { return totalServerRepairs.get(); }
    public static long getTotalRebuildCount() { return totalRebuilds.get(); }
    public static long getTotalClientRepairCount() { return totalClientRepairs.get(); }

    public static void setPollIntervalMs(long ms) {
        pollIntervalMs = Math.max(1L, Math.min(ms, 10000L));
    }
    public static long getPollIntervalMs() { return pollIntervalMs; }

    public static void setClientPollIntervalMs(long ms) {
        clientPollIntervalMs = Math.max(100L, Math.min(ms, 60000L));
    }
    public static long getClientPollIntervalMs() { return clientPollIntervalMs; }

    public static void setSnapshotIntervalMs(long ms) {
        snapshotIntervalMs = Math.max(50L, Math.min(ms, 60000L));
    }
    public static long getSnapshotIntervalMs() { return snapshotIntervalMs; }

    // ==================== 单实体巡检 ====================

    /* 检查与容器修复留在本线程（沿用既有行为，毫秒级轮询经不起主线程排队）；
       快照、从快照重建、客户端重配对一律投递主线程——它们要读写实体全量状态或往
       世界里加实体，与 tick 并发会直接踩到数据竞争。 */
    private static void processRecord(MinecraftServer server, ResurrectionRecord record) {
        ServerLevel level = resolveLevel(server, record);
        if (level == null) return;

        Entity entity = resolveUsableInstance(level, record);

        if (entity == null) {
            /* 实例已彻底不存在：容器、引用都救不回来，只能拿脱离式记录重建。 */
            scheduleRebuild(server, level, record);
            return;
        }

        if (EntityUtil.isChangingDimension(entity)) return;

        record.instance = entity;
        record.lastNetworkId = entity.getId();

        Map<String, Boolean> containers = EntityUtil.checkEntityInServerContainers(level, record.uuid);
        boolean serverIntact = allTrue(containers);
        boolean healthy = isHealthy(entity);

        if (serverIntact && healthy) {
            scheduleSnapshot(server, level, entity, record);
        } else {
            if (!serverIntact && EcaConfiguration.getDefenceEnableRadicalLogicSafely()) {
                EntityUtil.reviveAllContainersDirect(level, entity);
                totalServerRepairs.incrementAndGet();
            }
            if (!healthy) {
                scheduleStateRestore(server, entity, record);
            }
        }

        probeClient(server, level, entity, record);
    }

    // 记录不持 ServerLevel 引用，每轮按维度键解析；维度未知时全维度找一次并记下来
    private static ServerLevel resolveLevel(MinecraftServer server, ResurrectionRecord record) {
        ResourceKey<Level> dimension = record.dimension;
        if (dimension != null) {
            ServerLevel level = server.getLevel(dimension);
            if (level != null) return level;
        }

        long now = System.currentTimeMillis();
        if (now - record.lastLevelScanAt < LEVEL_SCAN_COOLDOWN_MS) return null;
        record.lastLevelScanAt = now;

        for (ServerLevel candidate : server.getAllLevels()) {
            if (EntityUtil.getEntity(candidate, record.uuid) != null) {
                record.dimension = candidate.dimension();
                return candidate;
            }
        }
        return null;
    }

    /* 引用还在不等于实例还能用：敌人可以把它降级成一具尸体，或用同 UUID 换一个别的实例。
       身份与所属世界对不上就当它已经没了，走重建，不要往错的对象上写。 */
    private static Entity resolveUsableInstance(ServerLevel level, ResurrectionRecord record) {
        Entity entity = EntityUtil.getEntity(level, record.uuid);
        if (entity == null) {
            Entity cached = record.instance;
            if (cached != null && cached.level() == level) {
                entity = cached;
            }
        }
        if (entity == null) return null;
        if (!record.uuid.equals(entity.getUUID())) return null;
        if (entity.level() != level) return null;
        return entity;
    }

    private static boolean allTrue(Map<String, Boolean> containers) {
        for (Boolean present : containers.values()) {
            if (!Boolean.TRUE.equals(present)) return false;
        }
        return !containers.isEmpty();
    }

    private static boolean isHealthy(Entity entity) {
        if (!isStructurallyAlive(entity)) return false;
        if (entity instanceof LivingEntity living) {
            return EntityUtil.getHealth(living) > 0.0f;
        }
        return true;
    }

    /* 不读血量的活性判据。getHealth 要先过一遍血量锚点解析，够不上按位移频率调用的开销，
       而位置记录本来也只需要知道实体没在死亡/移除流程里。 */
    private static boolean isStructurallyAlive(Entity entity) {
        if (entity.isRemoved() || entity.getRemovalReason() != null) return false;
        if (entity instanceof LivingEntity living) {
            return !living.dead && living.deathTime <= 0;
        }
        return true;
    }

    // ==================== 快照 ====================

    private static void scheduleSnapshot(MinecraftServer server, ServerLevel level, Entity entity, ResurrectionRecord record) {
        long now = System.currentTimeMillis();
        if (now - record.lastSnapshotAt < snapshotIntervalMs) return;
        record.lastSnapshotAt = now;
        server.execute(() -> captureSnapshot(level, entity, record));
    }

    /* 只在实体确实健康时更新记录。被打死的那一刻容器往往还在，若照记不误，
       记下的就是"0 血 + 已死"，重建时再拿它还原等于把死亡状态原样存档再写回去。 */
    static void captureSnapshot(ServerLevel level, Entity entity, ResurrectionRecord record) {
        if (entity == null || !isHealthy(entity)) return;
        try {
            /* 用 saveWithoutId 再自己补 id 字段：save/saveAsPassenger 在 removalReason 不可存档
               或类型无编码名时直接返回 false，正是最需要快照的时候拿不到快照。 */
            CompoundTag tag = new CompoundTag();
            entity.saveWithoutId(tag);
            ResourceLocation typeId = EntityType.getKey(entity.getType());
            if (typeId == null) return;
            tag.putString("id", typeId.toString());

            record.nbt = tag;
            record.typeId = typeId;
            record.dimension = level.dimension();
            record.position = entity.position();
            record.yRot = entity.getYRot();
            record.xRot = entity.getXRot();
            record.lastNetworkId = entity.getId();
            record.instance = entity;

            if (entity instanceof LivingEntity living) {
                record.health = EntityUtil.getHealth(living);
                captureEcaState(living, record);
            }
            totalSnapshots.incrementAndGet();
        } catch (Exception e) {
            EcaLogger.info("[ResurrectionManager] snapshot failed uuid={} msg={}", record.uuid, e.getMessage());
        }
    }

    /* 读不到就保留旧值，绝不写 null：状态被外部抹掉时若跟着清记录，
       等于把篡改结果存档，之后的还原只会把篡改固化。撤销只能走 remove()。 */
    private static void captureEcaState(LivingEntity living, ResurrectionRecord record) {
        Float lock = HealthLockManager.getLock(living);
        if (lock != null) record.healthLock = lock;

        Float maxLock = HealthLockManager.getMaxHealthLock(living);
        if (maxLock != null) record.maxHealthLock = maxLock;

        Float healBan = HealthLockManager.getHealBan(living);
        if (healBan != null) record.healBan = healBan;

        if (EcaAPI.isInvulnerable(living)) record.invulnerable = true;
    }

    // ==================== 服务端重建 ====================

    private static void scheduleRebuild(MinecraftServer server, ServerLevel level, ResurrectionRecord record) {
        if (!record.hasSnapshot()) return;
        long now = System.currentTimeMillis();
        if (now - record.lastRebuildAt < REBUILD_COOLDOWN_MS) return;
        record.lastRebuildAt = now;
        server.execute(() -> rebuildFromSnapshot(level, record));
    }

    static void rebuildFromSnapshot(ServerLevel level, ResurrectionRecord record) {
        /* 判定在巡检线程做出，落地在主线程；这中间实体可能已经自己回来了，
           不复查会凭空多出一个同 UUID 的副本。 */
        if (EntityUtil.getEntity(level, record.uuid) != null) return;

        CompoundTag snapshot = record.nbt;
        if (snapshot == null) return;

        try {
            Entity rebuilt = EntityType.loadEntityRecursive(snapshot.copy(), level, loaded -> loaded);
            if (rebuilt == null) {
                EcaLogger.info("[ResurrectionManager] rebuild failed: type not loadable uuid={} type={}",
                        record.uuid, record.typeId);
                return;
            }

            rebuilt.setUUID(record.uuid);
            Vec3 position = record.position;
            if (position != null) {
                rebuilt.moveTo(position.x, position.y, position.z, record.yRot, record.xRot);
            }

            if (!level.addFreshEntity(rebuilt)) {
                EcaLogger.info("[ResurrectionManager] rebuild rejected on join uuid={}", record.uuid);
                return;
            }

            record.instance = rebuilt;
            record.lastNetworkId = rebuilt.getId();
            applyRecordState(rebuilt, record);
            totalRebuilds.incrementAndGet();

            /* 新实例的 ChunkMap 追踪是全新的、seenBy 为空，原版会主动给范围内玩家发生成包，
               客户端不需要额外处理。 */
            EcaLogger.info("[ResurrectionManager] rebuilt entity uuid={} type={} id={}",
                    record.uuid, record.typeId, rebuilt.getId());
        } catch (Exception e) {
            EcaLogger.info("[ResurrectionManager] rebuild error uuid={} msg={}", record.uuid, e.getMessage());
        }
    }

    private static void scheduleStateRestore(MinecraftServer server, Entity entity, ResurrectionRecord record) {
        server.execute(() -> applyRecordState(entity, record));
    }

    /* 清死亡状态并把记录里的状态写回去。血量取快照值而非最大值——复活的目标是
       回到出事前的那个实体，无条件顶满等于每轮覆盖真实状态。 */
    static void applyRecordState(Entity entity, ResurrectionRecord record) {
        if (entity == null) return;
        try {
            entity.revive();
            EntityUtil.clearRemovalReasonIfProtected(entity);

            if (!(entity instanceof LivingEntity living)) return;

            living.dead = false;
            living.deathTime = 0;
            living.hurtTime = 0;
            living.setPose(Pose.STANDING);

            /* 顺序有讲究：setInvulnerable 会按 max(当前血量, 最大生命) 自行上锁，
               先让它跑完，再用记录里的意图值覆盖，最后才落血量。 */
            if (record.invulnerable && !EcaAPI.isInvulnerable(living)) {
                EcaAPI.setInvulnerable(living, true);
            }
            if (record.healthLock != null) {
                HealthLockManager.setLock(living, record.healthLock);
            }
            if (record.maxHealthLock != null) {
                HealthLockManager.setMaxHealthLock(living, record.maxHealthLock);
            }
            if (record.healBan != null) {
                HealthLockManager.setHealBan(living, record.healBan);
            }
            if (EntityUtil.RESURRECTION_TRACKED != null) {
                living.getEntityData().set(EntityUtil.RESURRECTION_TRACKED, true);
            }

            float target = record.health > 0.0f ? record.health : living.getMaxHealth();
            EntityUtil.setHealth(living, target);
        } catch (Exception e) {
            EcaLogger.info("[ResurrectionManager] state restore failed uuid={} msg={}", record.uuid, e.getMessage());
        }
    }

    // ==================== 客户端在场 ====================

    /* 客户端在场是三态：在 / 不在 / 未知。只有明确回执说不在才修，回执超时或没回执一律
       当未知——网络抖一下就判客户端没有会引发误重建，代价比漏修一轮大得多。 */
    private static void probeClient(MinecraftServer server, ServerLevel level, Entity entity, ResurrectionRecord record) {
        long now = System.currentTimeMillis();
        if (now - record.lastClientProbeAt < clientPollIntervalMs) return;
        record.lastClientProbeAt = now;

        server.execute(() -> {
            List<ServerPlayer> players = EntityUtil.getTrackingPlayers(level, entity);
            for (ServerPlayer player : players) {
                EntityUtil.requestClientContainerCheckAsync(player, record.uuid)
                        .orTimeout(CLIENT_ANSWER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .whenComplete((response, error) -> {
                            if (error != null || response == null) return;
                            if (!Boolean.FALSE.equals(response.get(CLIENT_PRESENCE_KEY))) return;
                            server.execute(() -> {
                                if (EntityUtil.repairClientPairing(level, entity, player)) {
                                    totalClientRepairs.incrementAndGet();
                                    EcaLogger.info("[ResurrectionManager] client pairing repaired uuid={} player={}",
                                            record.uuid, player.getGameProfile().getName());
                                }
                            });
                        });
            }
        });
    }

    // ==================== 实体追踪 ====================

    public static void add(Entity entity) {
        if (entity == null) return;
        ResurrectionRecord record = records.computeIfAbsent(entity.getUUID(), ResurrectionRecord::new);
        record.instance = entity;
        record.lastNetworkId = entity.getId();
        if (entity.level() instanceof ServerLevel serverLevel) {
            record.dimension = serverLevel.dimension();
            /* 实体尚未入世时不快照：本方法也在 readAdditionalSaveData 里被调用，
               而 saveWithoutId 会回调实体自身的 addAdditionalSaveData，
               半初始化状态下调它并不安全。首轮巡检会在主线程补上这份快照。 */
            if (EntityUtil.getEntity(serverLevel, entity.getUUID()) != null) {
                captureSnapshot(serverLevel, entity, record);
            }
        }
        if (entity instanceof LivingEntity living && EntityUtil.RESURRECTION_TRACKED != null) {
            living.getEntityData().set(EntityUtil.RESURRECTION_TRACKED, true);
        }
    }

    public static void add(UUID uuid) {
        if (uuid != null) {
            records.computeIfAbsent(uuid, ResurrectionRecord::new);
        }
    }

    public static void remove(Entity entity) {
        if (entity == null) return;
        records.remove(entity.getUUID());
        if (entity instanceof LivingEntity living && EntityUtil.RESURRECTION_TRACKED != null) {
            living.getEntityData().set(EntityUtil.RESURRECTION_TRACKED, false);
        }
    }

    public static void remove(UUID uuid) {
        if (uuid != null) records.remove(uuid);
    }

    public static boolean isTracked(UUID uuid) {
        return uuid != null && records.containsKey(uuid);
    }

    public static void recordPosition(Entity entity) {
        if (entity == null) return;
        ResurrectionRecord record = records.get(entity.getUUID());
        if (record == null) return;
        if (!(entity.level() instanceof ServerLevel serverLevel)) return;
        if (!isStructurallyAlive(entity) || EntityUtil.isChangingDimension(entity)) return;

        record.instance = entity;
        record.dimension = serverLevel.dimension();
        record.position = entity.position();
        record.yRot = entity.getYRot();
        record.xRot = entity.getXRot();
    }

    public static Set<UUID> getTrackedUUIDs() {
        return Collections.unmodifiableSet(records.keySet());
    }

    public static int getTrackedCount() { return records.size(); }

    public static void clearAll() { records.clear(); }

    // ==================== 单次检查 ====================

    public static Map<String, Boolean> check(ServerLevel level, UUID uuid) {
        return EntityUtil.checkEntityInContainers(level, uuid);
    }

    public static Map<String, Boolean> reviveNow(ServerLevel level, UUID uuid) {
        ResurrectionRecord record = records.get(uuid);
        Entity entity = record != null ? record.instance : null;
        if (entity == null) entity = EntityUtil.getEntity(level, uuid);

        if (entity == null) {
            if (record != null && record.hasSnapshot()) {
                rebuildFromSnapshot(level, record);
                return EntityUtil.checkEntityInServerContainers(level, uuid);
            }
            EcaLogger.info("[ResurrectionManager] reviveNow: entity not found uuid={}", uuid);
            return Collections.emptyMap();
        }
        if (EntityUtil.isChangingDimension(entity)) {
            EcaLogger.info("[ResurrectionManager] reviveNow: changing dimension uuid={}", uuid);
            return Collections.emptyMap();
        }

        EntityUtil.reviveAllContainersDirect(level, entity);
        if (record != null) applyRecordState(entity, record);
        return EntityUtil.checkEntityInServerContainers(level, uuid);
    }

    // ==================== 内部 ====================

    private static void sleepOneCycle() {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(pollIntervalMs));
    }
}
