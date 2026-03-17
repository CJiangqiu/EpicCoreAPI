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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理强加载实体的区块票据。
 * 当 EntityExtension.enableForceLoading() 返回 true 时，
 * 该实体类型的所有实例所在区块会被强制加载（EntityTicking 级别），确保 AI 正常运行。
 */
public final class ForceLoadingManager {

    private static final Map<UUID, TrackedChunk> TRACKED = new ConcurrentHashMap<>();

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

        ForgeChunkManager.forceChunk(level, EcaMod.MOD_ID, uuid, chunkPos.x, chunkPos.z, true, true);
        TRACKED.put(uuid, new TrackedChunk(level, chunkPos));
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
        ForgeChunkManager.forceChunk(level, EcaMod.MOD_ID, uuid,
                current.x, current.z, true, true);
        tracked.level = level;
        tracked.chunkPos = current;
    }

    public static void onEntityLeave(LivingEntity entity, ServerLevel level) {
        UUID uuid = entity.getUUID();
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
                // UUID 对应实体不存在，移除陈旧票据
                if (TRACKED.remove(uuid, tracked)) {
                    ForgeChunkManager.forceChunk(tracked.level, EcaMod.MOD_ID, uuid,
                            tracked.chunkPos.x, tracked.chunkPos.z, false, true);
                }
                continue;
            }

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
        return EcaAPI.isInvulnerable(entity) || isForceLoadedType(entity.getType());
    }

    // 强加载专属：超视距渲染、追踪距离扩大、区块票据、防despawn
    public static boolean shouldForceLoad(Entity entity) {
        return isForceLoadedType(entity.getType());
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

        TrackedChunk(ServerLevel level, ChunkPos chunkPos) {
            this.level = level;
            this.chunkPos = chunkPos;
        }
    }

    private ForceLoadingManager() {}
}
