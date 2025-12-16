package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {

    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void onServerLevelAddEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        // 预留用于未来扩展（例如禁止特定实体重新添加）
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void onServerLevelAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        // 预留用于未来扩展（例如禁止特定实体重新添加）
    }
}
