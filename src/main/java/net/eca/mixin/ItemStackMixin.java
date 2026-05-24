package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.item_extension.ItemExtension;
import net.eca.util.item_extension.ItemExtensionManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ItemStack.class)
public class ItemStackMixin {

    @Inject(method = "interactLivingEntity", at = @At("HEAD"), cancellable = true)
    private void eca$onInteractLivingEntity(Player player, LivingEntity target, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (EcaAPI.isInvulnerable(target)) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    //物品扩展提供自定义名字时覆盖显示名，但玩家铁砧改名优先
    @OnlyIn(Dist.CLIENT)
    @Inject(method = "getHoverName", at = @At("RETURN"), cancellable = true)
    private void eca$overrideItemName(CallbackInfoReturnable<Component> cir) {
        ItemStack self = (ItemStack)(Object) this;
        if (self.hasCustomHoverName()) return;
        ItemExtension ext = ItemExtensionManager.getExtension(self.getItem());
        if (ext == null || !ext.enabled()) return;
        MutableComponent name = ext.getItemName(self);
        if (name != null) cir.setReturnValue(name);
    }

    //物品扩展追加/修改 tooltip，注入点与 Forge ItemTooltipEvent 同源
    @OnlyIn(Dist.CLIENT)
    @Inject(method = "getTooltipLines", at = @At("RETURN"))
    private void eca$appendTooltip(Player player, TooltipFlag flag, CallbackInfoReturnable<List<Component>> cir) {
        ItemStack self = (ItemStack)(Object) this;
        if (self.isEmpty()) return;
        ItemExtension ext = ItemExtensionManager.getExtension(self.getItem());
        if (ext == null || !ext.enabled()) return;
        ext.appendTooltip(self, flag, cir.getReturnValue());
    }

}
