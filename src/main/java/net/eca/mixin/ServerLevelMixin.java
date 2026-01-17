package net.eca.mixin;

import net.eca.util.spawn.SpawnBanHook;
import net.eca.util.spawn.SpawnBanManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {

    @Unique
    private long eca$lastBanTickTime = 0;

    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onAddEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (SpawnBanHook.shouldBlockSpawn(self, entity)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (SpawnBanHook.shouldBlockSpawn(self, entity)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void eca$onTick(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        long currentTime = self.getGameTime();

        // 每 20 tick (1秒) 更新一次禁令
        if (currentTime - eca$lastBanTickTime >= 20) {
            eca$lastBanTickTime = currentTime;
            SpawnBanManager.tickBans(self);
        }
    }
}
