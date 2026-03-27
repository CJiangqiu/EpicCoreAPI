package net.eca.agent;

import net.eca.agent.transform.AllReturnTransformer;
import net.eca.agent.transform.ContainerReplacementTransformer;
import net.eca.agent.transform.EcaTransformer;
import net.eca.agent.transform.ITransformModule;
import net.eca.agent.transform.LivingEntityTransformer;
import net.eca.agent.transform.LoadingScreenTransformer;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// ECA Java Agent入口
/**
 * ECA Java Agent entry point.
 * Handles agent initialization and class retransformation.
 */
public final class EcaAgent {

    private static Instrumentation instrumentation;
    private static boolean initialized = false;
    private static final String LIVING_ENTITY_TRANSFORMER_NAME = "LivingEntityTransformer";

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

            // 注册加载界面渐变转换模块（最先 retransform，确保视觉反馈不被后续模块阻塞）
            if (AgentConfigReader.isCustomLoadingBackgroundEnabled()) {
                transformer.registerModule(new LoadingScreenTransformer());
                AgentLogWriter.info("[EcaAgent] Registered LoadingScreenTransformer");
            } else {
                AgentLogWriter.info("[EcaAgent] Custom loading background disabled by config");
            }

            // 注册容器替换模块
            transformer.registerModule(new ContainerReplacementTransformer());

            // 注册LivingEntity转换模块
            transformer.registerModule(new LivingEntityTransformer());

            // 注册AllReturn转换模块
            transformer.registerModule(new AllReturnTransformer());

            // 设置 getHealth() 完整钩子处理器（分析 + 覆盖）
            LivingEntityTransformer.setHookHandler(
                "net/eca/util/health/LivingEntityHook",
                "processGetHealth",
                "(Lnet/minecraft/world/entity/LivingEntity;F)F"
            );
            AgentLogWriter.info("[EcaAgent] Registered LivingEntityHook handler");

            applyAgentTransformers();

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
    /**
     * Open required modules for reflection access.
     * @param callerClassName the caller class name
     * @param inst instrumentation instance
     */
    private static void applyOpenPackages(String callerClassName, Instrumentation inst) {
        try {
            // 通过已加载的类查找目标模块
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
                // 使用EcaAgent自己的模块
                targetModule = EcaAgent.class.getModule();
            }

            // 打开java.util.*和java.lang.*
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

    // 打开模块
    /**
     * Open a module package to another module.
     * @param inst instrumentation instance
     * @param module the module to open
     * @param pkg the package to open
     * @param to the target module
     */
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

    public static void applyAgentTransformers() {
        Instrumentation inst = instrumentation;
        if (inst == null) {
            AgentLogWriter.warn("[EcaAgent] Cannot apply transformers: Instrumentation not available");
            return;
        }

        EcaTransformer transformer = EcaTransformer.getInstance();
        if (!transformer.getModules().isEmpty()) {
            applyAgentTransformersLocalOnly();
            return;
        }

        Class<?> peerAgent = findPeerAgentClass(inst);
        if (peerAgent != null) {
            try {
                peerAgent.getMethod("applyAgentTransformersLocalOnly").invoke(null);
                return;
            } catch (ReflectiveOperationException e) {
                AgentLogWriter.warn("[EcaAgent] Failed to invoke peer applyAgentTransformers: " + e.getMessage());
            }
        }

        AgentLogWriter.warn("[EcaAgent] No registered transform modules found");
    }

    public static void applyLivingEntityTransformers() {
        Instrumentation inst = instrumentation;
        if (inst == null) {
            AgentLogWriter.warn("[EcaAgent] Cannot apply LivingEntity transformer: Instrumentation not available");
            return;
        }

        EcaTransformer transformer = EcaTransformer.getInstance();
        if (!transformer.getModules().isEmpty()) {
            applyLivingEntityTransformersLocalOnly();
            return;
        }

        Class<?> peerAgent = findPeerAgentClass(inst);
        if (peerAgent != null) {
            try {
                peerAgent.getMethod("applyLivingEntityTransformersLocalOnly").invoke(null);
                return;
            } catch (ReflectiveOperationException e) {
                AgentLogWriter.warn("[EcaAgent] Failed to invoke peer LivingEntity transformer apply: " + e.getMessage());
            }
        }

        AgentLogWriter.warn("[EcaAgent] No registered transform modules found");
    }

    public static void applyAgentTransformersLocalOnly() {
        Instrumentation inst = instrumentation;
        if (inst == null) {
            AgentLogWriter.warn("[EcaAgent] Cannot apply transformers: Instrumentation not available");
            return;
        }

        EcaTransformer transformer = EcaTransformer.getInstance();
        if (transformer.getModules().isEmpty()) {
            AgentLogWriter.warn("[EcaAgent] No registered transform modules found");
            return;
        }

        applyAgentTransformersLocal(inst, transformer, true, null);
    }

    public static void applyLivingEntityTransformersLocalOnly() {
        Instrumentation inst = instrumentation;
        if (inst == null) {
            AgentLogWriter.warn("[EcaAgent] Cannot apply LivingEntity transformer: Instrumentation not available");
            return;
        }

        EcaTransformer transformer = EcaTransformer.getInstance();
        if (transformer.getModules().isEmpty()) {
            AgentLogWriter.warn("[EcaAgent] No registered transform modules found");
            return;
        }

        applyAgentTransformersLocal(inst, transformer, true, LIVING_ENTITY_TRANSFORMER_NAME);
    }

    /**
     * Apply all registered transform modules to currently loaded classes.
     * @param inst instrumentation instance
     * @param transformer transformer singleton
     * @param rebindTransformer true to remove/add transformer before retransforming
     */
    private static void applyAgentTransformersLocal(Instrumentation inst, EcaTransformer transformer, boolean rebindTransformer, String moduleNameFilter) {
        if (rebindTransformer) {
            try {
                inst.removeTransformer(transformer);
            } catch (Throwable ignored) {
            }
            inst.addTransformer(transformer, true);
            AgentLogWriter.info("[EcaAgent] Rebound EcaTransformer");
        }

        Class<?>[] allClasses = inst.getAllLoadedClasses();
        AgentLogWriter.info("[EcaAgent] Scanning " + allClasses.length + " loaded classes...");

        for (ITransformModule module : transformer.getModules()) {
            if (moduleNameFilter != null && !moduleNameFilter.equals(module.getName())) {
                continue;
            }
            List<Class<?>> targetClasses = new ArrayList<>();
            List<String> targetClassNames = module.getTargetClassNames();

            if (targetClassNames != null && !targetClassNames.isEmpty()) {
                for (String className : targetClassNames) {
                    Class<?> clazz = findClass(allClasses, className);
                    if (clazz == null) {
                        AgentLogWriter.warn("[EcaAgent] Target class not found: " + className);
                        continue;
                    }
                    if (!module.shouldRetransform(className, clazz.getClassLoader())) {
                        continue;
                    }
                    if (!inst.isModifiableClass(clazz)) {
                        AgentLogWriter.warn("[EcaAgent] Target class not modifiable: " + className);
                        continue;
                    }
                    targetClasses.add(clazz);
                }
            } else {
                String targetBaseClass = module.getTargetBaseClass();
                Class<?> baseClass = targetBaseClass != null ? findClass(allClasses, targetBaseClass) : null;
                if (targetBaseClass != null && baseClass == null) {
                    AgentLogWriter.warn("[EcaAgent] Base class not found: " + targetBaseClass);
                }

                for (Class<?> clazz : allClasses) {
                    if (!inst.isModifiableClass(clazz)) {
                        continue;
                    }
                    if (clazz.isInterface() || clazz.isArray() || clazz.isPrimitive()) {
                        continue;
                    }

                    String name = clazz.getName();
                    if (!module.shouldRetransform(name, clazz.getClassLoader())) {
                        continue;
                    }

                    if (baseClass != null && !baseClass.isAssignableFrom(clazz)) {
                        continue;
                    }

                    if (baseClass != null && module.requiresMethodOverrideCheck()) {
                        if (!hasOverriddenMethod(clazz, module.getTargetMethodName(), module.getTargetMethodDescriptor())) {
                            continue;
                        }
                    }

                    targetClasses.add(clazz);
                }
            }

            retransformModuleClasses(inst, module, targetClasses);
        }
    }

    private static void retransformModuleClasses(Instrumentation inst, ITransformModule module, List<Class<?>> targetClasses) {
        AgentLogWriter.info("[EcaAgent] Found " + targetClasses.size() + " classes for module: " + module.getName());

        int successCount = 0;
        int failCount = 0;
        for (Class<?> clazz : targetClasses) {
            try {
                inst.retransformClasses(clazz);
                successCount++;
            } catch (Throwable t) {
                failCount++;
                AgentLogWriter.warn("[EcaAgent] Failed to retransform: " + clazz.getName());
            }
        }

        AgentLogWriter.info("[EcaAgent] Retransformation for " + module.getName() + ": " + successCount + " success, " + failCount + " failed");
    }

    private static Class<?> findPeerAgentClass(Instrumentation inst) {
        ClassLoader localLoader = EcaAgent.class.getClassLoader();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (!"net.eca.agent.EcaAgent".equals(clazz.getName())) {
                continue;
            }
            if (clazz.getClassLoader() != localLoader) {
                return clazz;
            }
        }
        return null;
    }

    // 查找类
    /**
     * Find a class by name from loaded classes.
     * @param allClasses all loaded classes
     * @param className the class name to find
     * @return the class, or null if not found
     */
    private static Class<?> findClass(Class<?>[] allClasses, String className) {
        for (Class<?> clazz : allClasses) {
            if (clazz.getName().equals(className)) {
                return clazz;
            }
        }
        return null;
    }

    // 检查类是否重写了指定方法
    /**
     * Check if a class has overridden a specific method.
     * @param clazz the class to check
     * @param methodName the method name (SRG name)
     * @param methodDesc the method descriptor
     * @return true if the method is overridden
     */
    private static boolean hasOverriddenMethod(Class<?> clazz, String methodName, String methodDesc) {
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    // 简单检查：方法名匹配即可
                    // 更精确的检查需要比较方法描述符
                    if (methodDesc != null) {
                        String desc = getMethodDescriptor(method);
                        if (desc.equals(methodDesc)) {
                            return true;
                        }
                    } else {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            // 某些类可能无法反射，忽略
        }
        return false;
    }

    // 获取方法描述符
    /**
     * Get the method descriptor for a method.
     * @param method the method
     * @return the method descriptor
     */
    private static String getMethodDescriptor(Method method) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> paramType : method.getParameterTypes()) {
            sb.append(getTypeDescriptor(paramType));
        }
        sb.append(")");
        sb.append(getTypeDescriptor(method.getReturnType()));
        return sb.toString();
    }

    // 获取类型描述符
    /**
     * Get the type descriptor for a class.
     * @param type the class
     * @return the type descriptor
     */
    private static String getTypeDescriptor(Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type.isArray()) {
            return "[" + getTypeDescriptor(type.getComponentType());
        }
        return "L" + type.getName().replace('.', '/') + ";";
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
