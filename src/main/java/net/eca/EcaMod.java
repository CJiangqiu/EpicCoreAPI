package net.eca;

import net.eca.agent.EcaAgent;
import net.eca.compat.GeckoLibCompat;
import net.eca.coremod.EcaClassTransformer;
import net.eca.event.EcaEventHandler;
import net.eca.event.LoadCompleteHandler;
import net.eca.init.ModConfigs;
import net.eca.network.NetworkHandler;
import net.eca.util.selector.EcaSelectorRegistry;
import net.eca.util.entity_extension.ForceLoadingManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@SuppressWarnings("removal")
@Mod(EcaMod.MOD_ID)
public final class EcaMod {
    public static final String MOD_ID = "eca";

    private static volatile boolean loadComplete = false;

    public static boolean isLoadComplete() {
        return loadComplete;
    }

    public static void setLoadComplete(boolean value) {
        loadComplete = value;
    }

    public EcaMod() {

        // 从 CoreMod ClassLoader 桥接 Instrumentation 到 GAME layer
        bridgeInstrumentationFromCoremod();
        // 注册 ClassFileTransformer
        EcaClassTransformer.init();
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
    }

    //从 SystemClassLoader（Agent 层）或 CoreMod ClassLoader 获取 Instrumentation 桥接到当前 GAME layer
    private void bridgeInstrumentationFromCoremod() {
        if (EcaAgent.getInstrumentation() != null) return;

        try {
            // 尝试从 SystemClassLoader 的 EcaAgent 获取
            Class<?> agentClass = ClassLoader.getSystemClassLoader().loadClass("net.eca.agent.EcaAgent");
            java.lang.instrument.Instrumentation inst =
                (java.lang.instrument.Instrumentation) agentClass.getMethod("getInstrumentation").invoke(null);

            if (inst != null) {
                java.lang.reflect.Field instField = EcaAgent.class.getDeclaredField("instrumentation");
                instField.setAccessible(true);
                instField.set(null, inst);
            }
        } catch (Throwable t) {
            // SystemClassLoader 没有 → 尝试暴力搜索所有已加载类
            try {
                bridgeFromAnyClassLoader();
            } catch (Throwable ignored) {}
        }
    }

    private void bridgeFromAnyClassLoader() throws Exception {
        // Agent 层的 Instrumentation 可能在任意 ClassLoader 的 EcaAgent 中
        // 用已有的 Instrumentation (如果有) 搜索，否则无法搜索
        // 这里用 Thread 的 contextClassLoader 链尝试
        ClassLoader[] loaders = {
            ClassLoader.getSystemClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            EcaMod.class.getClassLoader().getParent()
        };

        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try {
                Class<?> agentClass = Class.forName("net.eca.agent.EcaAgent", false, loader);
                if (agentClass == EcaAgent.class) continue;

                java.lang.instrument.Instrumentation inst =
                    (java.lang.instrument.Instrumentation) agentClass.getMethod("getInstrumentation").invoke(null);
                if (inst != null) {
                    java.lang.reflect.Field instField = EcaAgent.class.getDeclaredField("instrumentation");
                    instField.setAccessible(true);
                    instField.set(null, inst);
                    return;
                }
            } catch (ClassNotFoundException ignored) {}
        }
    }
}
