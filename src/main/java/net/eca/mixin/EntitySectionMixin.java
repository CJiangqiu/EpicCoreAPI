package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.spawn_ban.SpawnBanHook;
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

    // 禁生成：阻止被禁实体添加到EntitySection
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void eca$onAdd(EntityAccess entity, CallbackInfo ci) {
        if (SpawnBanHook.shouldBlockSpawn(entity)) {
            ci.cancel();
        }
    }

    // 空间查询出口过滤，阻止残留实例参与命令、碰撞和 AI 查询
    @ModifyVariable(
        method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private AbortableIterationConsumer<EntityAccess> eca$filterSpatialQuery(
        AbortableIterationConsumer<EntityAccess> consumer
    ) {
        return entity -> SpawnBanHook.shouldBlockSpawn(entity)
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
        return entity -> SpawnBanHook.shouldBlockSpawn(entity)
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
