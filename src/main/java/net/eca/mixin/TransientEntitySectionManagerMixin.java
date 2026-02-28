package net.eca.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.eca.api.EcaAPI;

import net.eca.util.entity_extension.ForceLoadingManager;
import net.eca.util.spawn_ban.SpawnBanHook;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TransientEntitySectionManager.class)
public class TransientEntitySectionManagerMixin {

    @Shadow @Final
    LevelCallback callbacks;

    @Shadow @Final
    private LongSet tickingChunks;

    // 禁生成：阻止被禁实体添加到TransientEntitySectionManager
    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onAddEntity(EntityAccess entity, CallbackInfo ci) {
        if (entity instanceof Entity realEntity && realEntity.level() instanceof ServerLevel level) {
            if (SpawnBanHook.shouldBlockSpawn(level, realEntity)) {
                ci.cancel();
            }
        }
    }

    // 强加载实体：客户端侧对非ticking区段中的强加载实体手动启动tick，使位置插值正常推进
    @SuppressWarnings("unchecked")
    @Inject(method = "addEntity", at = @At("TAIL"))
    private void eca$startTickingForForceLoaded(EntityAccess entity, CallbackInfo ci) {
        if (!(entity instanceof Entity realEntity)) return;
        if (realEntity.isAlwaysTicking()) return;
        if (!ForceLoadingManager.isForceLoadedType(realEntity.getType())) return;

        // 仅在实体所在区块不在ticking集合中时手动启动（避免重复调用）
        long chunkKey = new ChunkPos(entity.blockPosition()).toLong();
        if (this.tickingChunks.contains(chunkKey)) return;

        this.callbacks.onTickingStart(entity);
    }

    @Mixin(TransientEntitySectionManager.Callback.class)
    public static class CallbackMixin {
        @Final
        @Shadow
        private EntityAccess entity;

        @Inject(method = "onRemove", at = @At("HEAD"), cancellable = true)
        private void eca$onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
            if (this.entity instanceof Entity realEntity) {
                // Allow dimension change operations even for invulnerable entities
                if (EcaAPI.isInvulnerable(realEntity) && reason != Entity.RemovalReason.CHANGED_DIMENSION) {
                    ci.cancel();
                }
            }
        }
    }
}
