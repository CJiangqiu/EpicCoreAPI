package net.eca.agent;

import net.eca.agent.transform.ITransformModule;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// ECA主字节码转换器
/**
 * Main bytecode transformer for ECA.
 * Manages multiple transform modules and delegates transformation to them.
 */
public class EcaTransformer implements ClassFileTransformer {

    private static final EcaTransformer INSTANCE = new EcaTransformer();

    private final List<ITransformModule> modules = new CopyOnWriteArrayList<>();

    // 获取单例实例
    /**
     * Get the singleton instance.
     * @return the EcaTransformer instance
     */
    public static EcaTransformer getInstance() {
        return INSTANCE;
    }

    // 注册转换模块
    /**
     * Register a transform module.
     * @param module the module to register
     */
    public void registerModule(ITransformModule module) {
        modules.add(module);
        AgentLogWriter.info("[EcaTransformer] Registered module: " + module.getName());
    }

    // 获取所有已注册模块
    /**
     * Get all registered modules.
     * @return list of registered modules
     */
    public List<ITransformModule> getModules() {
        return new ArrayList<>(modules);
    }

    // 根据目标基类获取模块
    /**
     * Get modules that handle a specific target base class.
     * @param targetBaseClass the target base class name
     * @return list of matching modules
     */
    public List<ITransformModule> getModulesByTargetClass(String targetBaseClass) {
        List<ITransformModule> result = new ArrayList<>();
        for (ITransformModule module : modules) {
            if (targetBaseClass.equals(module.getTargetBaseClass())) {
                result.add(module);
            }
        }
        return result;
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
    ) {
        if (className == null) {
            return null;
        }

        // 跳过JDK内部类和常见库类
        if (shouldSkipClass(className)) {
            return null;
        }

        byte[] currentBuffer = classfileBuffer;
        boolean transformed = false;

        // 遍历所有模块进行转换
        for (ITransformModule module : modules) {
            try {
                if (module.shouldTransform(className, loader)) {
                    byte[] result = module.transform(className, currentBuffer);
                    if (result != null) {
                        currentBuffer = result;
                        transformed = true;
                    }
                }
            } catch (Throwable t) {
                AgentLogWriter.error("[EcaTransformer] Module " + module.getName() + " failed to transform: " + className, t);
            }
        }

        return transformed ? currentBuffer : null;
    }

    // 判断是否应该跳过该类
    /**
     * Check if the class should be skipped.
     * @param className the internal class name
     * @return true if the class should be skipped
     */
    private boolean shouldSkipClass(String className) {
        return PackageWhitelist.isProtected(className);
    }

    private EcaTransformer() {}
}
