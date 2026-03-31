package net.eca.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import com.mojang.datafixers.util.Either;
import net.eca.api.EcaAPI;
import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.eca.util.entity_extension.ForceLoadingManager;
import net.eca.util.reflect.UnsafeUtil;
import net.eca.util.spawn_ban.SpawnBanHook;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(value = ChunkMap.class, priority = 1024)
public abstract class ChunkMapMixin {

    private static final int ECA_SAFE_CHUNK_LIMIT = 1_874_999;

    @Shadow @Final ServerLevel level;
    @Shadow @Final public Int2ObjectMap<ChunkMap.TrackedEntity> entityMap;

    @Shadow protected abstract void addEntity(Entity entity);

    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onAddEntity(Entity entity, CallbackInfo ci) {
        if (SpawnBanHook.shouldBlockSpawn(entity.level(), entity)) {
            ci.cancel();
            return;
        }
        if (entityMap.containsKey(entity.getId())) {
            ci.cancel();
        }
    }

    @Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onRemoveEntity(Entity entity, CallbackInfo ci) {
        if (EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "scheduleChunkGeneration", at = @At("HEAD"), cancellable = true)
    private void eca$guardChunkGeneration(
        ChunkHolder holder,
        ChunkStatus status,
        CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> cir
    ) {
        ChunkPos pos = holder.getPos();
        if (Math.abs(pos.x) > ECA_SAFE_CHUNK_LIMIT || Math.abs(pos.z) > ECA_SAFE_CHUNK_LIMIT) {
            EcaLogger.warn(
                "[ChunkMapMixin] blocked out-of-range chunk generation: {},{} status={} level={}",
                pos.x,
                pos.z,
                status,
                this.level.dimension().location()
            );
            cir.setReturnValue(ChunkHolder.UNLOADED_CHUNK_FUTURE);
        }
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void eca$onTickStart(CallbackInfo ci) {
        UnsafeUtil.onChunkMapTickStart();
    }

    @Inject(method = "tick()V", at = @At("TAIL"))
    private void eca$onTickEnd(CallbackInfo ci) {
        UnsafeUtil.onChunkMapTickEnd();
        // 恢复受保护实体的追踪
        ForceLoadingManager.recoverTrackedEntities(this.level, this.entityMap, this::addEntity);
    }
}
