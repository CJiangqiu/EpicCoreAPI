package net.eca;

import net.eca.agent.EcaAgent;
import net.eca.agent.ReturnToggle;
import net.eca.event.EcaEventHandler;
import net.eca.init.ModConfigs;
import net.eca.network.NetworkHandler;
import net.eca.util.EcaLogger;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.forgespi.language.IModInfo;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Set;

@SuppressWarnings("removal")
@Mod(EcaMod.MOD_ID)
public final class EcaMod {
    public static final String MOD_ID = "eca";

    // 标志：Forge 加载是否完成
    private static volatile boolean loadComplete = false;

    public static boolean isLoadComplete() {
        return loadComplete;
    }

    public EcaMod() {
        EcaLogger.info("EpicCoreAPI initializing...");

        // 注册配置
        ModConfigs.register();

        // 注册网络处理器
        NetworkHandler.register();

        // 注册事件处理器
        MinecraftForge.EVENT_BUS.register(new EcaEventHandler());

        // 注册 Forge 生命周期事件
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onLoadComplete);

        // Agent 已在 CoreMod 阶段加载，这里只需要收集 mod 包名
        if (EcaAgent.isInitialized()) {
            EcaLogger.info("Agent already loaded by CoreMod");
            collectAndSetModPackages();
        } else {
            EcaLogger.warn("Agent not initialized - some features may be unavailable");
        }

        EcaLogger.info("EpicCoreAPI initialized");
    }

    /**
     * Forge 加载完成事件
     */
    private void onLoadComplete(FMLLoadCompleteEvent event) {
        loadComplete = true;
        EcaLogger.info("Forge load complete,start check");
    }

    /**
     * 收集所有第三方 mod 的包名前缀并设置到 ReturnToggle
     */
    private void collectAndSetModPackages() {
        try {
            Set<String> modPackages = new HashSet<>();
            Set<String> excludedModIds = Set.of(
                "minecraft", "forge", "fml", "eca"
            );

            ModList.get().forEachModFile(modFile -> {
                for (IModInfo modInfo : modFile.getModInfos()) {
                    String modId = modInfo.getModId();

                    if (excludedModIds.contains(modId)) {
                        continue;
                    }

                    modFile.getScanResult().getClasses().forEach(classData -> {
                        String internalName = classData.clazz().getInternalName();
                        String prefix = extractPackagePrefix(internalName, 2);
                        if (prefix != null) {
                            modPackages.add(prefix);
                        }
                    });
                }
            });

            if (!modPackages.isEmpty()) {
                ReturnToggle.setModPackagePrefixes(modPackages);
                ReturnToggle.setLoadTimeTransformEnabled(true);

                setModPackagePrefixesInAgent(modPackages);
                setLoadTimeTransformEnabledInAgent(true);

                EcaLogger.info("Collected {} mod package prefixes for AllReturn", modPackages.size());
                if (modPackages.size() <= 10) {
                    EcaLogger.info("Mod packages: {}", modPackages);
                }
            }
        } catch (Throwable t) {
            EcaLogger.warn("Failed to collect mod packages: {}", t.getMessage());
        }
    }

    /**
     * 提取包名前缀
     */
    private String extractPackagePrefix(String internalClassName, int levels) {
        String[] parts = internalClassName.split("/");
        if (parts.length <= levels) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels; i++) {
            if (i > 0) sb.append("/");
            sb.append(parts[i]);
        }
        return sb.toString() + "/";
    }

    /**
     * 通过反射设置到 agent ClassLoader 中的 ReturnToggle
     */
    private void setModPackagePrefixesInAgent(Set<String> packages) {
        try {
            Instrumentation inst = EcaAgent.getInstrumentation();
            if (inst == null) {
                return;
            }

            ClassLoader localLoader = EcaMod.class.getClassLoader();
            Class<?> agentReturnToggle = null;

            for (Class<?> clazz : inst.getAllLoadedClasses()) {
                if ("net.eca.agent.ReturnToggle".equals(clazz.getName())) {
                    ClassLoader loader = clazz.getClassLoader();
                    if (loader != localLoader) {
                        agentReturnToggle = clazz;
                        break;
                    }
                }
            }

            if (agentReturnToggle != null) {
                agentReturnToggle.getMethod("setModPackagePrefixes", Set.class)
                    .invoke(null, packages);
                EcaLogger.info("Set mod packages to agent ReturnToggle");
            }
        } catch (Throwable t) {
            EcaLogger.warn("Failed to set mod packages to agent: {}", t.getMessage());
        }
    }

    /**
     * 通过反射设置 loadTimeTransformEnabled 到 agent ClassLoader 中的 ReturnToggle
     */
    private void setLoadTimeTransformEnabledInAgent(boolean enabled) {
        try {
            java.lang.instrument.Instrumentation inst = EcaAgent.getInstrumentation();
            if (inst == null) {
                return;
            }

            ClassLoader localLoader = EcaMod.class.getClassLoader();
            Class<?> agentReturnToggle = null;

            for (Class<?> clazz : inst.getAllLoadedClasses()) {
                if ("net.eca.agent.ReturnToggle".equals(clazz.getName())) {
                    ClassLoader loader = clazz.getClassLoader();
                    if (loader != localLoader) {
                        agentReturnToggle = clazz;
                        break;
                    }
                }
            }

            if (agentReturnToggle != null) {
                agentReturnToggle.getMethod("setLoadTimeTransformEnabled", boolean.class)
                    .invoke(null, enabled);
                EcaLogger.info("Set loadTimeTransformEnabled to {} in agent ReturnToggle", enabled);
            }
        } catch (Throwable t) {
            EcaLogger.warn("Failed to set loadTimeTransformEnabled to agent: {}", t.getMessage());
        }
    }
}
