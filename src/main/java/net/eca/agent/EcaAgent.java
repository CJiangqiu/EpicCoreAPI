package net.eca.agent;

import net.eca.agent.transform.ContainerReplacementTransformer;
import net.eca.agent.transform.ITransformModule;
import net.eca.agent.transform.LivingEntityTransformer;

import java.lang.instrument.Instrumentation;
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

            // 打开必要的模块
            openRequiredModules(args, inst);

            // 获取EcaTransformer并注册模块
            EcaTransformer transformer = EcaTransformer.getInstance();

            // 注册容器替换模块（优先级最高，需要最先执行）
            transformer.registerModule(new ContainerReplacementTransformer());
            AgentLogWriter.info("[EcaAgent] Registered ContainerReplacementTransformer");

            // 注册LivingEntity转换模块
            transformer.registerModule(new LivingEntityTransformer());

            // 设置health分析hook处理器
            LivingEntityTransformer.setHookHandler(
                "net/eca/util/health/HealthAnalysisHook",
                "processGetHealth",
                "(FLnet/minecraft/world/entity/LivingEntity;Ljava/lang/String;)F"
            );

            // 注册transformer（canRetransform = true用于已加载类）
            inst.addTransformer(transformer, true);
            AgentLogWriter.info("[EcaAgent] Registered EcaTransformer");

            // 对已加载的类进行retransform
            retransformLoadedClasses(inst, transformer);

            initialized = true;
            AgentLogWriter.info("[EcaAgent] Agent initialization completed successfully");

        } catch (Throwable t) {
            AgentLogWriter.error("[EcaAgent] Agent initialization failed", t);
            throw new RuntimeException(t);
        }
    }

    // 打开必要的模块
    /**
     * Open required modules for reflection access.
     * @param callerClassName the caller class name
     * @param inst instrumentation instance
     */
    private static void openRequiredModules(String callerClassName, Instrumentation inst) {
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
                    openModule(inst, base, pkg, targetModule);
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
    private static void openModule(Instrumentation inst, Module module, String pkg, Module to) {
        inst.redefineModule(
                module,
                Collections.emptySet(),
                Collections.emptyMap(),
                Collections.singletonMap(pkg, Collections.singleton(to)),
                Collections.emptySet(),
                Collections.emptyMap()
        );
    }

    // 对已加载的类进行retransform
    /**
     * Retransform already loaded classes.
     * @param inst instrumentation instance
     * @param transformer the transformer
     */
    private static void retransformLoadedClasses(Instrumentation inst, EcaTransformer transformer) {
        Class<?>[] allClasses = inst.getAllLoadedClasses();
        AgentLogWriter.info("[EcaAgent] Scanning " + allClasses.length + " loaded classes...");

        for (ITransformModule module : transformer.getModules()) {
            String targetBaseClass = module.getTargetBaseClass();
            if (targetBaseClass == null) {
                continue;
            }

            // 查找目标基类
            Class<?> baseClass = findClass(allClasses, targetBaseClass);
            if (baseClass == null) {
                AgentLogWriter.warn("[EcaAgent] Base class not found: " + targetBaseClass);
                continue;
            }

            // 收集需要retransform的类
            List<Class<?>> targetClasses = new ArrayList<>();

            for (Class<?> clazz : allClasses) {
                if (!inst.isModifiableClass(clazz)) continue;
                if (clazz.isInterface() || clazz.isArray() || clazz.isPrimitive()) continue;

                String name = clazz.getName();

                // 跳过JDK类和ECA类
                if (name.startsWith("java.") || name.startsWith("javax.") ||
                    name.startsWith("sun.") || name.startsWith("jdk.") ||
                    name.startsWith("com.sun.") || name.startsWith("org.objectweb.asm.") ||
                    name.startsWith("net.eca.")) {
                    continue;
                }

                // 检查是否是目标基类的子类
                if (!baseClass.isAssignableFrom(clazz)) {
                    continue;
                }

                // 检查是否需要方法重写检查
                if (module.requiresMethodOverrideCheck()) {
                    if (hasOverriddenMethod(clazz, module.getTargetMethodName(), module.getTargetMethodDescriptor())) {
                        targetClasses.add(clazz);
                    }
                } else {
                    targetClasses.add(clazz);
                }
            }

            AgentLogWriter.info("[EcaAgent] Found " + targetClasses.size() + " classes for module: " + module.getName());

            // Retransform所有目标类
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
