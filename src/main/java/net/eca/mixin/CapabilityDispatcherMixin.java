package net.eca.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraftforge.common.capabilities.CapabilityDispatcher;

/**
 * 防止 Capability 序列化时因返回 null 导致崩溃
 */
@Mixin(value = CapabilityDispatcher.class, remap = false)
public class CapabilityDispatcherMixin {

    @Redirect(
        method = "serializeNBT",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/nbt/CompoundTag;put(Ljava/lang/String;Lnet/minecraft/nbt/Tag;)Lnet/minecraft/nbt/Tag;",
                 remap = true)
    )
    private Tag eca$nullSafePut(CompoundTag nbt, String key, Tag value) {
        if (value == null) {
            return null;
        }
        return nbt.put(key, value);
    }
}
