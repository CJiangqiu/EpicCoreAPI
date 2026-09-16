package net.eca.client.render.blender;

import net.eca.EcaMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = EcaMod.MOD_ID, value = Dist.CLIENT)
public final class BlenderAnimationClientEvents {
    private BlenderAnimationClientEvents() {
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            BlenderAnimationClientState.clear();
        }
    }
}
