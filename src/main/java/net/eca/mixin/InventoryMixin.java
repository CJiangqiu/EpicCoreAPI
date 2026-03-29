package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(Inventory.class)
public abstract class InventoryMixin {

    @Final
    @Shadow
    public Player player;

    //拦截清除匹配物品，防止无敌玩家的物品被清除
    @Inject(method = "clearOrCountMatchingItems", at = @At("HEAD"), cancellable = true)
    private void eca$onClearOrCountMatchingItems(Predicate<ItemStack> predicate, int maxCount, Container container, CallbackInfoReturnable<Integer> cir) {
        if (EcaAPI.isInvulnerable(this.player)) {
            cir.setReturnValue(0);
        }
    }

    //拦截清空背包，防止无敌玩家的背包被清空
    @Inject(method = "clearContent", at = @At("HEAD"), cancellable = true)
    private void eca$onClearContent(CallbackInfo ci) {
        if (EcaAPI.isInvulnerable(this.player)) {
            ci.cancel();
        }
    }

    //拦截按引用移除物品，防止外部代码强制移除无敌玩家的物品
    @Inject(method = "removeItem(Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"), cancellable = true)
    private void eca$onRemoveItemByReference(ItemStack stack, CallbackInfo ci) {
        if (EcaAPI.isInvulnerable(this.player)) {
            ci.cancel();
        }
    }
}
