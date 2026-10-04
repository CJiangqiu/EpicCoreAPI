package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityRemovalQuarantine;
import net.eca.util.EntityUtil;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntitySection.class)
public class EntitySectionMixin {

    // 跨区段移动会先移出旧区段，不能因类型禁令阻止加入新区段。
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void eca$onAdd(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof Entity realEntity && EntityRemovalQuarantine.shouldBlockAdd(realEntity)) {
            ci.cancel();
        }
    }

    // 空间查询只隔离正在清除的实例，避免误伤同类型存活实体的交互。
    @ModifyVariable(
        method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private AbortableIterationConsumer<EntityAccess> eca$filterSpatialQuery(
        AbortableIterationConsumer<EntityAccess> consumer
    ) {
        return entity -> entity instanceof Entity realEntity && EntityRemovalQuarantine.isQueryHidden(realEntity)
            ? AbortableIterationConsumer.Continuation.CONTINUE
            : consumer.accept(entity);
    }

    @ModifyVariable(
        method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private AbortableIterationConsumer<EntityAccess> eca$filterTypedSpatialQuery(
        AbortableIterationConsumer<EntityAccess> consumer
    ) {
        return entity -> entity instanceof Entity realEntity && EntityRemovalQuarantine.isQueryHidden(realEntity)
            ? AbortableIterationConsumer.Continuation.CONTINUE
            : consumer.accept(entity);
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void eca$onRemove(EntityAccess entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof LivingEntity realEntity) {
            // Allow dimension change operations even for invulnerable entities
            if (EcaAPI.isInvulnerable(realEntity) && !EntityUtil.isChangingDimension(realEntity)) {
                cir.setReturnValue(false);
            }
        }
    }
}
