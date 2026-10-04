package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EcaLogger;
import net.eca.util.EntityRemovalQuarantine;
import net.eca.util.EntityUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityTickList.class)
public class EntityTickListMixin {

    // 恢复区块活动时仍需加入存活实体，只拦截清除中的实例与墓碑。
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void eca$onAdd(Entity entity, CallbackInfo ci) {
        if (entity == null) {
            EcaLogger.info("Blocked null entity in EntityTickList#add");
            ci.cancel();
            return;
        }
        if (EntityRemovalQuarantine.shouldBlockAdd(entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void eca$onRemove(Entity entity, CallbackInfo ci) {
        // Allow dimension change operations even for invulnerable entities
        if (entity instanceof LivingEntity && EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity)) {
            ci.cancel();
        }
    }
}
