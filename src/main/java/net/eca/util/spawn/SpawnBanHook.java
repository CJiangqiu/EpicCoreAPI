package net.eca.util.spawn;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

// 禁生成钩子（供Mixin调用）
public class SpawnBanHook {

    // 检查实体是否应该被阻止添加
    public static boolean shouldBlockSpawn(Level level, Entity entity) {
        if (level == null || level.isClientSide || entity == null) {
            return false;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        EntityType<?> type = entity.getType();
        return SpawnBanManager.isBanned(serverLevel, type);
    }

}
