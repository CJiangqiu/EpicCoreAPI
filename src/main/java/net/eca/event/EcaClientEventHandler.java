package net.eca.event;

import net.eca.client.FactionGlowData;
import net.eca.client.music.CombatMusicManager;
import net.eca.client.HealthClientSync;
import net.eca.coremod.AllReturnToggle;
import net.eca.util.entity_extension.EntityExtensionClientState;
import net.eca.util.raid.RaidClientState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;

//客户端事件处理：断开连接时清空实体扩展客户端状态，防止单人模式下静态状态跨存档残留
@Mod.EventBusSubscriber(modid = "eca", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EcaClientEventHandler {

    private EcaClientEventHandler() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) HealthClientSync.tick();
    }

    @SubscribeEvent
    public static void onClientLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            RaidClientState.clearAll();
            CombatMusicManager.reset();
        }
    }

    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        AllReturnToggle.clearAll();
        HealthClientSync.clear();
        EntityExtensionClientState.clearAll();
        FactionGlowData.clear();
        RaidClientState.clearAll();
        CombatMusicManager.reset();
    }
}
