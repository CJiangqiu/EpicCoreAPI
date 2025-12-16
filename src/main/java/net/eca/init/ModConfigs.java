package net.eca.init;

import net.eca.config.EcaConfiguration;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

@SuppressWarnings("removal")
//Mod配置注册类
public class ModConfigs {
    public static void register() {
        ModLoadingContext.get().registerConfig(
            ModConfig.Type.COMMON,
            EcaConfiguration.SPEC,
            "eca.toml"
        );
    }
}
