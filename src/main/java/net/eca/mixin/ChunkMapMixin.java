package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.spawn_ban.SpawnBanHook;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkMap.class, priority = 1024)
public class ChunkMapMixin {

    // 禁生成：阻止被禁实体添加到ChunkMap
    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onAddEntity(Entity entity, CallbackInfo ci) {
        if (SpawnBanHook.shouldBlockSpawn(entity.level(), entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onRemoveEntity(Entity entity, CallbackInfo ci) {
        if (EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity)) {
            ci.cancel();
        }
    }
}
