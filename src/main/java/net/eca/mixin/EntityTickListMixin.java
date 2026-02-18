package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.spawn_ban.SpawnBanHook;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityTickList.class)
public class EntityTickListMixin {

    // 禁生成：阻止被禁实体添加到EntityTickList
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void eca$onAdd(Entity entity, CallbackInfo ci) {
        if (SpawnBanHook.shouldBlockSpawn(entity.level(), entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void eca$onRemove(Entity entity, CallbackInfo ci) {
        // Allow dimension change operations even for invulnerable entities
        if (EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity)) {
            ci.cancel();
        }
    }
}
