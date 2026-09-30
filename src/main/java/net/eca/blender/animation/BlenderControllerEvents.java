package net.eca.blender.animation;

import net.eca.EcaMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = EcaMod.MOD_ID)
public final class BlenderControllerEvents {
    private BlenderControllerEvents() { }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity().level() instanceof ServerLevel) BlenderControllers.discover(event.getEntity());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onDamage(LivingDamageEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel)) return;
        // Read cancellation after every subscriber has had a chance to handle the event.
        BlenderControllers.queueHurt(event.getEntity(), () -> !event.isCanceled() && event.getAmount() > 0);
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) BlenderControllers.tick(level);
    }
}
