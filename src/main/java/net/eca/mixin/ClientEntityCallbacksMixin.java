package net.eca.mixin;

import net.eca.util.entity_extension.ForceLoadingManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 防止受保护实体在客户端的追踪/tick/销毁回调
 * 这些回调会导致客户端侧实体被移除，造成视觉上的消失
 */
@Mixin(ClientLevel.EntityCallbacks.class)
public class ClientEntityCallbacksMixin {

    @Inject(method = "onTrackingEnd*", at = @At("HEAD"), cancellable = true)
    private void onTrackingEnd(Entity entity, CallbackInfo ci) {
        if (ForceLoadingManager.shouldProtect(entity)) {
            ci.cancel();
        }
    }

    // 防止强加载实体被移出 entityTickList，确保位置插值正常推进
    @Inject(method = "onTickingEnd*", at = @At("HEAD"), cancellable = true)
    private void onTickingEnd(Entity entity, CallbackInfo ci) {
        if (ForceLoadingManager.shouldProtect(entity) && !entity.isRemoved()) {
            ci.cancel();
        }
    }

    @Inject(method = "onDestroyed*", at = @At("HEAD"), cancellable = true)
    private void onDestroyed(Entity entity, CallbackInfo ci) {
        if (ForceLoadingManager.shouldProtect(entity)) {
            ci.cancel();
        }
    }
}
