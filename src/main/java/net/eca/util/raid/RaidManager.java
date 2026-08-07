package net.eca.util.raid;

import net.eca.api.RegisterRaid;
import net.eca.util.EcaLogger;
import net.eca.util.faction.FactionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 袭击管理器 — 定义注册表 + 每维度活跃袭击表 + tick 驱动。
 *
 * 活跃袭击按维度分表，持久化由每个维度自己的 RaidSavedData 负责，
 * 与阵营的"主世界统一存储"不同：袭击是维度局部的，不应跨维度共享。
 *
 * 袭击中心所在区块在袭击期间强制加载，避免无玩家在场时袭击停摆
 * （原版袭击在 hasChunkAt 失败时直接冻结）。区块的申请与释放全部由本类管理，
 * RaidInstance 不直接操作区块。
 */
public class RaidManager {

    private static final int PERSIST_INTERVAL_TICKS = 20;

    // 袭击定义注册表（id → RaidDefinition）
    private static final Map<String, RaidDefinition> RAID_DEFINITIONS = new ConcurrentHashMap<>();

    // 每维度活跃袭击表（dimension → raidId → RaidInstance）
    private static final Map<ResourceKey<Level>, Map<Integer, RaidInstance>> ACTIVE_RAIDS = new ConcurrentHashMap<>();

    // 已从 SavedData 加载过的维度
    private static final Set<ResourceKey<Level>> LOADED_LEVELS = ConcurrentHashMap.newKeySet();

    private RaidManager() {}

    // ==================== 注解扫描 ====================

    // 扫描全部 mod 的 @RegisterRaid 注解，实例化并注册袭击定义
    /**
     * Scan all loaded mods for classes annotated with {@link RegisterRaid}, instantiate each
     * {@link RaidDefinition}, and register it. Duplicate ids are logged and skipped
     * (first registration wins).
     * <p>
     * Must run after faction scanning — raid definitions reference faction ids for their
     * raider binding and faction-drawn waves.
     */
    public static void scanAndRegisterAll() {
        ModList.get().forEachModFile(modFile -> {
            for (IModInfo modInfo : modFile.getModInfos()) {
                modFile.getScanResult().getAnnotations().forEach(annotationData -> {
                    if (RegisterRaid.class.getName().equals(annotationData.annotationType().getClassName())) {
                        String className = annotationData.clazz().getClassName();
                        try {
                            Class<?> clazz = Class.forName(className, true,
                                    Thread.currentThread().getContextClassLoader());
                            registerFromDefinitionClass(clazz);
                        } catch (ClassNotFoundException e) {
                            EcaLogger.error("[Raid] Failed to load raid definition class {}: {}",
                                    className, e.getMessage());
                        }
                    }
                });
            }
        });
    }

    private static void registerFromDefinitionClass(Class<?> clazz) {
        if (!RaidDefinition.class.isAssignableFrom(clazz)) {
            EcaLogger.error("[Raid] Class {} is annotated with @RegisterRaid but does not extend RaidDefinition",
                    clazz.getName());
            return;
        }

        RaidDefinition def;
        try {
            def = (RaidDefinition) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            EcaLogger.error("[Raid] Failed to instantiate RaidDefinition {}: {}", clazz.getName(), e.getMessage());
            return;
        }

        String id = def.getId();
        if (id == null || id.isEmpty()) {
            EcaLogger.error("[Raid] RaidDefinition {} returned null or empty id — skipping", clazz.getName());
            return;
        }
        if (RAID_DEFINITIONS.containsKey(id)) {
            EcaLogger.error("[Raid] Duplicate raid id '{}' from {} — already registered, skipping",
                    id, clazz.getName());
            return;
        }

        RAID_DEFINITIONS.put(id, def);
    }

    // 获取袭击定义
    /**
     * @param raidId the raid definition id
     * @return the definition, or null if not registered
     */
    public static RaidDefinition getDefinition(String raidId) {
        if (raidId == null) return null;
        return RAID_DEFINITIONS.get(raidId);
    }

    // 获取全部袭击定义（只读）
    /**
     * @return read-only map of all registered raid definitions
     */
    public static Map<String, RaidDefinition> getAllDefinitions() {
        return Collections.unmodifiableMap(RAID_DEFINITIONS);
    }

    // ==================== 加载与持久化 ====================

    private static void ensureLoaded(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        if (LOADED_LEVELS.contains(dimension)) return;
        synchronized (RaidManager.class) {
            if (LOADED_LEVELS.contains(dimension)) return;
            RaidSavedData data = RaidSavedData.get(level);
            Map<Integer, RaidInstance> raids = ACTIVE_RAIDS.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
            for (RaidInstance raid : data.loadRaids()) {
                raids.put(raid.getId(), raid);
                forceLoadCenter(level, raid, true);
            }
            LOADED_LEVELS.add(dimension);
        }
    }

    private static void persist(ServerLevel level) {
        RaidSavedData data = RaidSavedData.get(level);
        data.storeRaids(getRaids(level).values());
    }

    private static Map<Integer, RaidInstance> getRaids(ServerLevel level) {
        return ACTIVE_RAIDS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
    }

    // ==================== 启动与结束 ====================

    // 在指定位置发起袭击（要求该位置位于袭击定义的目标结构内）
    /**
     * Start a raid at a position inside its target structure. The raid center is taken from
     * the structure's bounding box, not from {@code pos}. If the definition declares no
     * target structure, {@code pos} is used as the center directly.
     *
     * @param level  the server level
     * @param pos    a position inside the target structure
     * @param raidId the registered raid definition id
     * @return the started raid, or null if the definition is unknown or the position is
     *         not inside the target structure
     */
    public static RaidInstance startRaid(ServerLevel level, BlockPos pos, String raidId) {
        RaidDefinition def = getDefinition(raidId);
        if (def == null) {
            EcaLogger.info("[Raid] Cannot start unknown raid '{}'", raidId);
            return null;
        }

        BlockPos center = resolveCenter(level, def, pos);
        if (center == null) {
            EcaLogger.info("[Raid] Position {} is not inside the target structure of raid '{}'", pos, raidId);
            return null;
        }
        return createRaid(level, def, center);
    }

    // 在指定坐标强制发起袭击，跳过结构查询
    /**
     * Start a raid with an explicit center, bypassing the structure lookup. Useful for
     * arbitrary trigger conditions and for testing. Note that a structure-anchored
     * definition started this way will lose immediately if its default defeat condition
     * is left in place and the center is not inside the target structure.
     *
     * @param level  the server level
     * @param center the raid center
     * @param raidId the registered raid definition id
     * @return the started raid, or null if the definition is unknown
     */
    public static RaidInstance startRaidAt(ServerLevel level, BlockPos center, String raidId) {
        RaidDefinition def = getDefinition(raidId);
        if (def == null) {
            EcaLogger.info("[Raid] Cannot start unknown raid '{}'", raidId);
            return null;
        }
        return createRaid(level, def, center);
    }

    private static BlockPos resolveCenter(ServerLevel level, RaidDefinition def, BlockPos pos) {
        ResourceKey<Structure> key = def.getTargetStructure();
        if (key != null) {
            StructureStart start = level.structureManager().getStructureWithPieceAt(pos, key);
            return start.isValid() ? start.getBoundingBox().getCenter() : null;
        }
        TagKey<Structure> tag = def.getTargetStructureTag();
        if (tag != null) {
            StructureStart start = level.structureManager().getStructureWithPieceAt(pos, tag);
            return start.isValid() ? start.getBoundingBox().getCenter() : null;
        }
        // 未锚定结构：直接以给定坐标为中心
        return pos;
    }

    /*
     * 启动前校验袭击定义引用的阵营。
     *
     * 袭击者阵营缺失会让整场袭击的敌我判定失效——袭击者不攻击防守方、防守方也不认识它们，
     * 表现为"袭击开始了但怪在发呆"，且原因只埋在日志里，因此直接拒绝启动。
     * 波次抽取阵营的问题只影响该波的一部分，记录错误但允许启动。
     *
     * 必须先触发阵营加载：isFactionRegistered 不会自行加载存档，否则动态创建的阵营
     * 会在这里被误判为未注册。
     */
    private static boolean validateFactions(ServerLevel level, RaidDefinition def) {
        FactionManager.ensureLoaded(level);

        String raiderFaction = def.getRaiderFactionId();
        if (raiderFaction != null && !raiderFaction.isEmpty()
                && !FactionManager.isFactionRegistered(raiderFaction)) {
            EcaLogger.error("[Raid] Cannot start raid '{}': raider faction '{}' is not registered — "
                    + "raiders would spawn without faction bindings and ignore defenders",
                    def.getId(), raiderFaction);
            return false;
        }

        List<RaidWave> waves = def.getWaves();
        if (waves == null) return true;
        for (int i = 0; i < waves.size(); i++) {
            for (String drawId : waves.get(i).getFactionCounts().keySet()) {
                if (!FactionManager.isFactionRegistered(drawId)) {
                    EcaLogger.error("[Raid] Raid '{}' wave {} draws from unregistered faction '{}' — "
                            + "that group will be skipped", def.getId(), i, drawId);
                } else if (FactionManager.getMemberEntityTypes(drawId).isEmpty()) {
                    EcaLogger.error("[Raid] Raid '{}' wave {} draws from faction '{}' which declares no "
                            + "member entity types — that group will be skipped", def.getId(), i, drawId);
                }
            }
        }
        return true;
    }

    private static RaidInstance createRaid(ServerLevel level, RaidDefinition def, BlockPos center) {
        ensureLoaded(level);
        if (!validateFactions(level, def)) {
            return null;
        }
        RaidSavedData data = RaidSavedData.get(level);
        int raidId = data.nextRaidId();

        RaidInstance raid = new RaidInstance(raidId, def.getId(), center);
        getRaids(level).put(raidId, raid);
        forceLoadCenter(level, raid, true);

        def.onStart(new RaidContext(level, raid));
        persist(level);
        EcaLogger.info("[Raid] Started raid '{}' (id {}) at {}", def.getId(), raidId, center);
        return raid;
    }

    // 结束一场袭击并清除全部存活袭击者
    /**
     * End a raid, discarding every surviving raider. This is the intended way to finish an
     * endless raid, which never satisfies the default victory condition.
     *
     * @param level   the server level
     * @param raidId  the raid instance id
     * @param victory true to end in victory (fires reward callbacks), false for defeat
     * @return true if a matching active raid was found and ended
     */
    public static boolean endRaid(ServerLevel level, int raidId, boolean victory) {
        ensureLoaded(level);
        RaidInstance raid = getRaids(level).get(raidId);
        if (raid == null) return false;
        raid.end(level, victory);
        persist(level);
        return true;
    }

    // 结束一场袭击并清除全部存活袭击者
    /**
     * End a raid instance directly.
     *
     * @param level   the server level
     * @param raid    the raid to end
     * @param victory true to end in victory, false for defeat
     * @return true if the raid was active in this level
     */
    public static boolean endRaid(ServerLevel level, RaidInstance raid, boolean victory) {
        if (raid == null) return false;
        return endRaid(level, raid.getId(), victory);
    }

    // ==================== 查询 ====================

    // 按 ID 获取活跃袭击
    /**
     * @param level  the server level
     * @param raidId the raid instance id
     * @return the active raid, or null
     */
    public static RaidInstance getRaid(ServerLevel level, int raidId) {
        ensureLoaded(level);
        return getRaids(level).get(raidId);
    }

    // 获取该维度全部活跃袭击
    /**
     * @param level the server level
     * @return all active raids in this level
     */
    public static List<RaidInstance> getActiveRaids(ServerLevel level) {
        ensureLoaded(level);
        return new ArrayList<>(getRaids(level).values());
    }

    // 获取距指定坐标最近的活跃袭击
    /**
     * Find the nearest active raid whose center is within {@code maxDistance} of a position.
     *
     * @param level       the server level
     * @param pos         the position to search from
     * @param maxDistance maximum distance in blocks
     * @return the nearest raid in range, or null
     */
    public static RaidInstance getNearestRaid(ServerLevel level, BlockPos pos, double maxDistance) {
        ensureLoaded(level);
        double bestSq = maxDistance * maxDistance;
        RaidInstance best = null;
        for (RaidInstance raid : getRaids(level).values()) {
            if (raid.isOver()) continue;
            double distSq = raid.getCenter().distSqr(pos);
            if (distSq <= bestSq) {
                bestSq = distSq;
                best = raid;
            }
        }
        return best;
    }

    // ==================== 驱动 ====================

    // 推进该维度的全部活跃袭击
    /**
     * Tick every active raid in a level and retire the ones that have stopped.
     *
     * @param level the level to tick
     */
    public static void tickDimension(ServerLevel level) {
        ensureLoaded(level);
        Map<Integer, RaidInstance> raids = getRaids(level);
        if (raids.isEmpty()) return;

        boolean changed = false;
        // ConcurrentHashMap 的迭代是弱一致的，回调中启动或结束袭击不会抛 CME
        for (RaidInstance raid : raids.values()) {
            RaidStatus previousStatus = raid.getStatus();
            raid.tick(level);
            if (raid.getStatus() != previousStatus) {
                changed = true;
            }
            if (raid.getStatus() == RaidStatus.STOPPED) {
                raids.remove(raid.getId());
                forceLoadCenter(level, raid, false);
                changed = true;
            }
        }
        // 定期抓取运行状态，兼顾重启恢复精度与 SavedData 序列化开销。
        if (changed || level.getGameTime() % PERSIST_INTERVAL_TICKS == 0L) {
            persist(level);
        }
    }

    // 实体永久移除时同步袭击者减员
    /**
     * Report a permanently removed entity to every active raid in its level.
     * Callers must only pass entities that are gone for good — a chunk unload is not
     * a casualty, and reporting one would end raids early.
     *
     * @param level  the level the entity was removed from
     * @param entity the removed entity
     */
    public static void onEntityRemoved(ServerLevel level, Entity entity) {
        if (entity == null) return;
        ensureLoaded(level);
        Map<Integer, RaidInstance> raids = getRaids(level);
        if (raids.isEmpty()) return;

        UUID uuid = entity.getUUID();
        for (RaidInstance raid : raids.values()) {
            if (raid.onRaiderRemoved(uuid)) {
                // 永久减员必须立即落盘，避免重启后恢复已不存在的袭击者 UUID。
                persist(level);
                break;
            }
        }
    }

    // ==================== 区块常驻 ====================

    // 申请或释放袭击中心区块的强制加载
    private static void forceLoadCenter(ServerLevel level, RaidInstance raid, boolean add) {
        ChunkPos chunk = new ChunkPos(raid.getCenter());
        if (!add) {
            level.setChunkForced(chunk.x, chunk.z, false);
            return;
        }
        /* 申请侧内部会同步阻塞取块。本类的 startRaid/endRaid 等入口是对外 API，
           调用上下文不受控——若在区块状态回调内被调到，阻塞取块会重入区块票据距离更新
           并破坏其集合迭代；且 ensureLoaded 持有静态锁，不应在锁内做阻塞 IO。
           推迟到主线程任务队列顶层执行。释放侧不阻塞且必须同步：
           服务器停止后任务队列不再排空，延迟释放会丢失。 */
        level.getServer().execute(() -> {
            // 延迟期间袭击可能已结束，届时不再申请，否则票据无人释放
            if (getRaids(level).get(raid.getId()) == raid) {
                level.setChunkForced(chunk.x, chunk.z, true);
            }
        });
    }

    // ==================== 清理 ====================

    // 清空随存档变化的全部状态
    /**
     * Clear all per-save raid state on server stop, releasing every forced chunk.
     * The definition registry is preserved — annotation scanning runs once per process.
     *
     * @param levels the server levels to release forced chunks in; may be empty
     */
    public static void clearAll(Iterable<ServerLevel> levels) {
        if (levels != null) {
            for (ServerLevel level : levels) {
                Map<Integer, RaidInstance> raids = ACTIVE_RAIDS.get(level.dimension());
                if (raids == null) continue;
                for (RaidInstance raid : raids.values()) {
                    forceLoadCenter(level, raid, false);
                }
            }
        }
        ACTIVE_RAIDS.clear();
        LOADED_LEVELS.clear();
    }
}
