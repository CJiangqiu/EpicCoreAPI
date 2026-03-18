package net.eca;

import net.eca.agent.AgentLoader;
import net.eca.agent.EcaAgent;
import net.eca.agent.ReturnToggle;
import net.eca.compat.GeckoLibCompat;
import net.eca.event.EcaEventHandler;
import net.eca.event.LoadCompleteHandler;
import net.eca.init.ModConfigs;
import net.eca.network.NetworkHandler;
import net.eca.util.EcaLogger;
import net.eca.util.selector.EcaSelectorRegistry;
import net.eca.util.entity_extension.ForceLoadingManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.forgespi.language.IModInfo;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.Collections;
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

    public static void setLoadComplete(boolean value) {
        loadComplete = value;
    }

    public EcaMod() {
        // 注册配置
        ModConfigs.register();

        // 注册网络处理器
        NetworkHandler.register();

        // 注册事件处理器
        MinecraftForge.EVENT_BUS.register(new EcaEventHandler());

        // 注册全局 ECA 实体选择器（@eca_e / @eca_p）
        EcaSelectorRegistry.register();

        // GeckoLib 兼容：仅客户端且 GeckoLib 存在时注册渲染层
        if (FMLEnvironment.dist == Dist.CLIENT && ModList.get().isLoaded("geckolib")) {
            GeckoLibCompat.register();
        }

        // 注册强加载区块票据验证回调
        ForceLoadingManager.registerValidationCallback();

        // 注册 Forge 生命周期事件
        LoadCompleteHandler loadCompleteHandler = new LoadCompleteHandler();
        FMLJavaModLoadingContext.get().getModEventBus().addListener(loadCompleteHandler::onLoadComplete);

        // 从 CoreMod 阶段已挂载的 Agent 桥接 Instrumentation 到 GAME layer
        boolean agentReady = bridgeFromEarlyAgent();
        if (!agentReady) {
            // Fallback：CoreMod 未能挂载 Agent，直接在此加载
            EcaLogger.warn("Early agent not found, attaching agent directly...");
            AgentLoader.enableSelfAttach();
            AgentLoader.loadAgent(EcaMod.class);
            agentReady = EcaAgent.isInitialized();
        }
        // 收集 mod 包名用于 AllReturn
        if (EcaAgent.isInitialized()) {
            collectAndSetModPackages();
        } else {
            EcaLogger.warn("Agent not initialized - some features may be unavailable");
        }

    }

    /**
     * 从 SystemClassLoader 中的 Agent 获取 Instrumentation 并桥接到 GAME layer
     * Agent 由 CoreMod (EcaTransformationService) 在游戏窗口创建前提前挂载
     */
    private boolean bridgeFromEarlyAgent() {
        try {
            Class<?> agentClass = ClassLoader.getSystemClassLoader()
                    .loadClass("net.eca.agent.EcaAgent");

            Field instField = agentClass.getDeclaredField("instrumentation");
            instField.setAccessible(true);
            Instrumentation inst = (Instrumentation) instField.get(null);

            if (inst == null) {
                return false;
            }

            Class<?> localAgentClass = EcaAgent.class;
            if (localAgentClass == agentClass) {
                return EcaAgent.isInitialized();
            }

            Field localInstField = localAgentClass.getDeclaredField("instrumentation");
            localInstField.setAccessible(true);
            localInstField.set(null, inst);

            Field localInitField = localAgentClass.getDeclaredField("initialized");
            localInitField.setAccessible(true);
            localInitField.setBoolean(null, true);

            openModulesForGameLayer(inst);

            return true;
        } catch (Throwable t) {
            EcaLogger.warn("Failed to bridge from early agent: {}", t.getMessage());
            return false;
        }
    }

    /**
     * 为 GAME layer 打开 java.util / java.lang 模块访问权限
     */
    private void openModulesForGameLayer(Instrumentation inst) {
        try {
            Module targetModule = EcaMod.class.getModule();
            Module base = Object.class.getModule();

            for (String pkg : base.getPackages()) {
                if (pkg.startsWith("java.util") || pkg.startsWith("java.lang")) {
                    if (!base.isOpen(pkg, targetModule)) {
                        inst.redefineModule(
                                base,
                                Collections.emptySet(),
                                Collections.emptyMap(),
                                Collections.singletonMap(pkg, Collections.singleton(targetModule)),
                                Collections.emptySet(),
                                Collections.emptyMap()
                        );
                    }
                }
            }
        } catch (Throwable t) {
            EcaLogger.warn("Failed to open modules for GAME layer: {}", t.getMessage());
        }
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
                agentReturnToggle.getMethod("setLoadTimeTransformEnabled", boolean.class)
                    .invoke(null, enabled);
            }
        } catch (Throwable t) {
            EcaLogger.warn("Failed to set loadTimeTransformEnabled to agent: {}", t.getMessage());
        }
    }
}
