package net.eca;

import net.eca.agent.AgentLoader;
import net.eca.event.EcaEventHandler;
import net.eca.init.ModConfigs;
import net.eca.util.EcaLogger;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

@Mod(EcaMod.MOD_ID)
public final class EcaMod {
    public static final String MOD_ID = "eca";

    static {
        AgentLoader.enableSelfAttach();
    }

    public EcaMod() {
        EcaLogger.info("EpicCoreAPI initializing...");

        // 注册配置
        ModConfigs.register();

        // 注册事件处理器
        MinecraftForge.EVENT_BUS.register(new EcaEventHandler());

        // 加载Agent
        if (AgentLoader.loadAgent(EcaMod.class)) {
            EcaLogger.info("Agent loaded successfully");
        } else {
            EcaLogger.warn("Agent loading failed - some features may be unavailable");
        }

        EcaLogger.info("EpicCoreAPI initialized");
    }
}
