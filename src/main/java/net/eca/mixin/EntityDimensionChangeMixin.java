package net.eca.mixin;

import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityDimensionChangeMixin {

    @Inject(method = "changeDimension*", at = @At("RETURN"))
    private void afterChangeDimension(ServerLevel destination, CallbackInfoReturnable<Entity> cir) {
        Entity oldEntity = (Entity) (Object) this;
        Entity newEntity = cir.getReturnValue();

        // 确保切换成功（返回值不为null，且是新实体）
        if (newEntity != null && newEntity != oldEntity) {
            // 获取旧维度
            if (oldEntity.level() instanceof ServerLevel oldLevel) {
                try {
                    // 检查并清除旧维度的残留记录
                    EcaLogger.info("[ECA] Entity {} changed dimension from {} to {}, cleaning up old records",
                        oldEntity, oldLevel.dimension().location(), destination.dimension().location());

                    EntityUtil.cleanupOldDimensionRecords(oldLevel, oldEntity);

                    // 从维度切换追踪集合中移除（使用旧实体UUID，因为新旧实体UUID相同）
                    EntityUtil.unmarkDimensionChanging(oldEntity);
                } catch (Exception e) {
                    EcaLogger.info("[ECA] Failed to cleanup after dimension change: {}", e.getMessage());
                }
            }
        }
    }
}
