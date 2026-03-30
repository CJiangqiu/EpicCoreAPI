package net.eca.agent;

import net.eca.agent.transform.AgentConfigReader;
import net.eca.agent.transform.AllReturnTransformer;
import net.eca.agent.transform.ContainerReplacementTransformer;
import net.eca.agent.transform.EcaTransformer;
import net.eca.agent.transform.EntityTransformer;
import net.eca.agent.transform.LivingEntityTransformer;
import net.eca.agent.transform.LoadingScreenTransformer;
import net.eca.agent.transform.TransformApplier;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.Collections;

// ECA Java Agent入口
/**
 * ECA Java Agent entry point.
 * Handles agent initialization and class retransformation.
 */
public final class EcaAgent {

    private static Instrumentation instrumentation;
    private static boolean initialized = false;

    // premain入口（JVM启动时加载）
    /**
     * Entry point when agent is loaded at JVM startup.
     * @param args agent arguments
     * @param inst instrumentation instance
     */
    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }

    // agentmain入口（运行时附着）
    /**
     * Entry point when agent is attached at runtime.
     * @param args agent arguments (typically the caller class name)
     * @param inst instrumentation instance
     */
    public static void agentmain(String args, Instrumentation inst) {
        try {
            AgentLogWriter.info("[EcaAgent] Starting agent initialization...");
            instrumentation = inst;
            bridgeInstrumentationToCallerClassLoader(args, inst);

            // 打开必要的模块
            applyOpenPackages(args, inst);

            // 获取EcaTransformer并注册模块
            EcaTransformer transformer = EcaTransformer.getInstance();

            // 注册加载界面渐变转换模块
            if (AgentConfigReader.isCustomLoadingBackgroundEnabled()) {
                transformer.registerModule(new LoadingScreenTransformer());
                AgentLogWriter.info("[EcaAgent] Registered LoadingScreenTransformer");
            } else {
                AgentLogWriter.info("[EcaAgent] Custom loading background disabled by config");
            }

            transformer.registerModule(new ContainerReplacementTransformer());
            transformer.registerModule(new EntityTransformer());
            transformer.registerModule(new LivingEntityTransformer());
            transformer.registerModule(new AllReturnTransformer());

            // 设置 getHealth() 完整钩子处理器
            LivingEntityTransformer.setHookHandler(
                "net/eca/util/health/LivingEntityHook",
                "processGetHealth",
                "(Lnet/minecraft/world/entity/LivingEntity;)F"
            );
            AgentLogWriter.info("[EcaAgent] Registered LivingEntityHook handler");

            TransformApplier.apply(null);

            initialized = true;
            AgentLogWriter.info("[EcaAgent] Agent initialization completed successfully");

        } catch (Throwable t) {
            AgentLogWriter.error("[EcaAgent] Agent initialization failed", t);
            throw new RuntimeException(t);
        }
    }

    private static void bridgeInstrumentationToCallerClassLoader(String callerClassName, Instrumentation inst) {
        if (callerClassName == null || inst == null) {
            return;
        }

        ClassLoader targetLoader = null;
        try {
            Class<?> callerClass = findClass(inst.getAllLoadedClasses(), callerClassName);
            if (callerClass != null) {
                targetLoader = callerClass.getClassLoader();
            }
        } catch (Throwable ignored) {
        }

        if (targetLoader == null) {
            targetLoader = ClassLoader.getSystemClassLoader();
        }

        try {
            Class<?> modEcaAgent = Class.forName("net.eca.agent.EcaAgent", false, targetLoader);
            if (modEcaAgent == EcaAgent.class) {
                return;
            }
            Field instField = modEcaAgent.getDeclaredField("instrumentation");
            instField.setAccessible(true);
            instField.set(null, inst);
            Field initField = modEcaAgent.getDeclaredField("initialized");
            initField.setAccessible(true);
            initField.setBoolean(null, true);
            AgentLogWriter.info("[EcaAgent] Bridged Instrumentation to caller classloader");
        } catch (Throwable t) {
            AgentLogWriter.warn("[EcaAgent] Failed to bridge Instrumentation: " + t.getMessage());
        }
    }

    // 打开必要的模块
    private static void applyOpenPackages(String callerClassName, Instrumentation inst) {
        try {
            Module targetModule = null;
            if (callerClassName != null) {
                for (Class<?> clazz : inst.getAllLoadedClasses()) {
                    if (clazz.getName().equals(callerClassName)) {
                        targetModule = clazz.getModule();
                        break;
                    }
                }
            }

            if (targetModule == null) {
                targetModule = EcaAgent.class.getModule();
            }

            Module base = Object.class.getModule();
            for (String pkg : base.getPackages()) {
                if (pkg.startsWith("java.util") || pkg.startsWith("java.lang")) {
                    applyOpenPackage(inst, base, pkg, targetModule);
                }
            }

            AgentLogWriter.info("[EcaAgent] Opened required modules");

        } catch (Throwable t) {
            AgentLogWriter.warn("[EcaAgent] Failed to open modules: " + t.getMessage());
        }
    }

    private static void applyOpenPackage(Instrumentation inst, Module module, String pkg, Module to) {
        inst.redefineModule(
                module,
                Collections.emptySet(),
                Collections.emptyMap(),
                Collections.singletonMap(pkg, Collections.singleton(to)),
                Collections.emptySet(),
                Collections.emptyMap()
        );
    }

    private static Class<?> findClass(Class<?>[] allClasses, String className) {
        for (Class<?> clazz : allClasses) {
            if (clazz.getName().equals(className)) {
                return clazz;
            }
        }
        return null;
    }

    // 获取Instrumentation实例
    /**
     * Get the instrumentation instance.
     * @return the instrumentation instance, or null if not initialized
     */
    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    // 检查Agent是否已初始化
    /**
     * Check if the agent is initialized.
     * @return true if initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }

    private EcaAgent() {}
}
