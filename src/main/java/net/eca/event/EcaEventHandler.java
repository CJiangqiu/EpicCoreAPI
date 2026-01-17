package net.eca.event;

import net.eca.api.EcaAPI;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Event handler for ECA mod events.
 */
public class EcaEventHandler {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingDeath(LivingDeathEvent event) {
        // 无敌状态或血量锁定时阻止死亡
        if (EcaAPI.isInvulnerable(event.getEntity()) || EcaAPI.isHealthLocked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

}
