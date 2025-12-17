package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {

    /**
     * 拦截客户端玩家的shouldShowDeathScreen
     * 防止无敌玩家显示死亡界面
     */
    @Inject(method = "shouldShowDeathScreen", at = @At("HEAD"), cancellable = true)
    private void onShouldShowDeathScreen(CallbackInfoReturnable<Boolean> cir) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (EcaAPI.isInvulnerable(player)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * 拦截客户端玩家的tickDeath方法
     * LocalPlayer重写了tickDeath，必须直接拦截
     */
    @Inject(method = "tickDeath", at = @At("HEAD"), cancellable = true)
    private void onTickDeath(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (EcaAPI.isInvulnerable(player)) {
            ci.cancel();
        }
    }

    /**
     * 在客户端每tick复活无敌玩家
     * 防止客户端本地状态变为死亡
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (EcaAPI.isInvulnerable(player)) {
            EntityUtil.reviveEntity(player);
        }
    }
}
