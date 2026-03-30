package net.eca.agent.transform;

import net.eca.agent.AgentLogWriter;
import net.eca.agent.EcaAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies registered transform modules to already-loaded classes.
 * Handles retransformation dispatch, peer classloader fallback, and batch processing.
 */
public final class TransformApplier {

    private static final int RETRANSFORM_BATCH_SIZE = 50;

    /**
     * Apply transform modules with optional filtering and peer fallback.
     * @param moduleNameFilter null for all modules, or a specific module name
     */
    public static void apply(String moduleNameFilter) {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            AgentLogWriter.warn("[TransformApplier] Instrumentation not available");
            return;
        }

        EcaTransformer transformer = EcaTransformer.getInstance();
        if (!transformer.getModules().isEmpty()) {
            applyLocal(moduleNameFilter);
            return;
        }

        Class<?> peer = findPeerClass(inst);
        if (peer != null) {
            try {
                peer.getMethod("applyLocal", String.class).invoke(null, moduleNameFilter);
                return;
            } catch (ReflectiveOperationException e) {
                AgentLogWriter.warn("[TransformApplier] Failed to invoke peer: " + e.getMessage());
            }
        }

        AgentLogWriter.warn("[TransformApplier] No registered transform modules found");
    }

    // 本地应用（也用于 peer 反射调用）
    /**
     * Apply transform modules using local instrumentation and transformer.
     * @param moduleNameFilter null for all modules, or a specific module name
     */
    public static void applyLocal(String moduleNameFilter) {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            AgentLogWriter.warn("[TransformApplier] Instrumentation not available");
            return;
        }

        EcaTransformer transformer = EcaTransformer.getInstance();
        if (transformer.getModules().isEmpty()) {
            AgentLogWriter.warn("[TransformApplier] No registered transform modules found");
            return;
        }

        applyInternal(inst, transformer, moduleNameFilter);
    }

    private static void applyInternal(Instrumentation inst, EcaTransformer transformer, String moduleNameFilter) {
        try {
            inst.removeTransformer(transformer);
        } catch (Throwable ignored) {
        }
        inst.addTransformer(transformer, true);
        AgentLogWriter.info("[TransformApplier] Rebound EcaTransformer");

        Class<?>[] allClasses = inst.getAllLoadedClasses();
        AgentLogWriter.info("[TransformApplier] Scanning " + allClasses.length + " loaded classes...");

        // 构建类名 -> Class 映射表，将 findClass 的 O(n) 查找降为 O(1)
        Map<String, Class<?>> classMap = new HashMap<>(allClasses.length * 2);
        for (Class<?> clazz : allClasses) {
            classMap.put(clazz.getName(), clazz);
        }

        // 按模块类型分组：显式目标类 vs 基类扫描
        List<ITransformModule> explicitModules = new ArrayList<>();
        List<ITransformModule> scanModules = new ArrayList<>();
        Map<ITransformModule, Class<?>> moduleBaseClasses = new HashMap<>();

        for (ITransformModule module : transformer.getModules()) {
            if (moduleNameFilter != null && !moduleNameFilter.equals(module.getName())) {
                continue;
            }
            List<String> targetClassNames = module.getTargetClassNames();
            if (targetClassNames != null && !targetClassNames.isEmpty()) {
                explicitModules.add(module);
            } else {
                scanModules.add(module);
                String targetBaseClass = module.getTargetBaseClass();
                if (targetBaseClass != null) {
                    Class<?> baseClass = classMap.get(targetBaseClass);
                    if (baseClass == null) {
                        AgentLogWriter.warn("[TransformApplier] Base class not found: " + targetBaseClass);
                    } else {
                        moduleBaseClasses.put(module, baseClass);
                    }
                }
            }
        }

        // 阶段1：显式目标模块，通过 HashMap O(1) 查找
        for (ITransformModule module : explicitModules) {
            List<Class<?>> targetClasses = new ArrayList<>();
            for (String className : module.getTargetClassNames()) {
                Class<?> clazz = classMap.get(className);
                if (clazz == null) {
                    AgentLogWriter.warn("[TransformApplier] Target class not found: " + className);
                    continue;
                }
                if (!module.shouldRetransform(className, clazz.getClassLoader())) {
                    continue;
                }
                if (!inst.isModifiableClass(clazz)) {
                    AgentLogWriter.warn("[TransformApplier] Target class not modifiable: " + className);
                    continue;
                }
                targetClasses.add(clazz);
            }
            retransformBatch(inst, module, targetClasses);
        }

        // 阶段2：基类扫描模块，单次遍历 allClasses 同时为所有模块收集目标
        if (!scanModules.isEmpty()) {
            Map<ITransformModule, List<Class<?>>> scanResults = new HashMap<>();
            for (ITransformModule module : scanModules) {
                scanResults.put(module, new ArrayList<>());
            }

            for (Class<?> clazz : allClasses) {
                if (!inst.isModifiableClass(clazz)) continue;
                if (clazz.isInterface() || clazz.isArray() || clazz.isPrimitive()) continue;

                String name = clazz.getName();

                for (ITransformModule module : scanModules) {
                    if (!module.shouldRetransform(name, clazz.getClassLoader())) continue;

                    Class<?> baseClass = moduleBaseClasses.get(module);
                    if (baseClass != null && !baseClass.isAssignableFrom(clazz)) continue;

                    if (baseClass != null && module.requiresMethodOverrideCheck()) {
                        if (!hasOverriddenMethod(clazz, module.getTargetMethodName(), module.getTargetMethodDescriptor())) {
                            continue;
                        }
                    }

                    scanResults.get(module).add(clazz);
                }
            }

            for (ITransformModule module : scanModules) {
                retransformBatch(inst, module, scanResults.get(module));
            }
        }
    }

    // 批量 retransform，失败时回退到逐个重转
    private static void retransformBatch(Instrumentation inst, ITransformModule module, List<Class<?>> targetClasses) {
        AgentLogWriter.info("[TransformApplier] Found " + targetClasses.size() + " classes for module: " + module.getName());

        if (targetClasses.isEmpty()) return;

        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < targetClasses.size(); i += RETRANSFORM_BATCH_SIZE) {
            int end = Math.min(i + RETRANSFORM_BATCH_SIZE, targetClasses.size());
            List<Class<?>> batch = targetClasses.subList(i, end);
            try {
                inst.retransformClasses(batch.toArray(new Class<?>[0]));
                successCount += batch.size();
            } catch (Throwable t) {
                for (Class<?> clazz : batch) {
                    try {
                        inst.retransformClasses(clazz);
                        successCount++;
                    } catch (Throwable t2) {
                        failCount++;
                        AgentLogWriter.warn("[TransformApplier] Failed to retransform: " + clazz.getName());
                    }
                }
            }
        }

        AgentLogWriter.info("[TransformApplier] Retransformation for " + module.getName() + ": " + successCount + " success, " + failCount + " failed");
    }

    private static Class<?> findPeerClass(Instrumentation inst) {
        ClassLoader localLoader = TransformApplier.class.getClassLoader();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (!"net.eca.agent.transform.TransformApplier".equals(clazz.getName())) {
                continue;
            }
            if (clazz.getClassLoader() != localLoader) {
                return clazz;
            }
        }
        return null;
    }

    private static boolean hasOverriddenMethod(Class<?> clazz, String methodName, String methodDesc) {
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
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
        }
        return false;
    }

    private static String getMethodDescriptor(Method method) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> paramType : method.getParameterTypes()) {
            sb.append(getTypeDescriptor(paramType));
        }
        sb.append(")");
        sb.append(getTypeDescriptor(method.getReturnType()));
        return sb.toString();
    }

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

    private TransformApplier() {}
}
