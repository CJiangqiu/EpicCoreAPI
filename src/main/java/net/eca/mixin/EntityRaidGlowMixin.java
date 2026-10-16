package net.eca.mixin;

import net.eca.util.raid.RaidGlowState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityRaidGlowMixin implements RaidGlowState {
    @Unique private boolean eca$raidGlow;

    @Override
    public void eca$setRaidGlow(boolean glowing) {
        if (eca$raidGlow == glowing) return;
        eca$raidGlow = glowing;
        Entity entity = (Entity) (Object) this;
        // 仅刷新共享描边位，保留原本的发光标签和药水效果。
        entity.setGlowingTag(entity.hasGlowingTag());
    }

    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void eca$includeRaidGlow(CallbackInfoReturnable<Boolean> cir) {
        if (eca$raidGlow) cir.setReturnValue(true);
    }
}
