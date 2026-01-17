package net.eca;

import net.eca.agent.AgentLoader;
import net.eca.event.EcaEventHandler;
import net.eca.init.ModConfigs;
import net.eca.network.NetworkHandler;
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

        // 注册网络处理器
        NetworkHandler.register();

        // 注册事件处理器
        MinecraftForge.EVENT_BUS.register(new EcaEventHandler());

        // 加载Agent
        if (AgentLoader.loadAgent(EcaMod.class)) {
            EcaLogger.info("Agent loaded successfully");

            // 收集所有 mod 包名并设置到 ReturnToggle
            collectAndSetModPackages();
        } else {
            EcaLogger.warn("Agent loading failed - some features may be unavailable");
        }

        EcaLogger.info("EpicCoreAPI initialized");
    }

    /**
     * 收集所有第三方 mod 的包名前缀并设置到 ReturnToggle
     */
    private void collectAndSetModPackages() {
        try {
            java.util.Set<String> modPackages = new java.util.HashSet<>();
            java.util.Set<String> excludedModIds = java.util.Set.of(
                "minecraft", "forge", "fml", "eca"
            );

            net.minecraftforge.fml.ModList.get().forEachModFile(modFile -> {
                for (net.minecraftforge.forgespi.language.IModInfo modInfo : modFile.getModInfos()) {
                    String modId = modInfo.getModId();

                    // 跳过核心 mod
                    if (excludedModIds.contains(modId)) {
                        continue;
                    }

                    // 从扫描结果获取所有类
                    modFile.getScanResult().getClasses().forEach(classData -> {
                        String internalName = classData.clazz().getInternalName();
                        // 提取顶层包名（前两级，如 com/example/）
                        String prefix = extractPackagePrefix(internalName, 2);
                        if (prefix != null) {
                            modPackages.add(prefix);
                        }
                    });
                }
            });

            if (!modPackages.isEmpty()) {
                // 设置到 ReturnToggle（mod ClassLoader）
                net.eca.agent.ReturnToggle.setModPackagePrefixes(modPackages);
                net.eca.agent.ReturnToggle.setLoadTimeTransformEnabled(true);

                // 也设置到 agent ClassLoader
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
     * @param internalClassName 内部类名 (如 com/example/mod/MyClass)
     * @param levels 提取的层级数
     * @return 包名前缀 (如 com/example/)
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
    private void setModPackagePrefixesInAgent(java.util.Set<String> packages) {
        try {
            java.lang.instrument.Instrumentation inst = net.eca.agent.EcaAgent.getInstrumentation();
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
                agentReturnToggle.getMethod("setModPackagePrefixes", java.util.Set.class)
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
            java.lang.instrument.Instrumentation inst = net.eca.agent.EcaAgent.getInstrumentation();
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
