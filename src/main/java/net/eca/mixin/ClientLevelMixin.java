package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientLevel.class, priority = 1100)
public class ClientLevelMixin {

    @Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void eca$preventClientRemoval(int entityId, Entity.RemovalReason reason, CallbackInfo ci) {
        try {
            ClientLevel clientLevel = (ClientLevel) (Object) this;
            Entity entity = clientLevel.getEntity(entityId);

            if (entity != null && EcaAPI.isInvulnerable(entity)) {
                ci.cancel();
            }
        } catch (Exception ignored) {
        }
    }
}
