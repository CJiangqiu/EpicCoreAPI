package net.eca.mixin;

import net.eca.api.EcaAPI;

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

    /**
     * 拦截客户端停止追踪实体的回调
     * 防止无敌实体在客户端视觉上消失
     */
    @Inject(method = "onTrackingEnd*", at = @At("HEAD"), cancellable = true)
    private void onTrackingEnd(Entity entity, CallbackInfo ci) {
        if (EcaAPI.isInvulnerable(entity)) {
            ci.cancel();
        }
    }

    /**
     * 拦截客户端停止tick实体的回调
     * 防止无敌实体停止更新；
     * 防止强加载实体（存活状态）被移出entityTickList，确保位置插值正常推进
     */
    @Inject(method = "onTickingEnd*", at = @At("HEAD"), cancellable = true)
    private void onTickingEnd(Entity entity, CallbackInfo ci) {
        if (EcaAPI.isInvulnerable(entity)) {
            ci.cancel();
        } else if (ForceLoadingManager.isForceLoadedType(entity.getType()) && !entity.isRemoved()) {
            ci.cancel();
        }
    }

    /**
     * 拦截客户端销毁实体的回调
     * 防止无敌实体被客户端销毁
     */
    @Inject(method = "onDestroyed*", at = @At("HEAD"), cancellable = true)
    private void onDestroyed(Entity entity, CallbackInfo ci) {
        if (EcaAPI.isInvulnerable(entity)) {
            ci.cancel();
        }
    }
}
