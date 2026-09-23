package net.eca;

import net.eca.event.EcaEventHandler;
import net.eca.event.LoadCompleteHandler;
import net.eca.init.ModConfigs;
import net.eca.network.NetworkHandler;
import net.eca.util.selector.EcaSelectorRegistry;
import net.eca.util.entity_extension.ForceLoadingManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@SuppressWarnings("removal")
@Mod(EcaMod.MOD_ID)
public final class EcaMod {
    public static final String MOD_ID = "eca";
    private static volatile boolean loadComplete;

    public static boolean isLoadComplete() { return loadComplete; }

    public static void setLoadComplete(boolean value) { loadComplete = value; }

    public EcaMod() {
        ModConfigs.register();
        NetworkHandler.register();
        MinecraftForge.EVENT_BUS.register(new EcaEventHandler());
        EcaSelectorRegistry.register();
        ForceLoadingManager.registerValidationCallback();
        LoadCompleteHandler loadCompleteHandler = new LoadCompleteHandler();
        FMLJavaModLoadingContext.get().getModEventBus().addListener(loadCompleteHandler::onLoadComplete);
    }
}
