package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.client.render.BlockExtensionRenderer;
import net.eca.util.EntityUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ClientLevel.class, priority = 1100)
public class ClientLevelMixin {

    @Inject(method = "setBlock", at = @At("RETURN"), require = 0)
    private void eca$trackBlockExtension(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            BlockExtensionRenderer.onBlockChanged((ClientLevel) (Object) this, pos, state);
        }
    }

    @Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void eca$preventClientRemoval(int entityId, Entity.RemovalReason reason, CallbackInfo ci) {
        try {
            if (reason == Entity.RemovalReason.CHANGED_DIMENSION) {
                ClientLevel clientLevel0 = (ClientLevel) (Object) this;
                Entity entity0 = clientLevel0.getEntity(entityId);
                if (entity0 != null && EntityUtil.isChangingDimension(entity0)) {
                    return;
                }
            }
            ClientLevel clientLevel = (ClientLevel) (Object) this;
            Entity entity = clientLevel.getEntity(entityId);

            if (entity instanceof LivingEntity && EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity)) {
                ci.cancel();
            }
        } catch (Exception ignored) {
        }
    }
}
