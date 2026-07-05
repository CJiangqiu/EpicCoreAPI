package net.eca.coremod;

import net.eca.agent.AgentLogWriter;
import net.eca.agent.EcaAgent;
import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;

import java.lang.instrument.Instrumentation;
import java.util.function.Consumer;

public final class EcaTransformerManager {

    public enum Backend {
        AGENT,
        JVMTI,
        NONE
    }

    private static volatile Backend backend = Backend.NONE;
    private static volatile boolean jvmTiTransformRegistered;
    private static volatile boolean allFailedLogged;

    private EcaTransformerManager() {}

    public static Backend backend() {
        return backend;
    }

    public static void registerClassTransformer() {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (EcaClassTransformer.registerWithInstrumentation(inst)) {
            backend = Backend.AGENT;
        }
    }

    public static void activateJvmTiIfNeeded() {
        if (!EcaConfiguration.getDefenceEnableRadicalLogicSafely()) return;
        try {
            JvmTiChannel.prepare();
            ensureJvmTiTransformsRegistered();
            JvmTiChannel.activate();
        } catch (Throwable t) {
            AgentLogWriter.info("[EcaTransformerManager] JVMTI activation failed: " + t.getMessage());
        }
    }

    public static boolean applyLoadCompleteTransforms() {
        boolean agentOk = tryAgentLoadComplete();
        if (agentOk) {
            backend = Backend.AGENT;
            return true;
        }

        if (EcaConfiguration.getDefenceEnableRadicalLogicSafely()) {
            AgentLogWriter.info("[EcaTransformerManager] Agent transform verification failed, trying JVMTI");
            boolean jvmTiOk = tryJvmTiLoadComplete();
            if (jvmTiOk) {
                backend = Backend.JVMTI;
                return true;
            }
            logAllFailed();
        }

        backend = Backend.NONE;
        return false;
    }

    public static boolean retransformClass(Class<?> clazz) {
        if (clazz == null) return false;
        String internalName = clazz.getName().replace('.', '/');
        if (backend == Backend.JVMTI) {
            return retransformInternalNameWithJvmTi(internalName);
        }

        if (tryAgentRetransform(clazz)) {
            backend = Backend.AGENT;
            return true;
        }

        if (EcaConfiguration.getDefenceEnableRadicalLogicSafely()) {
            AgentLogWriter.info("[EcaTransformerManager] Agent retransform failed for "
                    + clazz.getName() + ", trying JVMTI");
            if (retransformInternalNameWithJvmTi(internalName)) {
                backend = Backend.JVMTI;
                return true;
            }
            logAllFailed();
        }
        return false;
    }

    public static boolean retransformInternalName(String internalName) {
        if (internalName == null || internalName.isEmpty()) return false;
        if (backend == Backend.JVMTI) {
            return retransformInternalNameWithJvmTi(internalName);
        }
        Class<?> owner = loadClass(internalName);
        if (owner != null && tryAgentRetransform(owner)) {
            backend = Backend.AGENT;
            return true;
        }
        if (EcaConfiguration.getDefenceEnableRadicalLogicSafely()) {
            if (retransformInternalNameWithJvmTi(internalName)) {
                backend = Backend.JVMTI;
                return true;
            }
            logAllFailed();
        }
        return false;
    }

    public static boolean forEachLoadedClass(Consumer<Class<?>> consumer) {
        if (consumer == null) return false;
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) return false;
        try {
            for (Class<?> clazz : inst.getAllLoadedClasses()) {
                consumer.accept(clazz);
            }
            return true;
        } catch (Throwable t) {
            AgentLogWriter.info("[EcaTransformerManager] Agent loaded-class enumeration failed: "
                    + t.getMessage());
            return false;
        }
    }

    public static boolean forEachLoadedInternalName(Consumer<JvmTiChannel.LoadedClassInfo> consumer) {
        if (consumer == null) return false;
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst != null) {
            try {
                for (Class<?> clazz : inst.getAllLoadedClasses()) {
                    String internalName = clazz.getName().replace('.', '/');
                    boolean modifiable = inst.isModifiableClass(clazz);
                    int entityType = classifyEntity(clazz);
                    consumer.accept(new JvmTiChannel.LoadedClassInfo(internalName, modifiable,
                            entityType == 1, entityType == 2));
                }
                return true;
            } catch (Throwable t) {
                AgentLogWriter.info("[EcaTransformerManager] Agent internal-name enumeration failed: "
                        + t.getMessage());
            }
        }
        if (!EcaConfiguration.getDefenceEnableRadicalLogicSafely()) return false;
        activateJvmTiIfNeeded();
        return JvmTiChannel.forEachLoadedClass(consumer::accept);
    }

    private static boolean tryAgentLoadComplete() {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) return false;
        try {
            return EcaClassTransformer.retransformLoadedClassesWithInstrumentation(inst);
        } catch (Throwable t) {
            AgentLogWriter.info("[EcaTransformerManager] Agent load-complete transform failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean tryJvmTiLoadComplete() {
        try {
            activateJvmTiIfNeeded();
            if (!JvmTiChannel.isAvailable()) return false;
            EcaClassTransformer.ensureWhitelistLoaded();
            return JvmTiChannel.retransformLoadedClasses(EcaClassTransformer::isJvmTiLoadCompleteTarget);
        } catch (Throwable t) {
            AgentLogWriter.info("[EcaTransformerManager] JVMTI load-complete transform failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean tryAgentRetransform(Class<?> clazz) {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null || clazz == null) return false;
        try {
            if (!inst.isModifiableClass(clazz)) return false;
            inst.retransformClasses(clazz);
            return true;
        } catch (Throwable t) {
            AgentLogWriter.info("[EcaTransformerManager] Agent retransform failed for "
                    + clazz.getName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean retransformInternalNameWithJvmTi(String internalName) {
        try {
            activateJvmTiIfNeeded();
            if (!JvmTiChannel.isAvailable()) return false;
            EcaClassTransformer.ensureWhitelistLoaded();
            return JvmTiChannel.retransformInternalName(internalName);
        } catch (Throwable t) {
            AgentLogWriter.info("[EcaTransformerManager] JVMTI retransform failed for "
                    + internalName + ": " + t.getMessage());
            return false;
        }
    }

    private static void ensureJvmTiTransformsRegistered() {
        if (jvmTiTransformRegistered) return;
        jvmTiTransformRegistered = true;
        JvmTiChannel.addTransformFunction(EcaClassTransformer::transformStatic);
        RuntimeBytecodeProvider.registerJvmTiCapture();
    }

    private static Class<?> loadClass(String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false,
                    Thread.currentThread().getContextClassLoader());
        } catch (Throwable ignored) {
            try {
                return Class.forName(internalName.replace('/', '.'));
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private static int classifyEntity(Class<?> clazz) {
        try {
            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                String name = c.getName();
                if ("net.minecraft.world.entity.LivingEntity".equals(name)) return 1;
                if ("net.minecraft.world.entity.Entity".equals(name)) return 2;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static void logAllFailed() {
        if (allFailedLogged) return;
        allFailedLogged = true;
        try {
            EcaLogger.info("WARNNING!ECA Agent and JVMTI all failed!!!");
        } catch (Throwable ignored) {
            AgentLogWriter.info("WARNNING!ECA Agent and JVMTI all failed!!!");
        }
    }
}
