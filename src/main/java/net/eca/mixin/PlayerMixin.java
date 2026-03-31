package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public class PlayerMixin {

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void onAttack(Entity target, CallbackInfo ci) {
        if (EcaAPI.isInvulnerable(target)) {
            ci.cancel();
        }
    }

    //防止外部代码替换无敌玩家的装备槽
    @Inject(method = "setItemSlot", at = @At("HEAD"), cancellable = true)
    private void eca$onSetItemSlot(EquipmentSlot slot, ItemStack stack, CallbackInfo ci) {
        if (EcaAPI.isInvulnerable((Player) (Object) this)&& EntityUtil.hasExternalCaller(5)) {
            ci.cancel();
        }
    }
}
