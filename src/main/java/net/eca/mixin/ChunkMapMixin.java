package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkMap.class, priority = 1024)
public class ChunkMapMixin {

    @Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true)
    private void onChunkMapRemoveEntity(Entity entity, CallbackInfo ci) {
        if (EcaAPI.isInvulnerable(entity)) {
            ci.cancel();
        }
    }
}
