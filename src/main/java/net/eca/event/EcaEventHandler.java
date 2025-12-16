package net.eca.event;

import net.eca.api.EcaAPI;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Event handler for ECA mod events.
 */
public class EcaEventHandler {

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        // 初始化实体的无敌状态 NBT
        EcaAPI.initInvulnerableNBT(event.getEntity());
    }
}
