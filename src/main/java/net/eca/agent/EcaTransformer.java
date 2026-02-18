package net.eca.agent;

import net.eca.agent.transform.ITransformModule;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// ECA主字节码转换器
/**
 * Main bytecode transformer for ECA.
 * Manages multiple transform modules and delegates transformation to them.
 */
public class EcaTransformer implements ClassFileTransformer {

    private static final EcaTransformer INSTANCE = new EcaTransformer();

    private final List<ITransformModule> modules = new CopyOnWriteArrayList<>();
    private final Set<Module> accessGrantedModules = ConcurrentHashMap.newKeySet();

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
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
    ) {
        byte[] result = transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);

        // 转换成功且目标类在命名模块中时，确保模块访问权限
        if (result != null && module != null && module.isNamed()) {
            ensureModuleAccess(module);
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
        for (ITransformModule m : modules) {
            try {
                if (m.shouldTransform(className, loader)) {
                    byte[] result = m.transform(className, currentBuffer);
                    if (result != null) {
                        currentBuffer = result;
                        transformed = true;
                    }
                }
            } catch (Throwable t) {
                AgentLogWriter.error("[EcaTransformer] Module " + m.getName() + " failed to transform: " + className, t);
            }
        }

        return transformed ? currentBuffer : null;
    }

    /**
     * Ensure the target module can access ECA classes referenced by injected bytecode.
     * Adds reads and exports via Instrumentation.redefineModule().
     */
    private void ensureModuleAccess(Module targetModule) {
        if (accessGrantedModules.contains(targetModule)) {
            return;
        }

        try {
            ModuleLayer layer = targetModule.getLayer();
            if (layer == null) {
                return;
            }

            Module ecaModule = layer.findModule("eca").orElse(null);
            if (ecaModule == null || targetModule == ecaModule) {
                accessGrantedModules.add(targetModule);
                return;
            }

            Instrumentation inst = EcaAgent.getInstrumentation();
            if (inst == null) {
                return;
            }

            // 添加 reads：目标模块 -> eca 模块
            if (!targetModule.canRead(ecaModule)) {
                inst.redefineModule(
                    targetModule,
                    Set.of(ecaModule),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptySet(),
                    Collections.emptyMap()
                );
            }

            // 导出 eca 的包到目标模块
            if (ecaModule.isNamed()) {
                Map<String, Set<Module>> extraExports = new HashMap<>();
                Set<Module> targetSet = Set.of(targetModule);
                for (String pkg : ecaModule.getPackages()) {
                    if (!ecaModule.isExported(pkg, targetModule)) {
                        extraExports.put(pkg, targetSet);
                    }
                }
                if (!extraExports.isEmpty()) {
                    inst.redefineModule(
                        ecaModule,
                        Collections.emptySet(),
                        extraExports,
                        Collections.emptyMap(),
                        Collections.emptySet(),
                        Collections.emptyMap()
                    );
                }
            }

            accessGrantedModules.add(targetModule);
            AgentLogWriter.info("[EcaTransformer] Granted module access: " + targetModule.getName() + " -> eca");

        } catch (Throwable t) {
            AgentLogWriter.warn("[EcaTransformer] Failed to grant module access for " + targetModule.getName() + ": " + t.getMessage());
        }
    }

    // 判断是否应该跳过该类（仅跳过 JDK 和 ECA 自身，各模块自行决定其他过滤）
    /**
     * Check if the class should be skipped.
     * Only skips JDK internals and ECA agent classes.
     * Each module handles its own package filtering via shouldTransform().
     * @param className the internal class name
     * @return true if the class should be skipped
     */
    private boolean shouldSkipClass(String className) {
        return className.startsWith("java/") ||
               className.startsWith("javax/") ||
               className.startsWith("sun/") ||
               className.startsWith("jdk/") ||
               className.startsWith("com/sun/") ||
               className.startsWith("net/eca/agent/");
    }

    private EcaTransformer() {}
}
