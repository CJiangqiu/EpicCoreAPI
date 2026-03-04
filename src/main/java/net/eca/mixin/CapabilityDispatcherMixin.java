package net.eca.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.util.INBTSerializable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 防止 Capability 序列化时因返回 null 导致崩溃
 */
@Mixin(value = CapabilityDispatcher.class, remap = false)
public class CapabilityDispatcherMixin {

    @Shadow private INBTSerializable<Tag>[] writers;
    @Shadow private String[] names;

    @Inject(method = "serializeNBT", at = @At("HEAD"), cancellable = true)
    private void eca$nullSafeSerializeNBT(CallbackInfoReturnable<CompoundTag> cir) {
        CompoundTag nbt = new CompoundTag();
        for (int x = 0; x < this.writers.length; ++x) {
            Tag tag = this.writers[x].serializeNBT();
            if (tag != null) {
                nbt.put(this.names[x], tag);
            }
        }
        cir.setReturnValue(nbt);
    }
}
