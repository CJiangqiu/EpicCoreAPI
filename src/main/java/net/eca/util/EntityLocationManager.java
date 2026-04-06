package net.eca.util;

import net.eca.api.EcaAPI;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

//实体位置锁定管理器
public class EntityLocationManager {

    //实体临时查不到多少 tick 后才真正删锁（防止 section 迁移/卸载瞬态误删）
    private static final int MAX_MISS_TICKS = 1200;

    //位置锁定数据
    private static class LocationLockData {
        Vec3 lockedPosition;
        int missCount;

        LocationLockData(Vec3 position) {
            this.lockedPosition = position;
            this.missCount = 0;
        }
    }

    //存储锁定信息（使用UUID防止ID冲突）
    private static final Map<UUID, LocationLockData> LOCKED_ENTITIES = new ConcurrentHashMap<>();

    //锁定实体当前位置
    public static void lockLocation(Entity entity) {
        lockLocation(entity, entity.position());
    }

    //锁定到指定位置
    public static void lockLocation(Entity entity, Vec3 position) {
        if (entity == null || position == null) return;
        LOCKED_ENTITIES.put(entity.getUUID(), new LocationLockData(position));
    }

    //解除锁定
    public static void unlockLocation(Entity entity) {
        if (entity == null) return;
        LOCKED_ENTITIES.remove(entity.getUUID());
    }

    //检查是否锁定
    public static boolean isLocationLocked(Entity entity) {
        if (entity == null) return false;
        return LOCKED_ENTITIES.containsKey(entity.getUUID());
    }

    //获取锁定的位置
    public static Vec3 getLockedPosition(Entity entity) {
        if (entity == null) return null;
        LocationLockData data = LOCKED_ENTITIES.get(entity.getUUID());
        return data != null ? data.lockedPosition : null;
    }

    //检查所有锁定的实体（在ServerLevel tick事件中调用）
    public static void checkLockedEntities(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (server == null) return;

        LOCKED_ENTITIES.entrySet().removeIf(entry -> {
            UUID uuid = entry.getKey();
            LocationLockData data = entry.getValue();

            //从所有维度查找实体（防止维度切换时丢失锁定）
            Entity entity = findEntityInAllLevels(server, uuid);
            if (entity == null) {
                //实体暂时查不到（可能因为字段脏写导致 section 迁移瞬态卸载）
                //累计 miss，超过阈值才真正删锁，防止被攻击者诱导失锁
                data.missCount++;
                return data.missCount > MAX_MISS_TICKS;
            }

            //查到就重置 miss 计数
            data.missCount = 0;

            //维度切换时更新锁定位置（维度切换放行后门）
            if (EntityUtil.isChangingDimension(entity)) {
                data.lockedPosition = entity.position();
                return false;
            }

            //只检查当前维度的实体（避免跨维度操作）
            if (entity.level() != level) {
                return false;  //实体在其他维度，跳过检查
            }

            Vec3 currentPos = entity.position();
            Vec3 lockedPos = data.lockedPosition;
            double distance = currentPos.distanceTo(lockedPos);

            //检测位置偏离，用 ECA 传送 API 强制拉回
            //相比 setPos：同步 xOld/yOld/zOld、正确更新 bb、按 seenBy 发包（远距离也能同步客户端）
            if (distance > 0.001) {
                EcaAPI.teleport(entity, lockedPos.x, lockedPos.y, lockedPos.z);
            }

            return false;
        });
    }

    //在所有维度中查找实体
    private static Entity findEntityInAllLevels(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    //清理所有锁定
    public static void clearAll() {
        LOCKED_ENTITIES.clear();
    }

    //获取锁定实体数量
    public static int getLockedEntityCount() {
        return LOCKED_ENTITIES.size();
    }
}
