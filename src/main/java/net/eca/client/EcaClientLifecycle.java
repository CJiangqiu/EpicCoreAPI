package net.eca.client;

import net.eca.EcaMod;
import net.eca.client.render.BlockExtensionRenderer;
import net.eca.client.render.preset.ShaderPresetRegistry;
import net.eca.compat.GeckoLibCompat;
import net.eca.util.block_extension.BlockExtensionManager;
import net.eca.util.item_extension.ItemExtensionManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = EcaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class EcaClientLifecycle {

    private EcaClientLifecycle() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            BlockExtensionRenderer.register();
            if (ModList.get().isLoaded("geckolib")) {
                GeckoLibCompat.register();
            }
        });
    }

    @SubscribeEvent
    public static void onLoadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(ItemExtensionManager::scanAndRegisterAll);
        event.enqueueWork(BlockExtensionManager::scanAndRegisterAll);
        event.enqueueWork(ShaderPresetRegistry::scanAndRegisterAll);
    }
}
