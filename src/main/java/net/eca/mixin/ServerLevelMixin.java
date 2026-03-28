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
            Entity entity = self.getEntities().get(uuid);
            if (entity == null || EntityUtil.isChangingDimension(entity)) {
                continue;
            }
            EcaAPI.revive(self, uuid);
        }

        // 每 20 tick (1秒) 更新一次禁令
        if (currentTime - eca$lastBanTickTime >= 20) {
            eca$lastBanTickTime = currentTime;
            SpawnBanManager.tickBans(self);
        }
    }

    @Unique
    private ServerPlayer eca$oldDuplicate = null;
    @Unique
    private boolean eca$duplicateWasInvulnerable = false;

    @Inject(method = "addPlayer", at = @At("HEAD"))
    private void eca$captureOldDuplicate(ServerPlayer newPlayer, CallbackInfo ci) {
        ServerLevel self = (ServerLevel)(Object)this;
        Entity entity = self.getEntities().get(newPlayer.getUUID());
        if (entity instanceof ServerPlayer sp && entity != newPlayer) {
            eca$oldDuplicate = sp;
            eca$duplicateWasInvulnerable = EcaAPI.isInvulnerable(sp);

            // 针对无敌玩家被第三方模组强制 respawn 的场景：
            // 在新实例加入前，先移除旧实例，避免 knownUuids/lookup 里出现同 UUID 冲突
            if (eca$duplicateWasInvulnerable) {
                EntityUtil.remove(sp, Entity.RemovalReason.CHANGED_DIMENSION);
            }
        } else {
            eca$oldDuplicate = null;
            eca$duplicateWasInvulnerable = false;
        }
    }

    @Inject(method = "addPlayer", at = @At("TAIL"))
    private void eca$cleanupOldDuplicate(ServerPlayer newPlayer, CallbackInfo ci) {
        // 新玩家已正确添加到目标维度，清除维度切换标记
        // 对于 End→Overworld 终末之诗流程，标记在 changeDimension 返回时被延迟，此处完成清除
        EntityUtil.unmarkDimensionChanging(newPlayer);

        // End→Overworld 终末之诗流程中不存在旧重复实例（旧实体在 End，新实体在 Overworld）
        // 但 InvulnerableEntityManager 仍持有 UUID，需要同步无敌状态到新实例的 EntityData
        if (eca$oldDuplicate == null) {
            if (InvulnerableEntityManager.isInvulnerable(newPlayer.getUUID())) {
                EcaAPI.setInvulnerable(newPlayer, true);
            }
            eca$duplicateWasInvulnerable = false;
            return;
        }

        ServerLevel self = (ServerLevel)(Object)this;
        Entity current = self.getEntities().get(eca$oldDuplicate.getUUID());
        if (current == eca$oldDuplicate) {
            EntityUtil.remove(eca$oldDuplicate, Entity.RemovalReason.CHANGED_DIMENSION);
        }

        // 旧实例为无敌时，将无敌状态显式补回新玩家实例，避免 respawn 过程状态丢失
        if (eca$duplicateWasInvulnerable) {
            EcaAPI.setInvulnerable(newPlayer, true);
            EntityUtil.revive(newPlayer);
            EntityUtil.unmarkDimensionChanging(newPlayer);
        }

        eca$oldDuplicate = null;
        eca$duplicateWasInvulnerable = false;
    }
}
