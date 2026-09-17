package net.eca.util.spawn_ban;

import net.eca.util.reflect.UnsafeUtil;
import net.eca.util.selector.EcaEntitySelector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import java.util.List;

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

    // 为底层容器入口解析实体所属的服务端世界
    public static boolean shouldBlockSpawn(Object candidate) {
        if (!(candidate instanceof Entity entity)) return false;
        return shouldBlockSpawn(entity.level, entity);
    }

    // 从全部原始容器收集，避免只残留在单一容器中的实例逃过清扫
    public static void enforceBans(ServerLevel level) {
        if (level == null) return;
        List<Entity> blocked = EcaEntitySelector.getEntities(
            level,
            entity -> shouldBlockSpawn(level, entity)
        );
        for (Entity entity : blocked) {
            UnsafeUtil.unsafeRemove(level, entity, Entity.RemovalReason.DISCARDED);
        }
    }

}
