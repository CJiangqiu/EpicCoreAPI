package net.eca.coremod;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 验证器
 * 在游戏加载完成后验证字节码转换是否生效
 * 如果失败则使用 Agent retransform 恢复
 */
public class AgentVerifier {

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final AtomicBoolean verified = new AtomicBoolean(false);

    //检查间隔（毫秒）
    private static final long CHECK_INTERVAL_MS = 1000;
    //最大等待时间（5分钟）
    private static final long MAX_WAIT_TIME_MS = 5 * 60 * 1000;

    /**
     * 启动验证线程
     */
    public static void startVerification() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        System.out.println("[ECA Verifier] Starting verification thread...");

        ForkJoinPool.commonPool().execute(() -> {
            try {
                boolean gameReady = waitForGameReady();
                if (!gameReady) {
                    System.err.println("[ECA Verifier] Timeout waiting for game load, task terminated");
                    return;
                }
                verifyAndRecover();
            } catch (Throwable t) {
                System.err.println("[ECA Verifier] Verification failed: " + t.getMessage());
            }
        });
    }

    /**
     * 等待游戏加载完成
     * @return true 如果游戏加载完成，false 如果超时
     */
    private static boolean waitForGameReady() throws InterruptedException {
        System.out.println("[ECA Verifier] Waiting for Forge load complete (timeout: 5 minutes)...");

        long startTime = System.currentTimeMillis();
        int checkCount = 0;

        while (!isGameLoaded()) {
            long elapsed = System.currentTimeMillis() - startTime;

            //超时检查
            if (elapsed > MAX_WAIT_TIME_MS) {
                System.err.println("[ECA Verifier] Timeout after " + (elapsed / 1000) + " seconds");
                return false;
            }

            //每30秒打印一次等待状态
            checkCount++;
            if (checkCount % 30 == 0) {
                System.out.println("[ECA Verifier] Still waiting for game load... (" + (elapsed / 1000) + "s)");
            }

            Thread.sleep(CHECK_INTERVAL_MS);
        }

        System.out.println("[ECA Verifier] Forge load complete, waiting 3 seconds for safety...");
        Thread.sleep(3000);
        return true;
    }

    /**
     * 检测 Forge 是否已加载完成
     * 通过反射检查 EcaMod.isLoadComplete()
     */
    private static boolean isGameLoaded() {
        try {
            Class<?> ecaModClass = Class.forName("net.eca.EcaMod");
            Method isLoadComplete = ecaModClass.getMethod("isLoadComplete");
            Boolean complete = (Boolean) isLoadComplete.invoke(null);
            return complete != null && complete;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 验证转换是否生效，失败则恢复
     */
    private static void verifyAndRecover() {
        System.out.println("[ECA Verifier] Verifying bytecode transformation for all modules...");

        try {
            // 获取所有需要验证的模块信息
            List<ModuleVerifyInfo> failedModules = new ArrayList<>();

            // 验证 LivingEntityTransformer
            ModuleVerifyInfo livingEntityInfo = verifyModule(
                "LivingEntityTransformer",
                "net.eca.agent.transform.LivingEntityTransformer",
                "__ECA_LIVING_ENTITY_MARK__"
            );
            if (livingEntityInfo != null && !livingEntityInfo.verified) {
                failedModules.add(livingEntityInfo);
            }

            // 验证 ContainerReplacementTransformer
            ModuleVerifyInfo containerInfo = verifyModule(
                "ContainerReplacementTransformer",
                "net.eca.agent.transform.ContainerReplacementTransformer",
                "__ECA_CONTAINER_MARK__"
            );
            if (containerInfo != null && !containerInfo.verified) {
                failedModules.add(containerInfo);
            }

            // 验证 AllReturnTransformer（只有在转换过类时才验证）
            ModuleVerifyInfo allReturnInfo = verifyModule(
                "AllReturnTransformer",
                "net.eca.agent.transform.AllReturnTransformer",
                "__ECA_ALLRETURN_MARK__"
            );
            if (allReturnInfo != null && !allReturnInfo.verified) {
                failedModules.add(allReturnInfo);
            }

            if (failedModules.isEmpty()) {
                System.out.println("[ECA Verifier] All modules verified successfully");
                verified.set(true);
                return;
            }

            System.out.println("[ECA Verifier] " + failedModules.size() + " module(s) failed verification, attempting recovery...");

            // 获取 Instrumentation 进行恢复
            Instrumentation inst = getInstrumentation();
            if (inst == null) {
                System.err.println("[ECA Verifier] No Instrumentation available for recovery");
                return;
            }

            // 对每个失败的模块进行恢复
            for (ModuleVerifyInfo info : failedModules) {
                recoverModule(inst, info);
            }

            verified.set(true);
            System.out.println("[ECA Verifier] Recovery completed");

        } catch (Throwable t) {
            System.err.println("[ECA Verifier] Verification/recovery failed: " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * 模块验证信息
     */
    private static class ModuleVerifyInfo {
        final String moduleName;
        final String transformerClassName;
        final String markFieldName;
        final String firstTransformedClass;
        final boolean verified;

        ModuleVerifyInfo(String moduleName, String transformerClassName, String markFieldName,
                         String firstTransformedClass, boolean verified) {
            this.moduleName = moduleName;
            this.transformerClassName = transformerClassName;
            this.markFieldName = markFieldName;
            this.firstTransformedClass = firstTransformedClass;
            this.verified = verified;
        }
    }

    /**
     * 验证单个模块
     * @return ModuleVerifyInfo 如果模块需要验证，null 如果模块不需要验证（如AllReturn未转换任何类）
     */
    private static ModuleVerifyInfo verifyModule(String moduleName, String transformerClassName, String markFieldName) {
        try {
            Class<?> transformerClass = Class.forName(transformerClassName);

            // 获取第一个被转换的类名
            Method getFirstTransformed = transformerClass.getMethod("getFirstTransformed");
            String firstTransformedClass = (String) getFirstTransformed.invoke(null);

            // 如果没有转换过任何类，不需要验证
            if (firstTransformedClass == null) {
                System.out.println("[ECA Verifier] " + moduleName + " - no classes transformed, skipping verification");
                return null;
            }

            // 检查标签字段是否存在
            boolean markExists = checkMarkField(firstTransformedClass, markFieldName);

            if (markExists) {
                System.out.println("[ECA Verifier] " + moduleName + " - verified (mark found in " + firstTransformedClass + ")");
            } else {
                System.out.println("[ECA Verifier] " + moduleName + " - FAILED (mark not found in " + firstTransformedClass + ")");
            }

            return new ModuleVerifyInfo(moduleName, transformerClassName, markFieldName, firstTransformedClass, markExists);

        } catch (ClassNotFoundException e) {
            System.out.println("[ECA Verifier] " + moduleName + " - transformer class not found, skipping");
            return null;
        } catch (Throwable t) {
            System.err.println("[ECA Verifier] " + moduleName + " - verification error: " + t.getMessage());
            return null;
        }
    }

    /**
     * 检查类中是否存在标签字段
     */
    private static boolean checkMarkField(String className, String fieldName) {
        try {
            Class<?> clazz = Class.forName(className);
            Field markField = clazz.getDeclaredField(fieldName);
            markField.setAccessible(true);
            Object value = markField.get(null);
            return Boolean.TRUE.equals(value);
        } catch (NoSuchFieldException e) {
            // 标签字段不存在，说明转换被拦截
            return false;
        } catch (Throwable t) {
            // 其他错误
            return false;
        }
    }

    /**
     * 恢复单个模块的转换
     */
    private static void recoverModule(Instrumentation inst, ModuleVerifyInfo info) {
        System.out.println("[ECA Verifier] Recovering " + info.moduleName + "...");

        try {
            // 根据模块类型决定恢复策略
            switch (info.moduleName) {
                case "LivingEntityTransformer":
                    retransformLivingEntityClasses(inst);
                    break;
                case "ContainerReplacementTransformer":
                    retransformContainerClasses(inst);
                    break;
                case "AllReturnTransformer":
                    retransformAllReturnClasses(inst);
                    break;
                default:
                    System.err.println("[ECA Verifier] Unknown module: " + info.moduleName);
            }
        } catch (Throwable t) {
            System.err.println("[ECA Verifier] Failed to recover " + info.moduleName + ": " + t.getMessage());
        }
    }

    /**
     * 获取 Instrumentation 实例
     */
    private static Instrumentation getInstrumentation() {
        try {
            Class<?> agentClass = Class.forName("net.eca.agent.EcaAgent");
            Method getInst = agentClass.getMethod("getInstrumentation");
            return (Instrumentation) getInst.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 重新 retransform LivingEntity 及其子类
     */
    private static void retransformLivingEntityClasses(Instrumentation inst) {
        try {
            Class<?> livingEntityClass = Class.forName("net.minecraft.world.entity.LivingEntity");

            int successCount = 0;
            int failCount = 0;

            for (Class<?> clazz : inst.getAllLoadedClasses()) {
                if (!inst.isModifiableClass(clazz)) continue;
                if (clazz.isInterface() || clazz.isArray() || clazz.isPrimitive()) continue;

                String name = clazz.getName();

                // 跳过JDK和ECA自己的类
                if (shouldSkipClass(name)) {
                    continue;
                }

                // 只处理 LivingEntity 的子类
                if (!livingEntityClass.isAssignableFrom(clazz)) {
                    continue;
                }

                try {
                    inst.retransformClasses(clazz);
                    successCount++;
                } catch (Throwable t) {
                    failCount++;
                }
            }

            System.out.println("[ECA Verifier] LivingEntity: retransformed " + successCount + " classes, " + failCount + " failed");

        } catch (Throwable t) {
            System.err.println("[ECA Verifier] Failed to retransform LivingEntity classes: " + t.getMessage());
        }
    }

    /**
     * 重新 retransform 容器相关类
     */
    private static void retransformContainerClasses(Instrumentation inst) {
        String[] targetClasses = {
            "net.minecraft.world.level.entity.EntityTickList",
            "net.minecraft.world.level.entity.EntityLookup",
            "net.minecraft.util.ClassInstanceMultiMap",
            "net.minecraft.server.level.ChunkMap"
        };

        int successCount = 0;
        int failCount = 0;

        for (String className : targetClasses) {
            try {
                Class<?> clazz = Class.forName(className);
                if (inst.isModifiableClass(clazz)) {
                    inst.retransformClasses(clazz);
                    successCount++;
                    System.out.println("[ECA Verifier] Container: retransformed " + className);
                }
            } catch (ClassNotFoundException e) {
                System.out.println("[ECA Verifier] Container: class not found - " + className);
            } catch (Throwable t) {
                failCount++;
                System.err.println("[ECA Verifier] Container: failed to retransform " + className + " - " + t.getMessage());
            }
        }

        System.out.println("[ECA Verifier] Container: retransformed " + successCount + " classes, " + failCount + " failed");
    }

    /**
     * 重新 retransform AllReturn 相关类
     * 需要 retransform 所有已转换的类，因为 AllReturn 没有动态转换机制
     */
    @SuppressWarnings("unchecked")
    private static void retransformAllReturnClasses(Instrumentation inst) {
        try {
            // 通过反射获取 ReturnToggle.getActiveClassNames()
            Class<?> returnToggleClass = Class.forName("net.eca.agent.ReturnToggle");
            Method getActiveClassNames = returnToggleClass.getMethod("getActiveClassNames");
            Set<String> activeClassNames = (Set<String>) getActiveClassNames.invoke(null);

            if (activeClassNames == null || activeClassNames.isEmpty()) {
                System.out.println("[ECA Verifier] AllReturn: no active classes to retransform");
                return;
            }

            int successCount = 0;
            int failCount = 0;

            for (String internalClassName : activeClassNames) {
                try {
                    // 将内部格式 (com/example/Foo) 转换为二进制格式 (com.example.Foo)
                    String binaryName = internalClassName.replace('/', '.');
                    Class<?> clazz = Class.forName(binaryName);

                    if (inst.isModifiableClass(clazz)) {
                        inst.retransformClasses(clazz);
                        successCount++;
                    }
                } catch (ClassNotFoundException e) {
                    // 类可能已经被卸载，跳过
                    failCount++;
                } catch (Throwable t) {
                    failCount++;
                }
            }

            System.out.println("[ECA Verifier] AllReturn: retransformed " + successCount + " classes, " + failCount + " failed");

        } catch (Throwable t) {
            System.err.println("[ECA Verifier] AllReturn: failed to get active classes - " + t.getMessage());
        }
    }

    /**
     * 判断是否应该跳过该类
     */
    private static boolean shouldSkipClass(String className) {
        return className.startsWith("java.") ||
               className.startsWith("javax.") ||
               className.startsWith("sun.") ||
               className.startsWith("jdk.") ||
               className.startsWith("com.sun.") ||
               className.startsWith("net.eca.");
    }

    /**
     * 检查是否已验证通过
     */
    public static boolean isVerified() {
        return verified.get();
    }

    /**
     * 检查是否已启动验证
     */
    public static boolean hasStarted() {
        return started.get();
    }

    private AgentVerifier() {}
}
