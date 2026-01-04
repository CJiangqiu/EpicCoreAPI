package net.eca.mixin;

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
            EntityUtil.unmarkDimensionChanging(oldEntity);
        }
    }
}
