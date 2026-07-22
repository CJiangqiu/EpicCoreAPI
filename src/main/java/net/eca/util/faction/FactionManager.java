package net.eca.util.faction;

import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 阵营管理器 — 实体-阵营绑定 + 阵营定义 + 关系查询
 *
 * 三层查询：
 *   1. FACTION_MEMBER_IDS   — 快速路径，绝大多数实体不在此集合内，直接返回 null
 *   2. ENTITY_FACTION_CACHE  — WeakHashMap<Entity, String>，实体存活期间缓存，GC 时自动清除
 *   3. PERSISTENT_MEMBERS    — UUID→factionId，由 FactionSavedData 持久化，重启后恢复
 *
 * 持久化：
 *   - 阵营定义 + 实体绑定统一存储于 FactionSavedData（主世界 DataStorage）
 *   - 不依赖实体 NBT / SynchedEntityData
 */
public class FactionManager {

    // ==================== 三层缓存 ====================

    // 快速路径：按 entityId 记录当前属于任意阵营的实体
    private static final Set<Integer> FACTION_MEMBER_IDS = ConcurrentHashMap.newKeySet();

    // 实体 → 阵营 ID 缓存（弱引用 key，实体被 GC 后自动清除，无内存泄漏）
    private static final Map<Entity, String> ENTITY_FACTION_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    // 阵营定义注册表
    private static final Map<String, Faction> FACTIONS = new ConcurrentHashMap<>();

    // UUID → factionId 持久化映射（由 SavedData 加载和写入）
    private static final Map<UUID, String> PERSISTENT_MEMBERS = new ConcurrentHashMap<>();

    // 是否已从 SavedData 加载
    private static volatile boolean loaded = false;

    // ==================== SavedData 辅助 ====================

    private static FactionSavedData getSavedData(Entity entity) {
        if (entity == null || entity.level() == null) return null;
        if (!(entity.level() instanceof ServerLevel serverLevel)) return null;
        return FactionSavedData.get(serverLevel);
    }

    private static FactionSavedData getSavedData(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return null;
        return FactionSavedData.get(serverLevel);
    }

    // 尝试从世界数据加载（惰性、幂等）
    /**
     * Attempt to load faction definitions and member mappings from SavedData.
     * Idempotent — subsequent calls are no-ops after the first successful load.
     *
     * @param level any server level (uses the overworld's DataStorage)
     */
    public static void ensureLoaded(Level level) {
        if (loaded) return;
        FactionSavedData data = getSavedData(level);
        if (data == null) return;
        synchronized (FactionManager.class) {
            if (loaded) return;
            data.loadFactionsInto(FactionManager.instance());
            data.loadMembersInto(FactionManager.instance());
            loaded = true;
        }
    }

    // 供 FactionSavedData 回调（避免循环依赖）
    static FactionManager instance() {
        return new FactionManager(); // dummy, static methods only
    }

    void putLoadedFaction(Faction faction) {
        FACTIONS.put(faction.getId(), faction);
    }

    void putLoadedMember(UUID uuid, String factionId) {
        PERSISTENT_MEMBERS.put(uuid, factionId);
        // 不加入 FACTION_MEMBER_IDS — entityId 在实体加载缓存时才填充
    }

    // ==================== 阵营定义管理 ====================

    // 注册一个阵营（内存，不持久化；供 loadFactionsInto 和纯内存场景使用）
    /**
     * Register a faction in memory only (no SavedData persistence).
     * For persisted registration, use {@link #registerFaction(Faction, Level)}.
     *
     * @param faction the faction to register
     */
    public static void registerFaction(Faction faction) {
        if (faction == null || faction.getId() == null || faction.getId().isEmpty()) return;
        FACTIONS.put(faction.getId(), faction);
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
        FactionSavedData data = getSavedData(level);
        if (data != null) {
            data.putFaction(faction);
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
        return FACTIONS.remove(factionId) != null;
    }

    // 注销一个阵营（持久化到 SavedData）
    /**
     * Unregister a faction definition. Entities that belong to this faction are NOT
     * automatically removed — call {@link #kickAll(String, Level)} first if needed.
     *
     * @param factionId the faction id to remove
     * @param level     the server level for persistence
     * @return true if a faction was removed
     */
    public static boolean unregisterFaction(String factionId, Level level) {
        if (factionId == null) return false;
        ensureLoaded(level);
        boolean removed = FACTIONS.remove(factionId) != null;
        if (removed) {
            FactionSavedData data = getSavedData(level);
            if (data != null) {
                data.removeFaction(factionId);
            }
        }
        return removed;
    }

    // 获取阵营定义
    /**
     * Get a faction definition by its id.
     *
     * @param factionId the faction id
     * @return the faction, or null if not registered
     */
    public static Faction getFaction(String factionId) {
        if (factionId == null) return null;
        return FACTIONS.get(factionId);
    }

    // 获取全部阵营定义（只读）
    /**
     * Get an unmodifiable view of all registered factions.
     *
     * @return read-only faction map
     */
    public static Map<String, Faction> getAllFactions() {
        return Collections.unmodifiableMap(FACTIONS);
    }

    // 检查阵营是否已注册
    /**
     * Check whether a faction id is registered.
     *
     * @param factionId the faction id
     * @return true if registered
     */
    public static boolean isFactionRegistered(String factionId) {
        return factionId != null && FACTIONS.containsKey(factionId);
    }

    // ==================== 实体-阵营绑定 ====================

    // 实体加入阵营
    /**
     * Bind an entity to a faction. If the faction is not registered, a warning is logged
     * and the entity is still tagged, but faction-level relation queries will fall back
     * to default behavior.
     *
     * @param entity    the entity to bind
     * @param factionId the target faction id
     */
    public static void joinFaction(Entity entity, String factionId) {
        if (entity == null || factionId == null || factionId.isEmpty()) return;
        ensureLoaded(entity.level());
        if (!FACTIONS.containsKey(factionId)) {
            EcaLogger.info("[Faction] Entity {} joined unregistered faction '{}' — relations will use defaults",
                    entity.getUUID(), factionId);
        }
        FACTION_MEMBER_IDS.add(entity.getId());
        ENTITY_FACTION_CACHE.put(entity, factionId);
        PERSISTENT_MEMBERS.put(entity.getUUID(), factionId);
        FactionSavedData data = getSavedData(entity);
        if (data != null) {
            data.addMember(entity.getUUID(), factionId);
        }
    }

    // 实体退出阵营
    /**
     * Remove an entity from its current faction, if any.
     *
     * @param entity the entity to unbind
     */
    public static void leaveFaction(Entity entity) {
        if (entity == null) return;
        FACTION_MEMBER_IDS.remove(entity.getId());
        ENTITY_FACTION_CACHE.remove(entity);
        PERSISTENT_MEMBERS.remove(entity.getUUID());
        FactionSavedData data = getSavedData(entity);
        if (data != null) {
            data.removeMember(entity.getUUID());
        }
    }

    // 获取实体所属阵营 ID（三层查询）
    /**
     * Get the faction id an entity belongs to.
     * Uses three-layer lookup: fast-path set → WeakHashMap cache → persistent UUID map.
     * The fast-path set is consulted first as an optimization, but a miss does NOT
     * short-circuit — the persistent SavedData layer is always checked as authoritative
     * source, and a hit there will populate both the cache and the fast-path set.
     *
     * @param entity the entity to query
     * @return faction id, or null if the entity belongs to no faction
     */
    public static String getFactionId(Entity entity) {
        if (entity == null) return null;

        // Layer 1: 快速路径命中 → 查 WeakHashMap
        if (FACTION_MEMBER_IDS.contains(entity.getId())) {
            String cached = ENTITY_FACTION_CACHE.get(entity);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        }

        // Layer 2: UUID 持久化映射（权威数据源，重启后由 SavedData 加载）
        ensureLoaded(entity.level());
        String persistent = PERSISTENT_MEMBERS.get(entity.getUUID());
        if (persistent != null && !persistent.isEmpty()) {
            // 回填运行时缓存和快速路径（处理重启后首次查询的冷启动）
            ENTITY_FACTION_CACHE.put(entity, persistent);
            FACTION_MEMBER_IDS.add(entity.getId());
            return persistent;
        }

        // 完全未命中 → 清理快速路径脏数据
        FACTION_MEMBER_IDS.remove(entity.getId());
        return null;
    }

    // 检查实体是否属于任意阵营
    /**
     * Check whether an entity belongs to any faction.
     *
     * @param entity the entity to check
     * @return true if the entity has a faction
     */
    public static boolean hasFaction(Entity entity) {
        return getFactionId(entity) != null;
    }

    // 判断两个实体是否属于同一阵营
    /**
     * Check whether two entities belong to the same faction.
     * Both entities must have a faction; if either has none, returns false.
     *
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

    // 查询实体所在阵营的全体成员
    /**
     * Get all entities in the given level that belong to the specified faction.
     * This is an O(n) scan over {@link EntityUtil#getEntities(Level)} — avoid calling
     * every tick. Use {@link #areSameFaction} or {@link #getFactionId} for per-entity checks.
     *
     * @param level     the level to scan
     * @param factionId the faction id to filter by
     * @return list of entities belonging to the faction (may be empty)
     */
    public static List<Entity> getFactionMembers(Level level, String factionId) {
        if (level == null || factionId == null || factionId.isEmpty()) {
            return Collections.emptyList();
        }
        ensureLoaded(level);
        List<Entity> members = new ArrayList<>();
        for (Entity entity : EntityUtil.getEntities(level)) {
            if (factionId.equals(getFactionId(entity))) {
                members.add(entity);
            }
        }
        return members;
    }

    // 将指定阵营的全部实体移出
    /**
     * Remove all entities in the given level from the specified faction.
     *
     * @param factionId the faction to kick members from
     * @param level     the level to scan
     */
    public static void kickAll(String factionId, Level level) {
        if (factionId == null || level == null) return;
        for (Entity entity : getFactionMembers(level, factionId)) {
            leaveFaction(entity);
        }
    }

    // 实体被移除时清理持久化数据（由 Mixin 在实体移除时调用）
    /**
     * Called when an entity is permanently removed from the world.
     * Cleans up the faction binding from persistent storage.
     *
     * @param entity the entity being removed
     */
    public static void onEntityRemoved(Entity entity) {
        if (entity == null) return;
        FACTION_MEMBER_IDS.remove(entity.getId());
        ENTITY_FACTION_CACHE.remove(entity);
        // 保留 PERSISTENT_MEMBERS 中的条目，因为实体可能在死后复生
        // 仅在明确 leaveFaction 时清除持久化记录
    }

    // ==================== 关系查询 ====================

    /*
     * 查询两个实体之间的有效关系（用于判断是否可攻击/设目标）。
     *
     * 优先级：
     *   1. 同阵营                     → SAME_FACTION
     *   2. 双方都有阵营 + A 对 B 有覆盖 → 返回覆盖值
     *   3. 双方都有阵营 + B 对 A 有覆盖 → 返回覆盖值（对称回退）
     *   4. A 有阵营、B 无阵营           → A.defaultRelation
     *   5. A 无阵营、B 有阵营           → 逆向 B.defaultRelation
     *   6. 双方都无阵营                 → NEUTRAL
     */
    /**
     * Resolve the effective relation from entity {@code a}'s perspective toward entity {@code b}.
     * The result determines whether {@code a} can target or damage {@code b}.
     *
     * @param a the source entity (attacker / targeter)
     * @param b the target entity
     * @return the effective relation
     */
    public static FactionRelation getEffectiveRelation(Entity a, Entity b) {
        if (a == null || b == null) return FactionRelation.HOSTILE;

        String factionA = getFactionId(a);
        String factionB = getFactionId(b);

        // 同阵营
        if (factionA != null && factionA.equals(factionB)) {
            return FactionRelation.SAME_FACTION;
        }

        // 双方都有阵营 → 查关系覆盖
        if (factionA != null && factionB != null) {
            Faction fA = FACTIONS.get(factionA);
            if (fA != null) {
                FactionRelation rel = fA.getRelation(factionB);
                if (rel != null) return rel;
            }
            // 对称回退：B 对 A 的覆盖
            Faction fB = FACTIONS.get(factionB);
            if (fB != null) {
                FactionRelation rel = fB.getRelation(factionA);
                if (rel != null) return rel;
            }
            // 双方都有阵营但无覆盖 → 默认敌对
            return FactionRelation.HOSTILE;
        }

        // A 有阵营，B 无阵营 → A 的 defaultRelation
        if (factionA != null) {
            Faction fA = FACTIONS.get(factionA);
            if (fA != null) return fA.getDefaultRelation();
            return FactionRelation.HOSTILE;
        }

        // A 无阵营，B 有阵营 → 逆向 B 的 defaultRelation
        if (factionB != null) {
            Faction fB = FACTIONS.get(factionB);
            if (fB != null) return fB.getDefaultRelation();
            return FactionRelation.HOSTILE;
        }

        // 双方都无阵营
        return FactionRelation.NEUTRAL;
    }

    // 判断 source 是否可以对 target 造成伤害或设为目标
    /**
     * Shortcut: returns true if {@code source} is allowed to harm or target {@code target}
     * under the current faction rules.
     *
     * @param source the attacker / targeter
     * @param target the target entity
     * @return false if faction rules prevent harm, true otherwise
     */
    public static boolean canHarm(Entity source, Entity target) {
        FactionRelation rel = getEffectiveRelation(source, target);
        return rel != FactionRelation.SAME_FACTION && rel != FactionRelation.FRIENDLY;
    }

    // ==================== 阵营间关系快捷方法 ====================

    // 设置阵营 A 对阵营 B 的关系（内存，不持久化）
    /**
     * Set the relation that faction A has toward faction B (memory only).
     *
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
     * Set the relation that faction A has toward faction B, persisting to SavedData.
     *
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
        FactionSavedData data = getSavedData(level);
        if (data != null) {
            data.putRelation(factionAId, factionA);
        }
    }

    // 查询阵营 A 对阵营 B 的关系（无覆盖返回 null）
    /**
     * Get the explicit relation from faction A to faction B.
     *
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
}
