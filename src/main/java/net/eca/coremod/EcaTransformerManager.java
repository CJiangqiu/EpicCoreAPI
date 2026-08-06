package net.eca.coremod;

import net.eca.agent.AgentLogWriter;
import net.eca.agent.EcaAgent;
import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final Object TERMINAL_TRANSFORM_LOCK = new Object();
    private static final AtomicLong TRANSFORM_EPOCH = new AtomicLong();
    private static final Map<String, Object> HEALTH_TRANSFORM_LOCKS = new ConcurrentHashMap<>();
    private static final Map<String, PendingReceipt> PENDING_RECEIPTS = new ConcurrentHashMap<>();
    private static final Map<String, ConfirmedReceipt> CONFIRMED_RECEIPTS = new ConcurrentHashMap<>();
    private static volatile long terminalTransformGeneration;

    private static final long JVMTI_RECEIPT_GENERATION = -1L;

    private record PendingReceipt(long epoch, long generation) {}
    private record ConfirmedReceipt(long epoch, Backend backend) {}

    public record HealthTransformResult(Backend backend, boolean confirmed) {}

    private EcaTransformerManager() {}

    public static Backend backend() {
        return backend;
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

    public static HealthTransformResult retransformHealthClass(Class<?> clazz, boolean refreshTerminal) {
        if (clazz == null) return new HealthTransformResult(Backend.NONE, false);
        if (EcaConfiguration.getForceCompatibilityModeSafely()) {
            return new HealthTransformResult(Backend.NONE, false);
        }
        String internalName = clazz.getName().replace('.', '/');
        Object lock = HEALTH_TRANSFORM_LOCKS.computeIfAbsent(internalName, ignored -> new Object());
        synchronized (lock) {
            return retransformHealthClassLocked(clazz, internalName, refreshTerminal);
        }
    }

    public static boolean isHealthTransformConfirmed(Class<?> clazz) {
        if (clazz == null) return false;
        String internalName = clazz.getName().replace('.', '/');
        return CONFIRMED_RECEIPTS.containsKey(internalName);
    }

    public static boolean isHealthTransformConfirmed(Class<?> clazz, Backend expectedBackend) {
        if (clazz == null || expectedBackend == null) return false;
        ConfirmedReceipt receipt = CONFIRMED_RECEIPTS.get(clazz.getName().replace('.', '/'));
        return receipt != null && receipt.backend() == expectedBackend;
    }

    public static HealthTransformResult retransformHealthClassWithJvmTi(Class<?> clazz) {
        if (clazz == null || EcaConfiguration.getForceCompatibilityModeSafely()
                || !EcaConfiguration.getDefenceEnableRadicalLogicSafely()) {
            return new HealthTransformResult(Backend.NONE, false);
        }
        String internalName = clazz.getName().replace('.', '/');
        Object lock = HEALTH_TRANSFORM_LOCKS.computeIfAbsent(internalName, ignored -> new Object());
        synchronized (lock) {
            return retransformHealthClassWithJvmTiLocked(clazz, internalName);
        }
    }

    static void invalidateHealthTransformReceipt(String internalName) {
        if (internalName == null) return;
        CONFIRMED_RECEIPTS.remove(internalName.replace('.', '/'));
    }

    private static HealthTransformResult retransformHealthClassLocked(
            Class<?> clazz, String internalName, boolean refreshTerminal) {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst != null && isModifiable(inst, clazz)) {
            long generation = ensureTerminalAgentTransformers(inst, refreshTerminal);
            if (generation > 0L) {
                long epoch = beginReceipt(internalName, generation);
                boolean requested = tryAgentRetransform(clazz);
                boolean confirmed = requested && receiptConfirmed(internalName, epoch);
                endReceipt(internalName, epoch);
                if (confirmed) {
                    backend = Backend.AGENT;
                    return new HealthTransformResult(Backend.AGENT, true);
                }
                AgentLogWriter.info("[EcaTransformerManager] Agent health transform not confirmed for "
                        + clazz.getName());
            }
        }

        if (EcaConfiguration.getDefenceEnableRadicalLogicSafely()) {
            return retransformHealthClassWithJvmTiLocked(clazz, internalName);
        }
        return new HealthTransformResult(Backend.NONE, false);
    }

    private static HealthTransformResult retransformHealthClassWithJvmTiLocked(
            Class<?> clazz, String internalName) {
        long epoch = beginReceipt(internalName, JVMTI_RECEIPT_GENERATION);
        boolean confirmed = retransformInternalNameWithJvmTi(internalName)
                && receiptConfirmed(internalName, epoch);
        if (!confirmed) {
            boolean loadedClassRequested = requestJvmTiHookWithLoadedClass(clazz);
            confirmed = loadedClassRequested && receiptConfirmed(internalName, epoch);
        }
        endReceipt(internalName, epoch);
        if (confirmed) {
            backend = Backend.JVMTI;
            return new HealthTransformResult(Backend.JVMTI, true);
        }
        AgentLogWriter.info("[EcaTransformerManager] JVM TI health transform not confirmed for "
                + clazz.getName());
        return new HealthTransformResult(Backend.NONE, false);
    }

    /* 已加载类缺少早期全局引用时，由 Instrumentation 发起请求，转换仍由 JVM TI hook 完成。 */
    private static boolean requestJvmTiHookWithLoadedClass(Class<?> clazz) {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null || clazz == null || !JvmTiChannel.isAvailable() || !isModifiable(inst, clazz)) {
            return false;
        }
        try {
            inst.retransformClasses(clazz);
            AgentLogWriter.info("[EcaTransformerManager] Requested JVM TI hook for loaded class "
                    + clazz.getName());
            return true;
        } catch (Throwable t) {
            AgentLogWriter.info("[EcaTransformerManager] Loaded-class JVM TI request failed for "
                    + clazz.getName() + ": " + t.getMessage());
            return false;
        }
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

    public static boolean retransformLoadedInternalNames(Set<String> internalNames) {
        if (internalNames == null || internalNames.isEmpty()) return false;
        if (backend == Backend.JVMTI) {
            return retransformInternalNamesWithJvmTi(internalNames);
        }

        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst != null) {
            List<Class<?>> targets = new ArrayList<>();
            try {
                for (Class<?> clazz : inst.getAllLoadedClasses()) {
                    if (!inst.isModifiableClass(clazz)) continue;
                    if (internalNames.contains(clazz.getName().replace('.', '/'))) {
                        targets.add(clazz);
                    }
                }
            } catch (Throwable t) {
                AgentLogWriter.info("[EcaTransformerManager] Agent target enumeration failed: "
                        + t.getMessage());
            }
            if (retransformClassesWithAgent(inst, targets)) {
                backend = Backend.AGENT;
                return true;
            }
        }

        if (EcaConfiguration.getDefenceEnableRadicalLogicSafely()) {
            if (retransformInternalNamesWithJvmTi(internalNames)) {
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

    private static boolean isModifiable(Instrumentation inst, Class<?> clazz) {
        try {
            return inst.isModifiableClass(clazz);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long ensureTerminalAgentTransformers(Instrumentation inst, boolean refresh) {
        synchronized (TERMINAL_TRANSFORM_LOCK) {
            if (terminalTransformGeneration > 0L && !refresh) return terminalTransformGeneration;
            long generation = terminalTransformGeneration + 1L;
            try {
                inst.addTransformer(new ClassFileTransformer() {
                    @Override
                    public byte[] transform(ClassLoader loader, String name, Class<?> beingRedefined,
                                            ProtectionDomain domain, byte[] bytes) {
                        return EcaClassTransformer.transformHealthTail(name, bytes);
                    }
                }, true);
                inst.addTransformer(new ClassFileTransformer() {
                    @Override
                    public byte[] transform(ClassLoader loader, String name, Class<?> beingRedefined,
                                            ProtectionDomain domain, byte[] bytes) {
                        confirmReceipt(name, bytes, generation);
                        return null;
                    }
                }, true);
                terminalTransformGeneration = generation;
                AgentLogWriter.info("[EcaTransformerManager] Registered terminal health transformers generation="
                        + generation);
                return generation;
            } catch (Throwable t) {
                AgentLogWriter.info("[EcaTransformerManager] Terminal health transformer registration failed: "
                        + t.getMessage());
                return 0L;
            }
        }
    }

    private static long beginReceipt(String internalName, long generation) {
        long epoch = TRANSFORM_EPOCH.incrementAndGet();
        PENDING_RECEIPTS.put(internalName, new PendingReceipt(epoch, generation));
        CONFIRMED_RECEIPTS.remove(internalName);
        return epoch;
    }

    private static void confirmReceipt(String internalName, byte[] bytes, long generation) {
        if (internalName == null || bytes == null) return;
        String normalized = internalName.replace('.', '/');
        PendingReceipt pending = PENDING_RECEIPTS.get(normalized);
        if (pending == null || pending.generation() != generation) return;
        if (EcaClassTransformer.verifyHealthTail(normalized, bytes)) {
            Backend receiptBackend = generation == JVMTI_RECEIPT_GENERATION ? Backend.JVMTI : Backend.AGENT;
            CONFIRMED_RECEIPTS.put(normalized, new ConfirmedReceipt(pending.epoch(), receiptBackend));
        }
    }

    private static byte[] confirmJvmTiReceipt(String internalName, byte[] bytes) {
        confirmReceipt(internalName, bytes, JVMTI_RECEIPT_GENERATION);
        return null;
    }

    private static boolean receiptConfirmed(String internalName, long epoch) {
        ConfirmedReceipt receipt = CONFIRMED_RECEIPTS.get(internalName);
        return receipt != null && receipt.epoch() == epoch;
    }

    private static void endReceipt(String internalName, long epoch) {
        PENDING_RECEIPTS.computeIfPresent(internalName,
                (ignored, pending) -> pending.epoch() == epoch ? null : pending);
    }

    private static boolean retransformClassesWithAgent(Instrumentation inst, List<Class<?>> classes) {
        if (inst == null || classes == null || classes.isEmpty()) return false;
        int successCount = 0;
        int batchSize = 32;
        for (int start = 0; start < classes.size(); start += batchSize) {
            int end = Math.min(start + batchSize, classes.size());
            Class<?>[] batch = classes.subList(start, end).toArray(new Class<?>[0]);
            try {
                inst.retransformClasses(batch);
                successCount += batch.length;
            } catch (Throwable batchFailure) {
                for (Class<?> clazz : batch) {
                    try {
                        inst.retransformClasses(clazz);
                        successCount++;
                    } catch (Throwable classFailure) {
                        AgentLogWriter.info("[EcaTransformerManager] Agent retransform failed for "
                                + clazz.getName() + ": " + classFailure.getMessage());
                    }
                }
            }
        }
        if (successCount > 0) {
            AgentLogWriter.info("[EcaTransformerManager] Retransformed " + successCount
                    + " selected mod classes via agent");
        }
        return successCount > 0;
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

    private static boolean retransformInternalNamesWithJvmTi(Set<String> internalNames) {
        try {
            activateJvmTiIfNeeded();
            if (!JvmTiChannel.isAvailable()) return false;
            EcaClassTransformer.ensureWhitelistLoaded();
            return JvmTiChannel.retransformLoadedClasses(
                    info -> internalNames.contains(info.internalName()));
        } catch (Throwable t) {
            AgentLogWriter.info("[EcaTransformerManager] JVMTI selected-mod retransform failed: "
                    + t.getMessage());
            return false;
        }
    }

    private static void ensureJvmTiTransformsRegistered() {
        if (jvmTiTransformRegistered) return;
        jvmTiTransformRegistered = true;
        JvmTiChannel.addTransformFunction(EcaClassTransformer::transformStatic);
        RuntimeBytecodeProvider.registerJvmTiCapture();
        JvmTiChannel.addTransformFunction(EcaTransformerManager::confirmJvmTiReceipt);
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
