package net.eca.util.faction;

import net.eca.api.RegisterFaction;
import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 阵营管理器 — 阵营定义 + 成员表 + 首领 + 关系查询
 *
 * 数据模型：
 *   Faction 是唯一权威，成员表与首领都嵌在其中，随 FactionSavedData 持久化。
 *   MEMBER_INDEX 与 LEADER_INDEX 是由成员表派生的反向索引，只存在于内存，
 *   加载时重建——因此不存在两张表写不同步的可能。
 *
 * getFactionId 四层查询：
 *   1. FACTION_MEMBER_IDS   — 快速路径提示，纯性能，丢失不影响正确性
 *   2. ENTITY_FACTION_CACHE  — WeakHashMap<Entity, String>，实体存活期间缓存
 *   3. MEMBER_INDEX          — UUID→factionId 反向索引，权威结果
 *   4. 主人继承              — 驯服动物无自身绑定时继承主人阵营，纯计算不落库
 *
 * 持久化统一存于主世界 DataStorage，不依赖实体 NBT / SynchedEntityData。
 */
public class FactionManager {

    // ==================== 权威数据 ====================

    // 阵营注册表（id → Faction，含成员表与首领）
    private static final Map<String, Faction> FACTIONS = new ConcurrentHashMap<>();

    // 阵营定义对象注册表（id → FactionDefinition，进程级，不随存档清除）
    private static final Map<String, FactionDefinition> FACTION_DEFINITIONS = new ConcurrentHashMap<>();

    // ==================== 派生索引与缓存 ====================

    // 成员 UUID → factionId，由各阵营成员表派生
    private static final Map<UUID, String> MEMBER_INDEX = new ConcurrentHashMap<>();

    // 首领 UUID → factionId，由各阵营首领字段派生
    private static final Map<UUID, String> LEADER_INDEX = new ConcurrentHashMap<>();

    // 快速路径：按 entityId 记录当前属于任意阵营的实体
    private static final Set<Integer> FACTION_MEMBER_IDS = ConcurrentHashMap.newKeySet();

    // 实体 → 阵营 ID 缓存（弱引用 key，实体被 GC 后自动清除，无内存泄漏）
    private static final Map<Entity, String> ENTITY_FACTION_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    // 首领仇恨传导节流记录（factionId → 上次传导的目标与时刻）
    private static final Map<String, Propagation> LAST_PROPAGATION = new ConcurrentHashMap<>();

    // 是否已从 SavedData 加载
    private static volatile boolean loaded = false;

    private static final class Propagation {
        final UUID target;
        final long gameTime;

        Propagation(UUID target, long gameTime) {
            this.target = target;
            this.gameTime = gameTime;
        }
    }

    private FactionManager() {}

    // ==================== SavedData 辅助 ====================

    /*
     * 阵营数据统一存于主世界。
     *
     * FACTIONS 与派生索引都是全局静态的，持久化层必须同样全局才自洽：
     * 若按实体所在维度分别存储，则非主世界写入的数据会因 ensureLoaded 只加载一次
     * 而在重启后永久丢失，跨维度的阵营成员将失去敌我判定。
     */
    private static FactionSavedData getSavedData(Entity entity) {
        if (entity == null) return null;
        return getSavedData(entity.level());
    }

    private static FactionSavedData getSavedData(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return null;
        MinecraftServer server = serverLevel.getServer();
        if (server == null) return null;
        return FactionSavedData.get(server.overworld());
    }

    // 将一个阵营的当前状态写回存档
    private static void persist(Faction faction, Level level) {
        if (faction == null) return;
        FactionSavedData data = getSavedData(level);
        if (data != null) {
            data.putFaction(faction);
        }
    }

    // ==================== 加载与清理 ====================

    // 尝试从世界数据加载（惰性、幂等）
    /**
     * Attempt to load factions from SavedData. Annotation-registered factions are rebuilt
     * first as a baseline, then SavedData entries are applied on top so persisted runtime
     * edits take precedence. Derived indexes are rebuilt from the loaded member tables.
     * Idempotent — subsequent calls are no-ops until {@link #clearAll()} resets the state.
     *
     * @param level any server level (uses the overworld's DataStorage)
     */
    public static void ensureLoaded(Level level) {
        if (loaded) return;
        FactionSavedData data = getSavedData(level);
        if (data == null) return;
        synchronized (FactionManager.class) {
            if (loaded) return;
            rebuildFactionsFromDefinitions();
            data.loadFactions();
            rebuildIndexes();
            loaded = true;
        }
    }

    // 供 FactionSavedData 回填（避免循环依赖）
    static void putLoadedFaction(Faction faction) {
        FACTIONS.put(faction.getId(), faction);
    }

    // 由各阵营的成员表与首领重建反向索引
    private static void rebuildIndexes() {
        MEMBER_INDEX.clear();
        LEADER_INDEX.clear();
        for (Faction faction : FACTIONS.values()) {
            for (UUID uuid : faction.getMemberUuids()) {
                MEMBER_INDEX.put(uuid, faction.getId());
            }
            FactionMember leader = faction.getLeader();
            if (leader != null) {
                LEADER_INDEX.put(leader.getUuid(), faction.getId());
            }
        }
    }

    // 由 @RegisterFaction 定义重建阵营基线，使注解阵营不随存档切换丢失
    private static void rebuildFactionsFromDefinitions() {
        for (FactionDefinition def : FACTION_DEFINITIONS.values()) {
            FACTIONS.put(def.getId(), buildFactionFrom(def));
        }
    }

    // 清空随存档变化的全部状态
    /**
     * Clear all per-save faction state. Called on server stop so a single-player session
     * that opens a second save does not inherit the first save's factions.
     * <p>
     * {@code FACTION_DEFINITIONS} is intentionally preserved — annotation scanning runs
     * once per process, and {@link #ensureLoaded} rebuilds from it on the next load.
     */
    public static void clearAll() {
        synchronized (FactionManager.class) {
            FACTIONS.clear();
            MEMBER_INDEX.clear();
            LEADER_INDEX.clear();
            FACTION_MEMBER_IDS.clear();
            ENTITY_FACTION_CACHE.clear();
            LAST_PROPAGATION.clear();
            loaded = false;
        }
    }

    // ==================== 阵营定义管理 ====================

    // 注册一个阵营（内存，不持久化；供纯内存场景使用）
    /**
     * Register a faction in memory only (no SavedData persistence).
     * For persisted registration, use {@link #registerFaction(Faction, Level)}.
     *
     * @param faction the faction to register
     */
    public static void registerFaction(Faction faction) {
        if (faction == null || faction.getId() == null || faction.getId().isEmpty()) return;
        FACTIONS.put(faction.getId(), faction);
        indexFaction(faction);
    }

    // 注册一个阵营（持久化到 SavedData）
    /**
     * Register a new faction and persist it to SavedData.
     *
     * @param faction the faction to register
     * @param level   the server level for SavedData persistence
     */
    public static void registerFaction(Faction faction, Level level) {
        if (faction == null || faction.getId() == null || faction.getId().isEmpty()) return;
        ensureLoaded(level);
        FACTIONS.put(faction.getId(), faction);
        indexFaction(faction);
        persist(faction, level);
    }

    private static void indexFaction(Faction faction) {
        for (UUID uuid : faction.getMemberUuids()) {
            MEMBER_INDEX.put(uuid, faction.getId());
        }
        FactionMember leader = faction.getLeader();
        if (leader != null) {
            LEADER_INDEX.put(leader.getUuid(), faction.getId());
        }
    }

    // 注销一个阵营（内存，不持久化）
    /**
     * Unregister a faction definition from memory only.
     *
     * @param factionId the faction id to remove
     * @return true if a faction was removed
     */
    public static boolean unregisterFaction(String factionId) {
        if (factionId == null) return false;
        Faction removed = FACTIONS.remove(factionId);
        if (removed == null) return false;
        dropIndexesOf(removed);
        return true;
    }

    // 注销一个阵营（持久化到 SavedData，成员与首领一并失效）
    /**
     * Unregister a faction and drop its entire member table. Membership cannot outlive the
     * faction — an entry naming a faction that no longer exists could never be resolved
     * or cleaned up again.
     *
     * @param factionId the faction id to remove
     * @param level     the server level for persistence
     * @return true if a faction was removed
     */
    public static boolean unregisterFaction(String factionId, Level level) {
        if (factionId == null) return false;
        ensureLoaded(level);
        Faction removed = FACTIONS.remove(factionId);
        if (removed == null) return false;

        dropIndexesOf(removed);
        FactionSavedData data = getSavedData(level);
        if (data != null) {
            data.removeFaction(factionId);
        }
        return true;
    }

    // 清除某阵营在派生索引与运行时缓存中的全部痕迹
    private static void dropIndexesOf(Faction faction) {
        String factionId = faction.getId();
        for (UUID uuid : faction.getMemberUuids()) {
            MEMBER_INDEX.remove(uuid);
        }
        FactionMember leader = faction.getLeader();
        if (leader != null) {
            LEADER_INDEX.remove(leader.getUuid());
        }
        synchronized (ENTITY_FACTION_CACHE) {
            ENTITY_FACTION_CACHE.entrySet().removeIf(e -> factionId.equals(e.getValue()));
        }
        LAST_PROPAGATION.remove(factionId);
        // 快速路径仅是提示集合，清空后由 getFactionId 的索引层回填，不会丢失归属
        FACTION_MEMBER_IDS.clear();
    }

    // 获取阵营定义
    /**
     * @param factionId the faction id
     * @return the faction, or null if not registered
     */
    public static Faction getFaction(String factionId) {
        if (factionId == null) return null;
        return FACTIONS.get(factionId);
    }

    // 获取全部阵营定义（只读）
    /**
     * @return read-only faction map
     */
    public static Map<String, Faction> getAllFactions() {
        return Collections.unmodifiableMap(FACTIONS);
    }

    // 检查阵营是否已注册
    /**
     * @param factionId the faction id
     * @return true if registered
     */
    public static boolean isFactionRegistered(String factionId) {
        return factionId != null && FACTIONS.containsKey(factionId);
    }

    // 获取阵营定义对象（用于条件查询）
    /**
     * Get the FactionDefinition for a registered faction, if any.
     * Factions created via the API or commands won't have a definition instance.
     *
     * @param factionId the faction id
     * @return the definition, or null if none
     */
    public static FactionDefinition getFactionDefinition(String factionId) {
        if (factionId == null) return null;
        return FACTION_DEFINITIONS.get(factionId);
    }

    // ==================== 成员实体类型池 ====================

    // 获取阵营的成员实体类型池（类型 → 权重）
    /**
     * Get the entity type pool declared by a faction's {@link FactionDefinition}.
     * Only factions registered through {@link RegisterFaction} can declare a pool.
     *
     * @param factionId the faction id
     * @return read-only entity type → weight map, empty if the faction declares no pool
     */
    public static Map<EntityType<?>, Integer> getMemberEntityTypes(String factionId) {
        FactionDefinition def = getFactionDefinition(factionId);
        if (def == null) return Collections.emptyMap();
        Map<EntityType<?>, Integer> pool = def.getMemberEntityTypes();
        return pool == null ? Collections.emptyMap() : Collections.unmodifiableMap(pool);
    }

    // 按权重从阵营成员类型池随机抽取一个实体类型
    /**
     * Randomly pick one entity type from a faction's member pool, weighted by the
     * values declared in {@link FactionDefinition#getMemberEntityTypes()}.
     *
     * @param factionId the faction to draw from
     * @param random    the random source to use
     * @return a weighted-random entity type, or null if the faction declares no usable pool
     */
    public static EntityType<?> rollMemberType(String factionId, RandomSource random) {
        if (factionId == null || random == null) return null;
        Map<EntityType<?>, Integer> pool = getMemberEntityTypes(factionId);
        if (pool.isEmpty()) {
            EcaLogger.info("[Faction] Faction '{}' declares no member entity types — cannot roll a type", factionId);
            return null;
        }

        int totalWeight = 0;
        for (Integer weight : pool.values()) {
            if (weight != null && weight > 0) {
                totalWeight += weight;
            }
        }
        if (totalWeight <= 0) {
            EcaLogger.info("[Faction] Faction '{}' member pool has no positive weights — cannot roll a type", factionId);
            return null;
        }

        int roll = random.nextInt(totalWeight);
        for (Map.Entry<EntityType<?>, Integer> entry : pool.entrySet()) {
            Integer weight = entry.getValue();
            if (weight == null || weight <= 0) continue;
            roll -= weight;
            if (roll < 0) return entry.getKey();
        }
        return null;
    }

    // ==================== 注解扫描 ====================

    // 扫描全部 mod 的 @RegisterFaction 注解，实例化并注册阵营
    /**
     * Scan all loaded mods for classes annotated with {@link RegisterFaction},
     * instantiate each {@link FactionDefinition}, and register the resulting faction.
     * Duplicate ids are logged and skipped (first registration wins).
     */
    public static void scanAndRegisterAll() {
        ModList.get().forEachModFile(modFile -> {
            for (IModInfo modInfo : modFile.getModInfos()) {
                modFile.getScanResult().getAnnotations().forEach(annotationData -> {
                    if (RegisterFaction.class.getName().equals(annotationData.annotationType().getClassName())) {
                        String className = annotationData.clazz().getClassName();
                        try {
                            Class<?> clazz = Class.forName(className, true,
                                    Thread.currentThread().getContextClassLoader());
                            registerFromDefinitionClass(clazz);
                        } catch (ClassNotFoundException e) {
                            EcaLogger.error("[Faction] Failed to load faction definition class {}: {}",
                                    className, e.getMessage());
                        }
                    }
                });
            }
        });
    }

    private static void registerFromDefinitionClass(Class<?> clazz) {
        if (!FactionDefinition.class.isAssignableFrom(clazz)) {
            EcaLogger.error("[Faction] Class {} is annotated with @RegisterFaction but does not extend FactionDefinition",
                    clazz.getName());
            return;
        }

        FactionDefinition def;
        try {
            def = (FactionDefinition) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            EcaLogger.error("[Faction] Failed to instantiate FactionDefinition {}: {}", clazz.getName(), e.getMessage());
            return;
        }

        String id = def.getId();
        if (id == null || id.isEmpty()) {
            EcaLogger.error("[Faction] FactionDefinition {} returned null or empty id — skipping", clazz.getName());
            return;
        }
        if (FACTIONS.containsKey(id)) {
            EcaLogger.error("[Faction] Duplicate faction id '{}' from {} — already registered, skipping",
                    id, clazz.getName());
            return;
        }

        FACTIONS.put(id, buildFactionFrom(def));
        FACTION_DEFINITIONS.put(id, def);
    }

    // 由定义对象创建 Faction 并填入预设关系
    private static Faction buildFactionFrom(FactionDefinition def) {
        Faction faction = new Faction(def.getId(), def.getDisplayName(), def.getColor(),
                def.getStaticDefaultRelation());
        for (String target : def.getHostileTo()) {
            faction.setRelation(target, FactionRelation.HOSTILE);
        }
        for (String target : def.getFriendlyTo()) {
            faction.setRelation(target, FactionRelation.FRIENDLY);
        }
        for (String target : def.getNeutralTo()) {
            faction.setRelation(target, FactionRelation.NEUTRAL);
        }
        return faction;
    }

    // ==================== 成员绑定 ====================

    // 实体加入阵营
    /**
     * Bind an entity to a faction, recording its type so the membership stays inspectable
     * while the entity is unloaded. Joining a faction the entity already belongs to simply
     * refreshes the recorded type.
     *
     * @param entity    the entity to bind
     * @param factionId the target faction id
     * @return true if the binding was created; false if the faction does not exist
     */
    public static boolean joinFaction(Entity entity, String factionId) {
        if (entity == null || factionId == null || factionId.isEmpty()) return false;
        ensureLoaded(entity.level());
        if (!bindMember(FactionMember.of(entity), factionId, entity.level())) {
            return false;
        }
        FACTION_MEMBER_IDS.add(entity.getId());
        ENTITY_FACTION_CACHE.put(entity, factionId);
        return true;
    }

    // 按 UUID 加入阵营（无需实体在线）
    /**
     * Bind an entity to a faction by UUID, without requiring the entity to be loaded.
     * This is the form to use when managing summons or offline members.
     *
     * @param uuid      the entity UUID
     * @param typeId    the entity type registry id, e.g. {@code "minecraft:zombie"}
     * @param isPlayer  whether the member is a player
     * @param factionId the target faction id
     * @param level     any server level, used to reach the overworld SavedData
     * @return true if the binding was created
     */
    public static boolean joinFaction(UUID uuid, String typeId, boolean isPlayer, String factionId, Level level) {
        if (uuid == null || factionId == null || factionId.isEmpty()) return false;
        ensureLoaded(level);
        return bindMember(new FactionMember(uuid, typeId, isPlayer), factionId, level);
    }

    // 绑定成员的唯一写入路径：迁出旧阵营 → 写入新阵营 → 更新索引 → 落库
    private static boolean bindMember(FactionMember member, String factionId, Level level) {
        if (member == null) return false;
        Faction faction = FACTIONS.get(factionId);
        if (faction == null) {
            EcaLogger.info("[Faction] Cannot join unregistered faction '{}' — membership requires an existing faction",
                    factionId);
            return false;
        }

        String previous = MEMBER_INDEX.get(member.getUuid());
        if (previous != null && !previous.equals(factionId)) {
            Faction previousFaction = FACTIONS.get(previous);
            if (previousFaction != null) {
                previousFaction.removeMember(member.getUuid());
                persist(previousFaction, level);
            }
        }

        faction.addMember(member);
        MEMBER_INDEX.put(member.getUuid(), factionId);
        persist(faction, level);
        return true;
    }

    // 实体退出阵营
    /**
     * Remove an entity from its current faction, if any.
     * <p>
     * This clears an explicit binding only. A tamed animal that merely inherits its owner's
     * faction has no binding of its own, so calling this on one has no effect — it keeps
     * following its owner. Bind the pet explicitly first if it must differ from its owner.
     *
     * @param entity the entity to unbind
     */
    public static void leaveFaction(Entity entity) {
        if (entity == null) return;
        FACTION_MEMBER_IDS.remove(entity.getId());
        ENTITY_FACTION_CACHE.remove(entity);
        unbindMember(entity.getUUID(), entity.level());
    }

    // 按 UUID 退出阵营（无需实体在线）
    /**
     * Remove a member from its faction by UUID, without requiring the entity to be loaded.
     *
     * @param uuid  the entity UUID
     * @param level any server level, used to reach the overworld SavedData
     * @return true if a binding was removed
     */
    public static boolean leaveFaction(UUID uuid, Level level) {
        ensureLoaded(level);
        return unbindMember(uuid, level);
    }

    private static boolean unbindMember(UUID uuid, Level level) {
        if (uuid == null) return false;
        String factionId = MEMBER_INDEX.remove(uuid);
        if (factionId == null) return false;

        Faction faction = FACTIONS.get(factionId);
        if (faction != null) {
            faction.removeMember(uuid);
            // 首领必然是成员，退营即卸任
            if (faction.isLeader(uuid)) {
                faction.setLeader(null);
                LEADER_INDEX.remove(uuid);
            }
            persist(faction, level);
        }
        return true;
    }

    // ==================== 归属查询 ====================

    // 获取实体所属阵营 ID（四层查询）
    /**
     * Get the faction id an entity belongs to.
     * Uses four-layer lookup: fast-path set → WeakHashMap cache → member index →
     * owner inheritance for tamed animals.
     *
     * @param entity the entity to query
     * @return faction id, or null if the entity belongs to no faction
     */
    public static String getFactionId(Entity entity) {
        if (entity == null) return null;
        ensureLoaded(entity.level());

        // 没有任何成员时立即返回，未使用阵营系统的存档不必为每次查询计算 UUID
        if (MEMBER_INDEX.isEmpty()) return null;

        // Layer 1: 快速路径命中 → 查 WeakHashMap
        if (FACTION_MEMBER_IDS.contains(entity.getId())) {
            String cached = ENTITY_FACTION_CACHE.get(entity);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        }

        // Layer 2: 反向索引（权威结果）
        String indexed = MEMBER_INDEX.get(entity.getUUID());
        if (indexed != null && !indexed.isEmpty()) {
            ENTITY_FACTION_CACHE.put(entity, indexed);
            FACTION_MEMBER_IDS.add(entity.getId());
            return indexed;
        }

        // Layer 3: 驯服动物继承主人阵营（不写持久化，主人换营时自动跟随）
        String inherited = resolveOwnerFaction(entity);
        if (inherited != null) {
            return inherited;
        }

        // 完全未命中 → 清理快速路径脏数据
        FACTION_MEMBER_IDS.remove(entity.getId());
        return null;
    }

    // 按 UUID 获取所属阵营（纯索引查询，不含宠物继承）
    /**
     * Get the faction id bound to a UUID. Pure index lookup — no entity required, and no
     * owner inheritance, since that needs a live entity to resolve.
     *
     * @param uuid the entity UUID
     * @return faction id, or null if this UUID has no explicit binding
     */
    public static String getFactionId(UUID uuid) {
        return uuid == null ? null : MEMBER_INDEX.get(uuid);
    }

    /*
     * 解析驯服动物继承自主人的阵营。
     *
     * 只读 owner UUID 直接查索引，不解析主人实体：这条在 canAttack 与发光扫描的
     * 热路径上，且主人离线或不在同一维度时仍需正确继承。
     *
     * 只解析一层——原版 TamableAnimal.getOwner() 本身只支持玩家主人。
     * 结果不写入缓存与索引：宠物没有自己的绑定，主人改变阵营后下次查询自动跟随。
     */
    private static String resolveOwnerFaction(Entity entity) {
        if (!(entity instanceof TamableAnimal pet)) return null;
        UUID ownerUuid = pet.getOwnerUUID();
        if (ownerUuid == null) return null;
        String ownerFaction = MEMBER_INDEX.get(ownerUuid);
        return (ownerFaction != null && !ownerFaction.isEmpty()) ? ownerFaction : null;
    }

    // 检查实体是否属于任意阵营
    /**
     * @param entity the entity to check
     * @return true if the entity has a faction
     */
    public static boolean hasFaction(Entity entity) {
        return getFactionId(entity) != null;
    }

    // 检查 UUID 是否为指定阵营的成员
    /**
     * @param uuid      the entity UUID
     * @param factionId the faction id
     * @return true if the UUID is bound to that faction
     */
    public static boolean isMember(UUID uuid, String factionId) {
        return uuid != null && factionId != null && factionId.equals(MEMBER_INDEX.get(uuid));
    }

    // 判断两个实体是否属于同一阵营
    /**
     * @param a first entity
     * @param b second entity
     * @return true if both belong to the same faction
     */
    public static boolean areSameFaction(Entity a, Entity b) {
        if (a == null || b == null) return false;
        String factionA = getFactionId(a);
        String factionB = getFactionId(b);
        return factionA != null && factionA.equals(factionB);
    }

    // ==================== 反向查询：阵营 → 成员 ====================

    // 获取阵营的全部成员记录（只读，无需实体在线）
    /**
     * @param factionId the faction id
     * @return read-only member records, empty if the faction is unknown
     */
    public static Collection<FactionMember> getMembers(String factionId) {
        Faction faction = getFaction(factionId);
        return faction == null ? Collections.emptyList() : faction.getMembers().values();
    }

    // 获取阵营的全部成员 UUID（只读，无需实体在线）
    /**
     * @param factionId the faction id
     * @return read-only member UUIDs, empty if the faction is unknown
     */
    public static Set<UUID> getMemberUuids(String factionId) {
        Faction faction = getFaction(factionId);
        return faction == null ? Collections.emptySet() : faction.getMemberUuids();
    }

    // 按实体类型筛选阵营成员（无需实体在线）
    /**
     * @param factionId the faction id
     * @param typeId    the entity type registry id, e.g. {@code "minecraft:zombie"}
     * @return matching member records
     */
    public static List<FactionMember> getMembersByType(String factionId, String typeId) {
        Faction faction = getFaction(factionId);
        return faction == null ? Collections.emptyList() : faction.getMembersByType(typeId);
    }

    // 获取阵营成员数量
    /**
     * @param factionId the faction id
     * @return member count, 0 if the faction is unknown
     */
    public static int getMemberCount(String factionId) {
        Faction faction = getFaction(factionId);
        return faction == null ? 0 : faction.getMemberCount();
    }

    // 将阵营成员解析为该维度中实际存在的实体
    /**
     * Resolve a faction's members to live entities in one level. Members in unloaded chunks
     * or other dimensions resolve to nothing and are omitted, so the result may be much
     * shorter than {@link #getMemberCount}.
     *
     * @param factionId the faction id
     * @param level     the level to resolve in
     * @return resolvable member entities
     */
    public static List<Entity> resolveMembers(String factionId, ServerLevel level) {
        List<Entity> result = new ArrayList<>();
        if (level == null) return result;
        for (UUID uuid : getMemberUuids(factionId)) {
            Entity entity = level.getEntity(uuid);
            if (entity != null && entity.isAlive()) {
                result.add(entity);
            }
        }
        return result;
    }

    // 查询实体所在阵营的全体成员（保留原签名，现走成员表而非全实体扫描）
    /**
     * Get the entities of a faction present in the given level.
     *
     * @param level     the level to resolve in
     * @param factionId the faction id to filter by
     * @return list of entities belonging to the faction (may be empty)
     */
    public static List<Entity> getFactionMembers(Level level, String factionId) {
        if (!(level instanceof ServerLevel serverLevel) || factionId == null || factionId.isEmpty()) {
            return Collections.emptyList();
        }
        ensureLoaded(level);
        return resolveMembers(factionId, serverLevel);
    }

    // 将指定阵营的全部成员移出
    /**
     * Remove every member from a faction. Operates on the member table directly, so members
     * in unloaded chunks are unbound too.
     *
     * @param factionId the faction to clear
     * @param level     the server level for persistence
     */
    public static void kickAll(String factionId, Level level) {
        if (factionId == null || level == null) return;
        ensureLoaded(level);
        Faction faction = FACTIONS.get(factionId);
        if (faction == null) return;

        for (UUID uuid : new ArrayList<>(faction.getMemberUuids())) {
            MEMBER_INDEX.remove(uuid);
            LEADER_INDEX.remove(uuid);
        }
        faction.clearMembers();
        faction.setLeader(null);
        synchronized (ENTITY_FACTION_CACHE) {
            ENTITY_FACTION_CACHE.entrySet().removeIf(e -> factionId.equals(e.getValue()));
        }
        FACTION_MEMBER_IDS.clear();
        persist(faction, level);
    }

    // ==================== 首领 ====================

    // 设置阵营首领（实体版，自动加入该阵营）
    /**
     * Set a faction's leader. The entity is added to the faction if it is not already a
     * member — a leader that is not part of its own faction would be a contradictory state.
     *
     * @param factionId the faction id
     * @param entity    the new leader
     * @param level     the server level for persistence
     * @return true if the leader was set
     */
    public static boolean setLeader(String factionId, Entity entity, Level level) {
        if (entity == null) return false;
        ensureLoaded(level);
        return setLeader(factionId, FactionMember.of(entity), level);
    }

    // 设置阵营首领（成员记录版，自动加入该阵营）
    /**
     * Set a faction's leader from a member record, without requiring the entity to be loaded.
     *
     * @param factionId the faction id
     * @param member    the new leader record
     * @param level     the server level for persistence
     * @return true if the leader was set
     */
    public static boolean setLeader(String factionId, FactionMember member, Level level) {
        if (factionId == null || member == null) return false;
        ensureLoaded(level);
        Faction faction = FACTIONS.get(factionId);
        if (faction == null) {
            EcaLogger.info("[Faction] Cannot set leader: faction '{}' not registered", factionId);
            return false;
        }

        FactionMember previous = faction.getLeader();
        if (previous != null) {
            LEADER_INDEX.remove(previous.getUuid());
        }

        // 首领必须是成员
        if (!faction.hasMember(member.getUuid())) {
            bindMember(member, factionId, level);
        }
        faction.setLeader(member);
        LEADER_INDEX.put(member.getUuid(), factionId);
        persist(faction, level);
        return true;
    }

    // 清除阵营首领（成员身份保留）
    /**
     * Clear a faction's leader. The former leader remains a member.
     *
     * @param factionId the faction id
     * @param level     the server level for persistence
     * @return true if a leader was cleared
     */
    public static boolean clearLeader(String factionId, Level level) {
        if (factionId == null) return false;
        ensureLoaded(level);
        Faction faction = FACTIONS.get(factionId);
        if (faction == null || !faction.hasLeader()) return false;

        LEADER_INDEX.remove(faction.getLeader().getUuid());
        faction.setLeader(null);
        LAST_PROPAGATION.remove(factionId);
        persist(faction, level);
        return true;
    }

    // 获取阵营首领记录
    /**
     * @param factionId the faction id
     * @return the leader record, or null
     */
    public static FactionMember getLeader(String factionId) {
        Faction faction = getFaction(factionId);
        return faction == null ? null : faction.getLeader();
    }

    // 获取阵营首领 UUID
    /**
     * @param factionId the faction id
     * @return the leader UUID, or null
     */
    public static UUID getLeaderUuid(String factionId) {
        FactionMember leader = getLeader(factionId);
        return leader == null ? null : leader.getUuid();
    }

    // 将阵营首领解析为实体（跨全部维度搜索）
    /**
     * Resolve a faction's leader to a live entity, searching every dimension. Player leaders
     * take the player-list fast path; other entities require scanning each level's lookup.
     *
     * @param factionId the faction id
     * @param server    the running server
     * @return the leader entity, or null if it is offline or unloaded
     */
    public static Entity resolveLeader(String factionId, MinecraftServer server) {
        FactionMember leader = getLeader(factionId);
        if (leader == null || server == null) return null;

        if (leader.isPlayer()) {
            return server.getPlayerList().getPlayer(leader.getUuid());
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(leader.getUuid());
            if (entity != null) return entity;
        }
        return null;
    }

    // 判断实体是否为任意阵营的首领
    /**
     * @param entity the entity to test
     * @return true if it leads some faction
     */
    public static boolean isLeader(Entity entity) {
        return entity != null && LEADER_INDEX.containsKey(entity.getUUID());
    }

    // 判断 UUID 是否为任意阵营的首领
    /**
     * @param uuid the entity UUID
     * @return true if it leads some faction
     */
    public static boolean isLeader(UUID uuid) {
        return uuid != null && LEADER_INDEX.containsKey(uuid);
    }

    // 反查某实体担任首领的阵营
    /**
     * @param uuid the leader's UUID
     * @return the faction id it leads, or null
     */
    public static String getFactionByLeader(UUID uuid) {
        return uuid == null ? null : LEADER_INDEX.get(uuid);
    }

    // ==================== 关系查询 ====================

    /*
     * 查询两个实体之间的有效关系（用于判断是否可攻击/设目标）。
     *
     * 优先级：
     *   1. 同阵营                              → SAME_FACTION
     *   2. FactionDefinition.getRelation() 条件 → 非 null 即返回
     *   3. Faction 静态 hostileTo/friendlyTo/neutralTo
     *   4. 对称回退（B 的 FactionDefinition + B 的 Faction 静态表）
     *   5. FactionDefinition.getDefaultRelation() 条件 → 非 null 即返回
     *   6. Faction 静态 defaultRelation
     */
    /**
     * Resolve the effective relation from entity {@code a}'s perspective toward entity {@code b}.
     *
     * @param a the source entity (attacker / targeter)
     * @param b the target entity
     * @return the effective relation
     */
    public static FactionRelation getEffectiveRelation(Entity a, Entity b) {
        if (a == null || b == null) return FactionRelation.HOSTILE;

        String factionA = getFactionId(a);
        String factionB = getFactionId(b);

        if (factionA != null && factionA.equals(factionB)) {
            return FactionRelation.SAME_FACTION;
        }

        if (factionA != null && factionB != null) {
            FactionDefinition defA = FACTION_DEFINITIONS.get(factionA);
            if (defA != null) {
                LivingEntity selfA = (a instanceof LivingEntity) ? (LivingEntity) a : null;
                FactionRelation dyn = defA.getRelation(selfA, b);
                if (dyn != null) return dyn;
            }

            Faction fA = FACTIONS.get(factionA);
            if (fA != null) {
                FactionRelation rel = fA.getRelation(factionB);
                if (rel != null) return rel;
            }

            FactionDefinition defB = FACTION_DEFINITIONS.get(factionB);
            if (defB != null) {
                LivingEntity selfB = (b instanceof LivingEntity) ? (LivingEntity) b : null;
                FactionRelation dyn = defB.getRelation(selfB, a);
                if (dyn != null) return dyn;
            }

            Faction fB = FACTIONS.get(factionB);
            if (fB != null) {
                FactionRelation rel = fB.getRelation(factionA);
                if (rel != null) return rel;
            }

            return FactionRelation.HOSTILE;
        }

        if (factionA != null) {
            FactionDefinition defA = FACTION_DEFINITIONS.get(factionA);
            if (defA != null) {
                LivingEntity selfA = (a instanceof LivingEntity) ? (LivingEntity) a : null;
                FactionRelation dyn = defA.getDefaultRelation(selfA, b);
                if (dyn != null) return dyn;
            }
            Faction fA = FACTIONS.get(factionA);
            if (fA != null) return fA.getDefaultRelation();
            return FactionRelation.HOSTILE;
        }

        if (factionB != null) {
            FactionDefinition defB = FACTION_DEFINITIONS.get(factionB);
            if (defB != null) {
                LivingEntity selfB = (b instanceof LivingEntity) ? (LivingEntity) b : null;
                FactionRelation dyn = defB.getDefaultRelation(selfB, a);
                if (dyn != null) return dyn;
            }
            Faction fB = FACTIONS.get(factionB);
            if (fB != null) return fB.getDefaultRelation();
            return FactionRelation.HOSTILE;
        }

        return FactionRelation.NEUTRAL;
    }

    // 判断 source 是否可以对 target 造成伤害或设为目标
    /**
     * @param source the attacker / targeter
     * @param target the target entity
     * @return false if faction rules prevent harm, true otherwise
     */
    public static boolean canHarm(Entity source, Entity target) {
        FactionRelation rel = getEffectiveRelation(source, target);
        return rel != FactionRelation.SAME_FACTION && rel != FactionRelation.FRIENDLY;
    }

    // ==================== 阵营间关系 ====================

    // 设置阵营 A 对阵营 B 的关系（内存，不持久化）
    /**
     * @param factionAId the source faction id
     * @param factionBId the target faction id
     * @param relation   the relation to set
     */
    public static void setFactionRelation(String factionAId, String factionBId, FactionRelation relation) {
        if (factionAId == null || factionBId == null || relation == null) return;
        Faction factionA = FACTIONS.get(factionAId);
        if (factionA == null) {
            EcaLogger.info("[Faction] Cannot set relation: faction '{}' not registered", factionAId);
            return;
        }
        factionA.setRelation(factionBId, relation);
    }

    // 设置阵营 A 对阵营 B 的关系（持久化到 SavedData）
    /**
     * @param factionAId the source faction id
     * @param factionBId the target faction id
     * @param relation   the relation to set
     * @param level      the server level for persistence
     */
    public static void setFactionRelation(String factionAId, String factionBId, FactionRelation relation,
                                          Level level) {
        if (factionAId == null || factionBId == null || relation == null) return;
        ensureLoaded(level);
        Faction factionA = FACTIONS.get(factionAId);
        if (factionA == null) {
            EcaLogger.info("[Faction] Cannot set relation: faction '{}' not registered", factionAId);
            return;
        }
        factionA.setRelation(factionBId, relation);
        persist(factionA, level);
    }

    // 查询阵营 A 对阵营 B 的关系（无覆盖返回 null）
    /**
     * @param factionAId the source faction id
     * @param factionBId the target faction id
     * @return the relation, or null if no explicit override
     */
    public static FactionRelation getFactionRelation(String factionAId, String factionBId) {
        if (factionAId == null || factionBId == null) return null;
        Faction factionA = FACTIONS.get(factionAId);
        if (factionA == null) return null;
        return factionA.getRelation(factionBId);
    }

    // ==================== 仇恨传导 ====================

    // 阵营求援：当阵营成员被非友方攻击时，附近同阵营生物将攻击者设为目标
    /**
     * Alert nearby same-faction mobs to target an attacker, used when an ordinary member is
     * hurt. Candidates come from an AABB around the victim rather than the whole member
     * table, since this runs on the damage path and only nearby allies should react.
     *
     * @param factionId the victim's faction id
     * @param attacker  the entity that attacked
     * @param victim    the entity that was attacked
     * @param level     the level to search for allies
     */
    public static void alertFactionMembers(String factionId, Entity attacker, Entity victim, Level level) {
        if (factionId == null || attacker == null || victim == null || level == null) return;
        if (!(attacker instanceof LivingEntity livingAttacker)) return;
        if (!EcaConfiguration.getFactionAlertEnabledSafely()) return;

        int range = EcaConfiguration.getFactionAlertRangeSafely();
        double rangeSq = (double) range * range;
        AABB area = victim.getBoundingBox().inflate(range);
        boolean immediate = EcaConfiguration.getFactionImmediateMemberAlertSafely();

        for (Mob mob : level.getEntitiesOfClass(Mob.class, area, m -> m != victim)) {
            if (!immediate && mob.getTarget() != null) continue;
            // AABB 是方形，仍需按半径裁剪为球形范围
            if (mob.distanceToSqr(victim) > rangeSq) continue;
            if (!factionId.equals(getFactionId(mob))) continue;
            if (!FactionUtil.canAttack(mob, livingAttacker)) continue;

            mob.setTarget(livingAttacker);
        }
    }

    // 首领仇恨传导：首领攻击他人或被攻击时，全阵营成员锁定该实体
    /**
     * Propagate a target to every member of the faction a leader commands. Unlike member
     * alerts this is not range-limited — the whole member table is walked, so distant
     * summons still answer. Members in other dimensions cannot be resolved and are skipped.
     * <p>
     * Whether an already-engaged member switches targets is decided by config, not per
     * faction. Repeat propagations of the same target within one tick are dropped so a
     * rapidly attacking leader does not walk the table every hit.
     *
     * @param leader the leader that attacked or was attacked
     * @param target the entity to propagate
     * @param level  the leader's level
     */
    public static void propagateLeaderTarget(Entity leader, LivingEntity target, ServerLevel level) {
        if (leader == null || target == null || level == null) return;
        if (!EcaConfiguration.getFactionLeaderProtectionEnabledSafely()) return;

        String factionId = LEADER_INDEX.get(leader.getUUID());
        if (factionId == null) return;
        Faction faction = FACTIONS.get(factionId);
        if (faction == null) return;

        long gameTime = level.getGameTime();
        Propagation last = LAST_PROPAGATION.get(factionId);
        if (last != null && last.gameTime == gameTime && target.getUUID().equals(last.target)) {
            return;
        }
        LAST_PROPAGATION.put(factionId, new Propagation(target.getUUID(), gameTime));

        boolean immediate = EcaConfiguration.getFactionImmediateLeaderProtectionSafely();
        UUID leaderUuid = leader.getUUID();

        for (UUID uuid : faction.getMemberUuids()) {
            if (uuid.equals(leaderUuid)) continue;
            Entity member = level.getEntity(uuid);
            if (!(member instanceof Mob mob) || !mob.isAlive()) continue;
            if (!immediate && mob.getTarget() != null) continue;
            // 传导不得让成员攻击自己人
            if (!FactionUtil.canAttack(mob, target)) continue;

            mob.setTarget(target);
        }
    }

    // ==================== 实体移除 ====================

    // 实体离开世界时清理运行时缓存，永久移除时一并清理归属与首领身份
    /**
     * Called when an entity leaves a level. Runtime caches are always dropped; membership
     * and leadership are removed only when the entity is gone for good, otherwise a
     * world's member table grows without bound as mobs die.
     * <p>
     * Chunk unloads and dimension changes keep the binding so the entity rejoins its
     * faction on reload. Players always keep theirs — a player UUID survives respawn.
     *
     * @param entity the entity leaving the level
     * @param reason why the entity was removed; null is treated as a temporary unload
     */
    public static void onEntityRemoved(Entity entity, Entity.RemovalReason reason) {
        if (entity == null) return;
        FACTION_MEMBER_IDS.remove(entity.getId());
        ENTITY_FACTION_CACHE.remove(entity);

        if (reason == null || !reason.shouldDestroy()) return;
        if (entity instanceof Player) return;

        unbindMember(entity.getUUID(), entity.level());
    }
}
