package net.eca.util.raid;

import net.eca.network.NetworkHandler;
import net.eca.network.RaidBossBarSyncPacket;
import net.eca.util.EcaLogger;
import net.eca.util.faction.FactionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/*
 * 单场袭击的运行时状态。
 *
 * 与原版 Raid 的关键差异：
 *   - 目标由结构锚定（或完全不锚定），不绑定村庄
 *   - 袭击者用 UUID 集合跟踪，不要求实现任何接口，敌我判定交给阵营系统
 *   - 波次推进与胜负判定全部委托给 RaidDefinition，本类只负责驱动
 *
 * 减员判定由事件驱动（RaidManager 在实体永久移除时回调 onRaiderRemoved），
 * 轮询只清理"能解析到且已死亡"的条目——解析不到的可能只是区块未加载，不能算减员。
 */
public class RaidInstance {

    private static final String NBT_ID = "id";
    private static final String NBT_DEFINITION = "definition";
    private static final String NBT_CENTER_X = "centerX";
    private static final String NBT_CENTER_Y = "centerY";
    private static final String NBT_CENTER_Z = "centerZ";
    private static final String NBT_STATUS = "status";
    private static final String NBT_WAVES_SPAWNED = "wavesSpawned";
    private static final String NBT_WAVES_COMPLETED = "wavesCompleted";
    private static final String NBT_TICKS_ACTIVE = "ticksActive";
    private static final String NBT_WAVE_COOLDOWN = "waveCooldown";
    private static final String NBT_CELEBRATION = "celebrationTicks";
    private static final String NBT_WAVE_TOTAL = "currentWaveTotal";
    private static final String NBT_STARTED = "started";
    private static final String NBT_RAIDERS = "raiders";

    private final int id;
    private final String definitionId;
    private BlockPos center;
    private RaidStatus status = RaidStatus.ONGOING;
    private int wavesSpawned = 0;
    private int wavesCompleted = 0;
    private long ticksActive = 0L;
    private int waveCooldown = 0;
    private int celebrationTicks = 0;
    // 初值不能为 0：首波生成前也会刷新血条，用作进度分母时会算出 NaN
    private int currentWaveTotal = 1;
    private boolean started = false;
    private final Set<UUID> raiderUuids = new LinkedHashSet<>();

    private List<RaidWave> cachedWaves;
    private ServerBossEvent bossEvent;

    // 阵营绑定失败只警告一次，避免每个袭击者刷一条日志
    private boolean factionBindingWarned = false;

    // 上次已同步到客户端的状态，用于只在内容变化时发包而非每 tick 广播
    private int lastSyncedWaves = -1;
    private int lastSyncedAlive = -1;
    private int lastSyncedWaveTotal = -1;
    private RaidStatus lastSyncedStatus = null;

    // 创建一场袭击
    /**
     * @param id           unique raid id within the level
     * @param definitionId the registered raid definition id
     * @param center       the raid center
     */
    public RaidInstance(int id, String definitionId, BlockPos center) {
        this.id = id;
        this.definitionId = definitionId;
        this.center = center;
    }

    // ==================== 基础访问 ====================

    // 获取袭击 ID
    public int getId() {
        return id;
    }

    // 获取袭击定义 ID
    public String getDefinitionId() {
        return definitionId;
    }

    // 获取袭击定义
    /**
     * @return the registered definition, or null if the defining mod was removed
     */
    public RaidDefinition getDefinition() {
        return RaidManager.getDefinition(definitionId);
    }

    // 获取袭击中心
    public BlockPos getCenter() {
        return center;
    }

    // 设置袭击中心
    public void setCenter(BlockPos center) {
        this.center = center;
    }

    // 获取当前状态
    public RaidStatus getStatus() {
        return status;
    }

    // 获取已生成波次数
    public int getWavesSpawned() {
        return wavesSpawned;
    }

    // 获取袭击已持续 tick 数
    public long getTicksActive() {
        return ticksActive;
    }

    // 获取当前存活袭击者数量
    public int getAliveRaiderCount() {
        return raiderUuids.size();
    }

    // 获取当前波次生成的袭击者总数（血条进度的分母）
    public int getCurrentWaveTotal() {
        return currentWaveTotal;
    }

    // 获取全部袭击者 UUID（只读）
    public Set<UUID> getRaiderUuids() {
        return Collections.unmodifiableSet(raiderUuids);
    }

    // 袭击是否已结束（胜利、失败或已停止）
    /**
     * @return true if the raid is no longer progressing
     */
    public boolean isOver() {
        return status == RaidStatus.VICTORY || status == RaidStatus.DEFEAT || status == RaidStatus.STOPPED;
    }

    // 获取定义的总波次数
    public int getWaveCount() {
        List<RaidWave> waves = getWaves();
        return waves == null ? 0 : waves.size();
    }

    // 是否已生成全部波次（无限波次恒为 false）
    public boolean isAllWavesSpawned() {
        RaidDefinition def = getDefinition();
        if (def == null) return true;
        if (def.isEndless()) return false;
        return wavesSpawned >= getWaveCount();
    }

    private List<RaidWave> getWaves() {
        if (cachedWaves == null) {
            RaidDefinition def = getDefinition();
            cachedWaves = def == null ? Collections.emptyList() : def.getWaves();
            if (cachedWaves == null) cachedWaves = Collections.emptyList();
        }
        return cachedWaves;
    }

    // ==================== 目标判定 ====================

    // 袭击目标是否完好
    /**
     * Check whether the target structure still covers the raid center.
     * Raids started without structure anchoring always report intact.
     *
     * @param level the level to query
     * @return true if the target is intact or the raid is unanchored
     */
    public boolean isTargetIntact(ServerLevel level) {
        RaidDefinition def = getDefinition();
        if (def == null) return false;

        ResourceKey<Structure> key = def.getTargetStructure();
        if (key != null) {
            return level.structureManager().getStructureWithPieceAt(center, key).isValid();
        }
        TagKey<Structure> tag = def.getTargetStructureTag();
        if (tag != null) {
            return level.structureManager().getStructureWithPieceAt(center, tag).isValid();
        }
        return true;
    }

    // ==================== 驱动 ====================

    // 推进一 tick
    /**
     * Advance the raid by one tick. Drives wave spawning, victory/defeat checks,
     * the boss bar, and the post-result celebration countdown.
     *
     * @param level the level this raid runs in
     */
    public void tick(ServerLevel level) {
        if (status == RaidStatus.STOPPED) return;

        RaidDefinition def = getDefinition();
        if (def == null) {
            EcaLogger.info("[Raid] Raid {} references unknown definition '{}' — stopping", id, definitionId);
            stop();
            return;
        }

        if (status == RaidStatus.VICTORY || status == RaidStatus.DEFEAT) {
            tickCelebration(level, def);
            return;
        }

        ticksActive++;

        int maxDuration = def.getMaxDurationTicks();
        if (maxDuration > 0 && ticksActive >= maxDuration) {
            EcaLogger.info("[Raid] Raid {} ('{}') timed out after {} ticks", id, definitionId, ticksActive);
            setDefeat(level, def);
            return;
        }

        // 周期性维护：清理确认死亡的袭击者、刷新血条可见玩家
        if (ticksActive % 20L == 0L) {
            pruneResolvedDeadRaiders(level);
            updateBossBarPlayers(level, def);
        }

        RaidContext ctx = new RaidContext(level, this);

        if (def.checkDefeat(ctx)) {
            setDefeat(level, def);
            return;
        }

        notifyWaveEnd(def, ctx);

        if (started && def.checkVictory(ctx)) {
            setVictory(level, def);
            return;
        }

        tickWaveProgress(level, def, ctx);
        updateBossBarDisplay(level, def);
    }

    private void tickWaveProgress(ServerLevel level, RaidDefinition def, RaidContext ctx) {
        if (isAllWavesSpawned()) return;
        if (!def.shouldAdvanceWave(ctx)) return;

        if (waveCooldown > 0) {
            waveCooldown--;
            return;
        }
        spawnNextWave(level, def);
    }

    // 结束回调必须早于胜利与下一波开始，并通过持久化计数保证只触发一次
    private void notifyWaveEnd(RaidDefinition def, RaidContext ctx) {
        if (!started || !raiderUuids.isEmpty() || wavesCompleted >= wavesSpawned) {
            return;
        }

        int completedWaveNumber = wavesSpawned - 1;
        int waveCount = getWaveCount();
        int waveIndex = def.isEndless() && waveCount > 0
                ? completedWaveNumber % waveCount
                : completedWaveNumber;
        wavesCompleted = wavesSpawned;
        def.onWaveEnd(ctx, waveIndex);
    }

    private void tickCelebration(ServerLevel level, RaidDefinition def) {
        celebrationTicks++;
        if (celebrationTicks >= def.getCelebrationTicks()) {
            stop();
            return;
        }
        if (celebrationTicks % 20 == 0) {
            updateBossBarPlayers(level, def);
            updateBossBarDisplay(level, def);
        }
    }

    // ==================== 波次生成 ====================

    private void spawnNextWave(ServerLevel level, RaidDefinition def) {
        List<RaidWave> waves = getWaves();
        if (waves.isEmpty()) {
            EcaLogger.info("[Raid] Raid definition '{}' declares no waves — stopping raid {}", definitionId, id);
            stop();
            return;
        }

        int index = def.isEndless() ? (wavesSpawned % waves.size()) : wavesSpawned;
        if (index >= waves.size()) return;

        RaidWave wave = waves.get(index);
        RandomSource random = level.getRandom();
        int spawned = 0;

        for (RaidSpawnEntry entry : wave.getEntries()) {
            for (int i = 0; i < entry.getCount(); i++) {
                if (spawnRaider(level, def, entry.getType(), wave, random, entry.getPostSpawn()) != null) {
                    spawned++;
                }
            }
        }

        for (Map.Entry<String, Integer> factionEntry : wave.getFactionCounts().entrySet()) {
            String factionId = factionEntry.getKey();
            for (int i = 0; i < factionEntry.getValue(); i++) {
                EntityType<?> type = FactionManager.rollMemberType(factionId, random);
                if (type == null) {
                    // 类型池不可用，跳过该阵营在本波的剩余数量（rollMemberType 已记录原因）
                    break;
                }
                if (spawnRaider(level, def, type, wave, random, null) != null) {
                    spawned++;
                }
            }
        }

        if (spawnWaveLeader(level, def, wave, random)) {
            spawned++;
        }

        wavesSpawned++;
        started = true;
        currentWaveTotal = Math.max(spawned, 1);
        waveCooldown = def.getWaveCooldownTicks() + wave.getSpawnDelayTicks();

        def.onWaveStart(new RaidContext(level, this), index);
        updateBossBarDisplay(level, def);
    }

    // 生成本波声明的首领，并将其设为袭击者阵营的首领
    private boolean spawnWaveLeader(ServerLevel level, RaidDefinition def, RaidWave wave, RandomSource random) {
        RaidSpawnEntry leaderEntry = wave.getLeaderEntry();
        if (leaderEntry == null) return false;

        Entity leader = spawnRaider(level, def, leaderEntry.getType(), wave, random, leaderEntry.getPostSpawn());
        if (leader == null) return false;

        String factionId = def.getRaiderFactionId();
        if (factionId == null || factionId.isEmpty()) {
            // 没有阵营就没有可领导的对象，该实体作为普通袭击者存在
            EcaLogger.info("[Raid] Raid {} ('{}') declares a wave leader but no raider faction — spawned as an ordinary raider",
                    id, definitionId);
            return true;
        }
        if (!FactionManager.setLeader(factionId, leader, level)) {
            EcaLogger.error("[Raid] Raid {} ('{}') could not promote its wave leader in faction '{}'",
                    id, definitionId, factionId);
        }
        return true;
    }

    private Entity spawnRaider(ServerLevel level, RaidDefinition def, EntityType<?> type,
                               RaidWave wave, RandomSource random, Consumer<Mob> postSpawn) {
        if (type == null) return null;

        BlockPos pos = findSpawnPos(level, wave.getSpawnRadius(), type, random);
        if (pos == null) {
            EcaLogger.info("[Raid] Raid {} found no valid spawn position for {} within {} blocks",
                    id, type.getDescriptionId(), wave.getSpawnRadius());
            return null;
        }

        Entity entity = type.create(level);
        if (entity == null) return null;

        entity.setPos(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        if (entity instanceof Mob mob) {
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
            mob.setOnGround(true);
            level.addFreshEntityWithPassengers(mob);
        } else {
            level.addFreshEntity(entity);
        }

        registerRaider(entity, def);
        if (postSpawn != null && entity instanceof Mob mob) {
            postSpawn.accept(mob);
        }
        return entity;
    }

    // 将实体登记为本场袭击的袭击者：跟踪、入营、注入寻路 Goal
    private void registerRaider(Entity entity, RaidDefinition def) {
        raiderUuids.add(entity.getUUID());

        String factionId = def.getRaiderFactionId();
        if (factionId != null && !factionId.isEmpty()
                && !FactionManager.joinFaction(entity, factionId) && !factionBindingWarned) {
            // 每场袭击只警告一次：绑定失败会让整批袭击者都失去敌我判定，逐个刷屏没有意义
            factionBindingWarned = true;
            EcaLogger.error("[Raid] Raid {} ('{}') could not bind raiders to faction '{}' — they will ignore "
                    + "faction rules and defenders will not recognise them", id, definitionId, factionId);
        }

        int priority = def.getRaiderGoalPriority();
        if (priority >= 0 && entity instanceof Mob mob) {
            mob.goalSelector.addGoal(priority, new MoveToRaidCenterGoal(mob, this, 1.0));
        }
    }

    // 仿原版 findRandomSpawnPos：随机角度取地表，校验区块已加载、位置可 tick、且适合该实体落地
    private BlockPos findSpawnPos(ServerLevel level, double radius, EntityType<?> type, RandomSource random) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int attempt = 0; attempt < 16; attempt++) {
            float angle = random.nextFloat() * ((float) Math.PI * 2);
            int x = center.getX() + Mth.floor(Mth.cos(angle) * radius) + random.nextInt(5);
            int z = center.getZ() + Mth.floor(Mth.sin(angle) * radius) + random.nextInt(5);
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            mutable.set(x, y, z);

            if (!level.hasChunksAt(x - 10, z - 10, x + 10, z + 10)) continue;
            if (!level.isPositionEntityTicking(mutable)) continue;
            if (!NaturalSpawner.isSpawnPositionOk(SpawnPlacements.Type.ON_GROUND, level, mutable, type)) continue;

            return mutable.immutable();
        }
        return null;
    }

    // ==================== 袭击者跟踪 ====================

    // 袭击者被永久移除时调用（由 RaidManager 在实体离开世界且 shouldDestroy 时驱动）
    /**
     * Report that a raider is permanently gone. Chunk unloads must not be reported here —
     * an unloaded raider is still part of the raid and will return when its chunk reloads.
     *
     * @param uuid the raider's UUID
     * @return true if the UUID belonged to this raid
     */
    public boolean onRaiderRemoved(UUID uuid) {
        return raiderUuids.remove(uuid);
    }

    // 清理已确认死亡的袭击者；解析不到的实体可能只是区块未加载，予以保留
    private void pruneResolvedDeadRaiders(ServerLevel level) {
        raiderUuids.removeIf(uuid -> {
            Entity entity = level.getEntity(uuid);
            return entity != null && !entity.isAlive();
        });
    }

    // ==================== 结束 ====================

    // 主动结束袭击并清除全部存活袭击者
    /**
     * End the raid immediately, discarding every surviving raider. This is the intended
     * way to finish an endless raid, which never satisfies the default victory condition.
     *
     * @param level   the level this raid runs in
     * @param victory true to end in victory (fires rewards), false to end in defeat
     */
    public void end(ServerLevel level, boolean victory) {
        RaidDefinition def = getDefinition();
        clearRaiders(level);
        if (def == null) {
            stop();
            return;
        }
        if (victory) {
            setVictory(level, def);
        } else {
            setDefeat(level, def);
        }
    }

    // 清除全部存活袭击者
    private void clearRaiders(ServerLevel level) {
        for (UUID uuid : new ArrayList<>(raiderUuids)) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) {
                // discard 的 RemovalReason 会让阵营系统一并清掉持久化绑定
                entity.discard();
            }
        }
        raiderUuids.clear();
    }

    private void setVictory(ServerLevel level, RaidDefinition def) {
        status = RaidStatus.VICTORY;
        celebrationTicks = 0;
        RaidContext ctx = new RaidContext(level, this);
        def.onVictory(ctx);
        def.onStop(ctx);
        updateBossBarDisplay(level, def);
    }

    private void setDefeat(ServerLevel level, RaidDefinition def) {
        status = RaidStatus.DEFEAT;
        celebrationTicks = 0;
        RaidContext ctx = new RaidContext(level, this);
        def.onDefeat(ctx);
        def.onStop(ctx);
        updateBossBarDisplay(level, def);
    }

    // 停止袭击并移除血条（不触发胜负回调）
    /**
     * Stop ticking this raid and tear down its boss bar. Does not fire victory or defeat
     * callbacks — those already ran when the outcome was decided.
     */
    public void stop() {
        status = RaidStatus.STOPPED;
        if (bossEvent != null) {
            // 解除客户端映射，否则该 BossEvent UUID 的状态会一直留在客户端表里
            for (ServerPlayer player : new ArrayList<>(bossEvent.getPlayers())) {
                NetworkHandler.sendToPlayer(new RaidBossBarSyncPacket(bossEvent.getId(), null), player);
            }
            bossEvent.removeAllPlayers();
            bossEvent.setVisible(false);
        }
    }

    // ==================== Boss 血条 ====================

    private ServerBossEvent getBossEvent(RaidDefinition def) {
        if (bossEvent == null) {
            bossEvent = new ServerBossEvent(Component.translatable(def.getDisplayName()),
                    def.getBossBarColor(), BossEvent.BossBarOverlay.NOTCHED_10);
        }
        return bossEvent;
    }

    private void updateBossBarPlayers(ServerLevel level, RaidDefinition def) {
        ServerBossEvent bar = getBossEvent(def);
        List<ServerPlayer> nearby = new RaidContext(level, this).getNearbyPlayers();
        Set<ServerPlayer> shown = new HashSet<>(bar.getPlayers());

        RaidBarState state = null;
        for (ServerPlayer player : nearby) {
            if (!shown.contains(player)) {
                bar.addPlayer(player);
                // 新加入的玩家没收到过状态，立即补发一次
                if (state == null) {
                    state = RaidBarState.of(this);
                }
                NetworkHandler.sendToPlayer(new RaidBossBarSyncPacket(bar.getId(), state), player);
            }
        }
        for (ServerPlayer player : shown) {
            if (!nearby.contains(player)) {
                bar.removePlayer(player);
                NetworkHandler.sendToPlayer(new RaidBossBarSyncPacket(bar.getId(), null), player);
            }
        }
    }

    // 状态变化时向订阅了血条的玩家广播袭击快照
    private void syncBarStateIfChanged(ServerBossEvent bar) {
        int alive = getAliveRaiderCount();
        if (wavesSpawned == lastSyncedWaves && alive == lastSyncedAlive
                && currentWaveTotal == lastSyncedWaveTotal && status == lastSyncedStatus) {
            return;
        }
        lastSyncedWaves = wavesSpawned;
        lastSyncedAlive = alive;
        lastSyncedWaveTotal = currentWaveTotal;
        lastSyncedStatus = status;

        RaidBarState state = RaidBarState.of(this);
        for (ServerPlayer player : new ArrayList<>(bar.getPlayers())) {
            NetworkHandler.sendToPlayer(new RaidBossBarSyncPacket(bar.getId(), state), player);
        }
    }

    private void updateBossBarDisplay(ServerLevel level, RaidDefinition def) {
        ServerBossEvent bar = getBossEvent(def);
        Component name = Component.translatable(def.getDisplayName());

        if (status == RaidStatus.VICTORY) {
            bar.setName(name.copy().append(" - ").append(Component.translatable("raid.eca.victory")));
            bar.setProgress(0.0f);
            syncBarStateIfChanged(bar);
            return;
        }
        if (status == RaidStatus.DEFEAT) {
            bar.setName(name.copy().append(" - ").append(Component.translatable("raid.eca.defeat")));
            bar.setProgress(0.0f);
            syncBarStateIfChanged(bar);
            return;
        }

        int alive = getAliveRaiderCount();
        Component suffix = def.isEndless()
                ? Component.translatable("raid.eca.wave_endless", wavesSpawned)
                : Component.translatable("raid.eca.wave", wavesSpawned, getWaveCount());
        bar.setName(name.copy().append(" - ").append(suffix)
                .append(" (").append(Component.translatable("raid.eca.raiders_remaining", alive)).append(")"));
        bar.setProgress(Mth.clamp((float) alive / (float) currentWaveTotal, 0.0f, 1.0f));
        syncBarStateIfChanged(bar);
    }

    // ==================== 持久化 ====================

    // 写入 NBT
    /**
     * @param tag the tag to write into
     * @return the same tag, for chaining
     */
    public CompoundTag save(CompoundTag tag) {
        tag.putInt(NBT_ID, id);
        tag.putString(NBT_DEFINITION, definitionId);
        tag.putInt(NBT_CENTER_X, center.getX());
        tag.putInt(NBT_CENTER_Y, center.getY());
        tag.putInt(NBT_CENTER_Z, center.getZ());
        tag.putString(NBT_STATUS, status.name());
        tag.putInt(NBT_WAVES_SPAWNED, wavesSpawned);
        tag.putInt(NBT_WAVES_COMPLETED, wavesCompleted);
        tag.putLong(NBT_TICKS_ACTIVE, ticksActive);
        tag.putInt(NBT_WAVE_COOLDOWN, waveCooldown);
        tag.putInt(NBT_CELEBRATION, celebrationTicks);
        tag.putInt(NBT_WAVE_TOTAL, currentWaveTotal);
        tag.putBoolean(NBT_STARTED, started);

        ListTag raiders = new ListTag();
        for (UUID uuid : raiderUuids) {
            raiders.add(StringTag.valueOf(uuid.toString()));
        }
        tag.put(NBT_RAIDERS, raiders);
        return tag;
    }

    // 从 NBT 读取
    /**
     * @param tag the tag to read from
     * @return the restored raid, or null if the tag is malformed
     */
    public static RaidInstance load(CompoundTag tag) {
        String definitionId = tag.getString(NBT_DEFINITION);
        if (definitionId.isEmpty()) return null;

        BlockPos center = new BlockPos(tag.getInt(NBT_CENTER_X), tag.getInt(NBT_CENTER_Y), tag.getInt(NBT_CENTER_Z));
        RaidInstance raid = new RaidInstance(tag.getInt(NBT_ID), definitionId, center);

        try {
            raid.status = RaidStatus.valueOf(tag.getString(NBT_STATUS));
        } catch (IllegalArgumentException e) {
            raid.status = RaidStatus.ONGOING;
        }
        raid.wavesSpawned = tag.getInt(NBT_WAVES_SPAWNED);
        raid.ticksActive = tag.getLong(NBT_TICKS_ACTIVE);
        raid.waveCooldown = tag.getInt(NBT_WAVE_COOLDOWN);
        raid.celebrationTicks = tag.getInt(NBT_CELEBRATION);
        raid.currentWaveTotal = Math.max(1, tag.getInt(NBT_WAVE_TOTAL));
        raid.started = tag.getBoolean(NBT_STARTED);

        ListTag raiders = tag.getList(NBT_RAIDERS, Tag.TAG_STRING);
        for (int i = 0; i < raiders.size(); i++) {
            try {
                raid.raiderUuids.add(UUID.fromString(raiders.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // 非法 UUID 字符串，跳过
            }
        }
        raid.wavesCompleted = tag.contains(NBT_WAVES_COMPLETED)
                ? Mth.clamp(tag.getInt(NBT_WAVES_COMPLETED), 0, raid.wavesSpawned)
                : Math.max(0, raid.wavesSpawned - (raid.raiderUuids.isEmpty() ? 0 : 1));
        return raid;
    }
}
