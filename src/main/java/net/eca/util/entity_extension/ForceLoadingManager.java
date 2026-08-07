package net.eca.util.entity_extension;

import net.eca.EcaMod;
import net.eca.api.EcaAPI;
import net.eca.util.EcaLogger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.core.SectionPos;
import net.minecraftforge.common.world.ForgeChunkManager;

import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理强加载实体的区块票据。
 * 当 EntityExtension.enableForceLoading() 返回 true 时，
 * 该实体类型的所有实例所在区块会被强制加载（EntityTicking 级别），确保 AI 正常运行。
 */
public final class ForceLoadingManager {

    private static final Map<UUID, TrackedChunk> TRACKED = new ConcurrentHashMap<>();
    private static final Set<UUID> FORCE_LOADED_MANUAL = ConcurrentHashMap.newKeySet();
    private static final Map<EntityType<?>, Boolean> FORCE_LOADED_TYPE_CACHE = new ConcurrentHashMap<>();

    /* 票据申请推迟到主线程任务队列执行，落地之前实体不在可见存储里、按 UUID 查不到。
       陈旧清理必须容忍这段窗口，否则会把尚未生效的条目当成"实体已消失"收走，
       而条目一没，延迟任务的守卫就再也匹配不上，强加载会永久失效。
       任务最迟 3 tick 派发，落地时区块是同步取的，20 tick 留了足够余量。 */
    private static final int STALE_GRACE_TICKS = 20;

    private static final ThreadLocal<Entity> CURRENT_RENDERING_ENTITY = new ThreadLocal<>();

    public static void setCurrentRenderingEntity(Entity entity) {
        CURRENT_RENDERING_ENTITY.set(entity);
    }

    public static Entity getCurrentRenderingEntity() {
        return CURRENT_RENDERING_ENTITY.get();
    }

    public static void clearCurrentRenderingEntity() {
        CURRENT_RENDERING_ENTITY.remove();
    }

    public static void onEntityJoin(LivingEntity entity, ServerLevel level) {
        if (!shouldForceLoad(entity)) {
            return;
        }

        UUID uuid = entity.getUUID();
        ChunkPos chunkPos = new ChunkPos(entity.blockPosition());

        if (!isValidChunkPos(chunkPos)) {
            return;
        }

        TRACKED.put(uuid, new TrackedChunk(level, chunkPos));
        requestForceLoad(level, uuid, chunkPos);
    }

    /* 申请区块票据。申请侧内部会同步阻塞取块，而本类的入口挂在实体加入世界的回调上，
       该回调有机会落在区块票据距离更新的集合迭代中，阻塞取块会重入该更新并破坏迭代。
       推迟到主线程任务队列顶层执行以避开该窗口。释放侧不阻塞，全部保持同步。
       TRACKED 是权威表，先落表再申请票据，守卫据此判断这张票是否仍是当前目标。 */
    private static void requestForceLoad(ServerLevel level, UUID uuid, ChunkPos pos) {
        level.getServer().execute(() -> {
            TrackedChunk tracked = TRACKED.get(uuid);
            // 延迟期间实体可能已离开或已移动到别的区块，此时这张票据不再是当前目标
            if (tracked == null || tracked.level != level
                    || tracked.chunkPos.x != pos.x || tracked.chunkPos.z != pos.z) {
                return;
            }
            ForgeChunkManager.forceChunk(level, EcaMod.MOD_ID, uuid, pos.x, pos.z, true, true);
        });
    }

    public static void onEntityTick(LivingEntity entity, ServerLevel level) {
        UUID uuid = entity.getUUID();
        TrackedChunk tracked = TRACKED.get(uuid);
        if (tracked == null) {
            return;
        }

        ChunkPos current = new ChunkPos(entity.blockPosition());
        if (current.x == tracked.chunkPos.x && current.z == tracked.chunkPos.z) {
            return;
        }

        // 坐标超出合法范围时，保留旧票据不更新，防止在极端坐标触发区块生成崩溃
        if (!isValidChunkPos(current)) {
            return;
        }

        // 实体移动到新区块，更新票据
        ForgeChunkManager.forceChunk(tracked.level, EcaMod.MOD_ID, uuid,
                tracked.chunkPos.x, tracked.chunkPos.z, false, true);
        tracked.level = level;
        tracked.chunkPos = current;
        requestForceLoad(level, uuid, current);
    }

    public static void onEntityLeave(LivingEntity entity, ServerLevel level) {
        UUID uuid = entity.getUUID();
        FORCE_LOADED_MANUAL.remove(uuid);
        TrackedChunk tracked = TRACKED.remove(uuid);
        if (tracked == null) {
            return;
        }

        ForgeChunkManager.forceChunk(tracked.level, EcaMod.MOD_ID, uuid,
                tracked.chunkPos.x, tracked.chunkPos.z, false, true);
    }

    /**
     * 注册 Forge 区块票据验证回调。
     * 服务器重启时 Forge 会调用此回调来验证持久化的票据是否仍然有效。
     */
    public static void registerValidationCallback() {
        ForgeChunkManager.setForcedChunkLoadingCallback(EcaMod.MOD_ID, (level, ticketHelper) -> {
            // 保留所有实体票据，让 Forge 恢复区块加载。
            // 实体加载后会通过 onEntityJoin 重新纳入 TRACKED 管理。
            // 如果实体已不存在，票据会在下次 onEntityLeave/清理时移除。
        });
    }

    public static void tickDimension(ServerLevel level) {
        if (TRACKED.isEmpty()) {
            return;
        }

        for (Map.Entry<UUID, TrackedChunk> entry : TRACKED.entrySet()) {
            UUID uuid = entry.getKey();
            TrackedChunk tracked = entry.getValue();
            if (tracked.level != level) {
                continue;
            }

            Entity entity = level.getEntity(uuid);
            if (entity == null) {
                // 票据尚未落地时实体本就查不到，攒够宽限才认定它真的消失了
                if (++tracked.missTicks < STALE_GRACE_TICKS) {
                    continue;
                }
                // UUID 对应实体不存在，移除陈旧票据
                if (TRACKED.remove(uuid, tracked)) {
                    ForgeChunkManager.forceChunk(tracked.level, EcaMod.MOD_ID, uuid,
                            tracked.chunkPos.x, tracked.chunkPos.z, false, true);
                }
                continue;
            }
            tracked.missTicks = 0;

            if (entity instanceof LivingEntity living) {
                onEntityTick(living, level);
            }
        }
    }

    public static boolean isForceLoaded(UUID entityUuid) {
        return TRACKED.containsKey(entityUuid);
    }

    // 防移除保护：无敌实体 或 强加载实体
    public static boolean shouldProtect(Entity entity) {
        return (entity instanceof LivingEntity && EcaAPI.isInvulnerable(entity))
                || isForceLoadedType(entity.getType())
                || FORCE_LOADED_MANUAL.contains(entity.getUUID());
    }

    // 强加载专属：超视距渲染、追踪距离扩大、区块票据、防despawn
    public static boolean shouldForceLoad(Entity entity) {
        return isForceLoadedType(entity.getType()) || FORCE_LOADED_MANUAL.contains(entity.getUUID());
    }

    public static void enableForceLoading(LivingEntity entity, ServerLevel level) {
        if (entity == null || level == null) return;
        UUID uuid = entity.getUUID();
        if (TRACKED.containsKey(uuid)) return;
        FORCE_LOADED_MANUAL.add(uuid);
        ChunkPos chunkPos = new ChunkPos(entity.blockPosition());
        if (!isValidChunkPos(chunkPos)) return;
        TRACKED.put(uuid, new TrackedChunk(level, chunkPos));
        requestForceLoad(level, uuid, chunkPos);
    }

    public static void disableForceLoading(LivingEntity entity, ServerLevel level) {
        if (entity == null) return;
        UUID uuid = entity.getUUID();
        FORCE_LOADED_MANUAL.remove(uuid);
        if (isForceLoadedType(entity.getType())) return;
        TrackedChunk tracked = TRACKED.remove(uuid);
        if (tracked == null) return;
        ForgeChunkManager.forceChunk(tracked.level, EcaMod.MOD_ID, uuid,
                tracked.chunkPos.x, tracked.chunkPos.z, false, true);
    }

    public static boolean isManualForceLoaded(UUID uuid) {
        return FORCE_LOADED_MANUAL.contains(uuid);
    }

    // 恢复被清除的强加载实体的 ChunkMap 追踪
    public static void recoverTrackedEntities(
            ServerLevel level,
            it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> entityMap,
            java.util.function.Consumer<Entity> addEntity) {
        if (TRACKED.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, TrackedChunk> entry : TRACKED.entrySet()) {
            TrackedChunk tracked = entry.getValue();
            if (tracked.level != level) {
                continue;
            }
            Entity entity = level.getEntity(entry.getKey());
            if (entity != null && shouldRecoverTrackedEntity(level, entityMap, entity)) {
                try {
                    addEntity.accept(entity);
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }

    private static boolean shouldRecoverTrackedEntity(ServerLevel level,
                                                      it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> entityMap,
                                                      Entity entity) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        if (entity.level() != level) {
            return false;
        }
        if (entityMap.containsKey(entity.getId())) {
            return false;
        }
        if (entity.levelCallback == EntityInLevelCallback.NULL) {
            return false;
        }

        PersistentEntitySectionManager<Entity> manager = level.entityManager;
        if (!manager.knownUuids.contains(entity.getUUID())) {
            return false;
        }

        long sectionKey = SectionPos.asLong(entity.blockPosition());
        EntitySection<Entity> section = manager.sectionStorage.sections.get(sectionKey);
        return section != null && section.getEntities().anyMatch(e -> e == entity);
    }

    public static boolean isForceLoadedType(EntityType<?> type) {
        if (type == null) {
            return false;
        }
        return FORCE_LOADED_TYPE_CACHE.computeIfAbsent(type, ForceLoadingManager::resolveForceLoadedType);
    }

    static void clearForceLoadedTypeCache(EntityType<?> type) {
        if (type != null) {
            FORCE_LOADED_TYPE_CACHE.remove(type);
        }
    }

    private static boolean resolveForceLoadedType(EntityType<?> type) {
        EntityExtension extension = EntityExtensionManager.getExtension(type);
        return extension != null && extension.enableForceLoading();
    }

    // 原版世界边界最大值 30000000 blocks = 1875000 chunks
    private static final int MAX_CHUNK_COORD = 1875000;

    private static boolean isValidChunkPos(ChunkPos pos) {
        return pos.x >= -MAX_CHUNK_COORD && pos.x <= MAX_CHUNK_COORD
                && pos.z >= -MAX_CHUNK_COORD && pos.z <= MAX_CHUNK_COORD;
    }

    private static class TrackedChunk {
        ServerLevel level;
        ChunkPos chunkPos;
        // 连续查不到实体的 tick 数，判定陈旧的依据，见 STALE_GRACE_TICKS
        int missTicks;

        TrackedChunk(ServerLevel level, ChunkPos chunkPos) {
            this.level = level;
            this.chunkPos = chunkPos;
        }
    }

    private ForceLoadingManager() {}
}
