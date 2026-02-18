package net.eca.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.entity_extension.ForceLoadingManager;
import net.eca.util.spawn_ban.SpawnBanHook;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkMap.class, priority = 1024)
public class ChunkMapMixin {

    @Shadow
    @Final
    private Int2ObjectMap<ChunkMap.TrackedEntity> entityMap;

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

    // 强加载实体：确保发送实体状态更新（位置/旋转/速度等）
    // 原版 ChunkMap.tick() 只在 inEntityTickingRange 内调用 sendChanges，强加载实体可能不满足此条件
    @Inject(method = "tick", at = @At("TAIL"))
    private void eca$forceLoadedEntityTick(CallbackInfo ci) {
        for (ChunkMap.TrackedEntity tracked : this.entityMap.values()) {
            if (ForceLoadingManager.isForceLoadedType(tracked.entity.getType())) {
                tracked.serverEntity.sendChanges();
            }
        }
    }
}
