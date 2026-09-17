package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.spawn_ban.SpawnBanHook;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Mixin(EntityLookup.class)
public class EntityLookupMixin {

    // 禁生成：阻止被禁实体添加到EntityLookup
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void eca$onAdd(EntityAccess entity, CallbackInfo ci) {
        if (SpawnBanHook.shouldBlockSpawn(entity)) {
            ci.cancel();
        }
    }

    // 查询端同样隐藏被禁实体，避免短暂残留重新进入命令选择结果
    @ModifyVariable(method = "getEntities", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private AbortableIterationConsumer<EntityAccess> eca$filterTypedQuery(
        AbortableIterationConsumer<EntityAccess> consumer
    ) {
        return entity -> SpawnBanHook.shouldBlockSpawn(entity)
            ? AbortableIterationConsumer.Continuation.CONTINUE
            : consumer.accept(entity);
    }

    @Inject(method = "getAllEntities", at = @At("RETURN"), cancellable = true)
    private void eca$filterAllEntities(CallbackInfoReturnable<Iterable<EntityAccess>> cir) {
        List<EntityAccess> visible = new ArrayList<>();
        for (EntityAccess entity : cir.getReturnValue()) {
            if (!SpawnBanHook.shouldBlockSpawn(entity)) {
                visible.add(entity);
            }
        }
        cir.setReturnValue(Collections.unmodifiableList(visible));
    }

    @Inject(
        method = "getEntity(I)Lnet/minecraft/world/level/entity/EntityAccess;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void eca$filterEntityById(int entityId, CallbackInfoReturnable<EntityAccess> cir) {
        if (SpawnBanHook.shouldBlockSpawn(cir.getReturnValue())) {
            cir.setReturnValue(null);
        }
    }

    @Inject(
        method = "getEntity(Ljava/util/UUID;)Lnet/minecraft/world/level/entity/EntityAccess;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void eca$filterEntityByUuid(UUID entityUuid, CallbackInfoReturnable<EntityAccess> cir) {
        if (SpawnBanHook.shouldBlockSpawn(cir.getReturnValue())) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void eca$onRemove(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof LivingEntity realEntity) {
            // Allow dimension change operations even for invulnerable entities
            if (EcaAPI.isInvulnerable(realEntity) && !EntityUtil.isChangingDimension(realEntity)) {
                ci.cancel();
            }
        }
    }
}
