package net.eca.event;

import net.eca.EcaMod;
import net.eca.util.bossshow.BossShowManager;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.faction.FactionManager;
import net.eca.util.health.EcaSetHealthManager;
import net.eca.util.health.HealthDataFlow;
import net.eca.util.raid.RaidManager;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;

/** Completes common registry work after Forge has loaded the mod. */
public final class LoadCompleteHandler {
    public void onLoadComplete(FMLLoadCompleteEvent event) {
        EcaMod.setLoadComplete(true);
        event.enqueueWork(FactionManager::scanAndRegisterAll);
        event.enqueueWork(RaidManager::scanAndRegisterAll);
        event.enqueueWork(EntityExtensionManager::scanAndRegisterAll);
        event.enqueueWork(BossShowManager::scanAndRegisterAll);
        event.enqueueWork(() -> {
            HealthDataFlow.init();
            EcaSetHealthManager.startWarmup();
        });
    }
}
