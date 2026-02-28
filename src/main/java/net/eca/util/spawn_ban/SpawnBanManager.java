package net.eca.util.spawn_ban;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

// 禁生成管理器
public class SpawnBanManager {

    // 添加禁生成
    public static boolean addBan(ServerLevel level, EntityType<?> type, int timeInSeconds) {
        if (level == null || type == null || timeInSeconds <= 0) {
            return false;
        }

        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (typeId == null) {
            return false;
        }

        SpawnBanData data = SpawnBanData.get(level);
        data.addBan(typeId, timeInSeconds);
        return true;
    }

    // 检查是否被禁生成
    public static boolean isBanned(ServerLevel level, EntityType<?> type) {
        if (level == null || type == null) {
            return false;
        }

        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (typeId == null) {
            return false;
        }

        SpawnBanData data = SpawnBanData.get(level);
        return data.hasBan(typeId);
    }

    // 获取禁生成剩余时间
    public static int getRemainingTime(ServerLevel level, EntityType<?> type) {
        if (level == null || type == null) {
            return 0;
        }

        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (typeId == null) {
            return 0;
        }

        SpawnBanData data = SpawnBanData.get(level);
        return data.getTime(typeId);
    }

    // 清除禁生成
    public static boolean clearBan(ServerLevel level, EntityType<?> type) {
        if (level == null || type == null) {
            return false;
        }

        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (typeId == null) {
            return false;
        }

        SpawnBanData data = SpawnBanData.get(level);
        return data.removeBan(typeId);
    }

    // 获取所有禁生成
    public static Map<EntityType<?>, Integer> getAllBans(ServerLevel level) {
        if (level == null) {
            return Collections.emptyMap();
        }

        SpawnBanData data = SpawnBanData.get(level);
        Map<ResourceLocation, Integer> rawBans = data.getAllBans();

        Map<EntityType<?>, Integer> result = new HashMap<>();
        for (Map.Entry<ResourceLocation, Integer> entry : rawBans.entrySet()) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(entry.getKey());
            if (type != null) {
                result.put(type, entry.getValue());
            }
        }

        return Collections.unmodifiableMap(result);
    }

    // 清除所有禁生成
    public static void clearAllBans(ServerLevel level) {
        if (level == null) return;

        SpawnBanData data = SpawnBanData.get(level);
        data.clearAll();
    }

    // 更新禁生成倒计时（每秒调用）
    public static void tickBans(ServerLevel level) {
        if (level == null) return;

        SpawnBanData data = SpawnBanData.get(level);
        data.tick();
    }

    // 检查实体是否可以生成
    public static boolean canSpawn(ServerLevel level, EntityType<?> type) {
        return !isBanned(level, type);
    }
}
