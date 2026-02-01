package net.eca;

import net.eca.agent.AgentLoader;
import net.eca.agent.BytecodeVerifier;
import net.eca.agent.EcaAgent;
import net.eca.agent.EcaTransformer;
import net.eca.agent.ReturnToggle;
import net.eca.config.EcaConfiguration;
import net.eca.event.EcaEventHandler;
import net.eca.init.ModConfigs;
import net.eca.network.NetworkHandler;
import net.eca.util.EcaLogger;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.forgespi.language.IModInfo;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

        // 加载 Agent（在 Mod ClassLoader 中，避免多 ClassLoader 桥接问题）
        boolean selfAttachEnabled = AgentLoader.enableSelfAttach();
        EcaLogger.info("Self-attach enabled: {}", selfAttachEnabled);

        boolean agentLoaded = AgentLoader.loadAgent(EcaMod.class);
        EcaLogger.info("Agent loaded: {}", agentLoaded);

        // 收集 mod 包名用于 AllReturn
        if (EcaAgent.isInitialized()) {
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
        EcaLogger.info("Forge load complete");

        // 激进防御：重新注册 transformer 并 retransform LivingEntity 类
        if (EcaConfiguration.getDefenceEnableRadicalLogicSafely()) {
            event.enqueueWork(this::triggerLivingEntityRetransform);
        }
    }

    // 标志：是否已执行过延迟 retransform
    private static volatile boolean hasDelayedRetransform = false;

    /**
     * 触发 LivingEntity 相关类的延迟 retransform
     * 通过重新注册 transformer 确保 ECA 的修改优先级最高
     */
    private void triggerLivingEntityRetransform() {
        if (hasDelayedRetransform) {
            return;
        }

        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            EcaLogger.warn("Cannot trigger delayed retransform: Instrumentation not available");
            return;
        }

        EcaTransformer transformer = EcaTransformer.getInstance();
        if (transformer == null) {
            EcaLogger.warn("Cannot trigger delayed retransform: EcaTransformer not available");
            return;
        }

        try {
            EcaLogger.info("Starting delayed LivingEntity retransform...");

            // 1. 移除旧的 transformer
            inst.removeTransformer(transformer);

            // 2. 重新注册（排在所有其他 transformer 之后）
            inst.addTransformer(transformer, true);

            // 3. 收集所有 LivingEntity 相关的类
            List<Class<?>> targets = new ArrayList<>();
            for (Class<?> clazz : inst.getAllLoadedClasses()) {
                if (!inst.isModifiableClass(clazz)) {
                    continue;
                }
                if (LivingEntity.class.isAssignableFrom(clazz)) {
                    targets.add(clazz);
                }
            }

            // 4. Retransform
            if (!targets.isEmpty()) {
                long startTime = System.currentTimeMillis();
                inst.retransformClasses(targets.toArray(new Class[0]));
                long elapsed = System.currentTimeMillis() - startTime;
                EcaLogger.info("Delayed retransform completed: {} classes in {}ms", targets.size(), elapsed);

                // 5. 验证字节码转换（验证第一个 LivingEntity 子类）
                BytecodeVerifier.verifyAndLog(inst, targets.get(0));
            }

            hasDelayedRetransform = true;

        } catch (Throwable e) {
            EcaLogger.error("Delayed retransform failed: {}", e.getMessage());
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
