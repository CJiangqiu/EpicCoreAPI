package net.eca.mixin;

import net.eca.util.item_extension.EcaTooltipLine;
import net.eca.util.item_extension.EcaTooltipPosition;
import net.eca.util.item_extension.ItemExtension;
import net.eca.util.item_extension.ItemExtensionManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Comparator;
import java.util.List;

@OnlyIn(Dist.CLIENT)
@Mixin(ItemStack.class)
public class ItemStackClientMixin {

    // 玩家通过铁砧设置的名称必须优先于扩展名称。
    @Inject(method = "getHoverName", at = @At("RETURN"), cancellable = true)
    private void eca$overrideItemName(CallbackInfoReturnable<Component> callback) {
        ItemStack stack = (ItemStack) (Object) this;
        if (stack.hasCustomHoverName()) return;
        ItemExtension extension = ItemExtensionManager.getExtension(stack.getItem());
        if (extension == null || !extension.enabled()) return;
        MutableComponent name = extension.getItemName(stack);
        if (name != null) callback.setReturnValue(name);
    }

    // 与原版 tooltip 生成完成点对齐，保证扩展可以控制最终插入位置。
    @Inject(method = "getTooltipLines", at = @At("RETURN"))
    private void eca$appendTooltip(Player player, TooltipFlag flag,
                                   CallbackInfoReturnable<List<Component>> callback) {
        ItemStack stack = (ItemStack) (Object) this;
        if (stack.isEmpty()) return;
        ItemExtension extension = ItemExtensionManager.getExtension(stack.getItem());
        if (extension == null || !extension.enabled()) return;
        List<Component> lines = callback.getReturnValue();
        eca$insertStructuredTooltip(stack, lines, extension.getTooltipLines(stack, flag));
        extension.appendTooltip(stack, flag, lines);
    }

    private static void eca$insertStructuredTooltip(ItemStack stack, List<Component> target,
                                                    List<EcaTooltipLine> source) {
        if (target == null || source == null || source.isEmpty()) return;
        eca$insertAt(target, source, EcaTooltipPosition.HEAD, Math.min(1, target.size()));
        eca$insertAt(target, source, EcaTooltipPosition.BODY, eca$bodyInsertIndex(stack, target));
        eca$insertAt(target, source, EcaTooltipPosition.TAIL, target.size());
    }

    private static void eca$insertAt(List<Component> target, List<EcaTooltipLine> source,
                                     EcaTooltipPosition position, int index) {
        List<Component> components = source.stream()
            .filter(line -> line != null && line.position() == position)
            .sorted(Comparator.comparingInt(EcaTooltipLine::order))
            .map(EcaTooltipLine::component)
            .toList();
        if (!components.isEmpty()) {
            target.addAll(Math.max(0, Math.min(index, target.size())), components);
        }
    }

    private static int eca$bodyInsertIndex(ItemStack stack, List<Component> lines) {
        int fallback = lines.size();
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String disabled = Component.translatable("item.disabled").getString();
        String nbtTags = stack.hasTag()
            ? Component.translatable("item.nbt_tags", stack.getTag().getAllKeys().size()).getString()
            : null;
        for (int i = 1; i < lines.size(); i++) {
            String text = lines.get(i).getString();
            if (text.equals(itemId) || (nbtTags != null && text.equals(nbtTags)) || text.equals(disabled)) {
                fallback = i;
                break;
            }
        }
        return Math.max(1, fallback);
    }
}
