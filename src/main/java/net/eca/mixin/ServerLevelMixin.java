package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.EcaLogger;
import net.eca.util.InvulnerableEntityManager;
import net.eca.util.spawn_ban.SpawnBanHook;
import net.eca.util.spawn_ban.SpawnBanManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {

    @Unique
    private long eca$lastBanTickTime = 0;

    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onAddEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity == null) {
            EcaLogger.info("Blocked null entity in ServerLevel#addEntity");
            cir.setReturnValue(false);
            return;
        }
        ServerLevel self = (ServerLevel) (Object) this;
        if (SpawnBanHook.shouldBlockSpawn(self, entity)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity == null) {
            EcaLogger.info("Blocked null entity in ServerLevel#addFreshEntity");
            cir.setReturnValue(false);
            return;
        }
        ServerLevel self = (ServerLevel) (Object) this;
        if (SpawnBanHook.shouldBlockSpawn(self, entity)) {
            cir.setReturnValue(false);
        }
    }

    // 防止实体 tick 阶段因空实体崩溃
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void eca$onTickNonPassenger(Entity entity, CallbackInfo ci) {
        if (entity == null) {
            EcaLogger.info("Skipped null entity in ServerLevel#tickNonPassenger");
            ci.cancel();
        }
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("TAIL"))
    private void eca$onTick(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        long currentTime = self.getGameTime();

        for (UUID uuid : InvulnerableEntityManager.getAllInvulnerableUUIDs()) {
            EcaAPI.reviveEntity(self, uuid);
        }

        // 每 20 tick (1秒) 更新一次禁令
        if (currentTime - eca$lastBanTickTime >= 20) {
            eca$lastBanTickTime = currentTime;
            SpawnBanManager.tickBans(self);
        }
    }

    @Unique
    private ServerPlayer eca$oldDuplicate = null;

    @Inject(method = "addPlayer", at = @At("HEAD"))
    private void eca$captureOldDuplicate(ServerPlayer newPlayer, CallbackInfo ci) {
        ServerLevel self = (ServerLevel)(Object)this;
        Entity entity = self.getEntities().get(newPlayer.getUUID());
        if (entity instanceof ServerPlayer sp && entity != newPlayer) {
            eca$oldDuplicate = sp;
        } else {
            eca$oldDuplicate = null;
        }
    }

    @Inject(method = "addPlayer", at = @At("TAIL"))
    private void eca$cleanupOldDuplicate(ServerPlayer newPlayer, CallbackInfo ci) {
        if (eca$oldDuplicate == null) return;
        ServerLevel self = (ServerLevel)(Object)this;
        Entity current = self.getEntities().get(eca$oldDuplicate.getUUID());
        if (current == eca$oldDuplicate) {
            EntityUtil.removeEntity(eca$oldDuplicate, Entity.RemovalReason.CHANGED_DIMENSION);
        }
        eca$oldDuplicate = null;
    }
}
