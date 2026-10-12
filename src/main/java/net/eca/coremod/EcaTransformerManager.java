package net.eca.coremod;

import net.eca.coremod.EarlyLogWriter;
import net.eca.agent.AgentLoader;
import net.eca.agent.EcaAgent;
import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class EcaTransformerManager {

    private static final String TRANSFORMATION_BACKEND_KEY = "net.eca.transform.backend";

    public enum Backend {
        AGENT,
        COREMOD,
        JVMTI,
        NONE
    }

    private static volatile Backend backend = Backend.NONE;
    private static volatile boolean allFailedLogged;
    private static final Object TERMINAL_TRANSFORM_LOCK = new Object();
    private static final AtomicLong TRANSFORM_EPOCH = new AtomicLong();
    private static final Map<String, Object> HEALTH_TRANSFORM_LOCKS = new ConcurrentHashMap<>();
    private static final Map<String, PendingReceipt> PENDING_RECEIPTS = new ConcurrentHashMap<>();
    private static final Map<String, ConfirmedReceipt> CONFIRMED_RECEIPTS = new ConcurrentHashMap<>();
    private static volatile long terminalTransformGeneration;
    private static final Object NATIVE_LOCK = new Object();
    private static volatile boolean nativeActive;
    private static final ThreadLocal<NativeRequest> NATIVE_REQUEST = new ThreadLocal<>();
    private static final Set<Class<?>> NATIVE_HEALTH_CONFIRMED = ConcurrentHashMap.newKeySet();

    private record NativeRequest(Set<Class<?>> targets, Map<Class<?>, byte[]> outputs,
                                 Set<Class<?>> healthConfirmed, boolean health) {}

    private record PendingReceipt(long epoch, long generation) {}
    private record ConfirmedReceipt(long epoch, Backend backend) {}

    public record HealthTransformResult(Backend backend, boolean confirmed) {}

    public record LoadedClassInfo(String internalName, boolean modifiable,
                                  boolean livingEntity, boolean entityOnly) {}

    private EcaTransformerManager() {}

    public static Backend backend() {
        return backend;
    }

    public static boolean supportsExtendedRuntime() {
        return EcaAgent.getInstrumentation() != null || NativeRuntimeBridge.isPackaged();
    }

    public static boolean applyLoadCompleteTransforms() {
        if (EcaConfiguration.getForceCompatibilityModeSafely()) return false;
        return tryAgentLoadComplete();
    }

    public static boolean retransformClass(Class<?> clazz) {
        if (clazz == null) return false;
        if (!isCoremodBackend() && tryAgentRetransform(clazz)) {
            backend = Backend.AGENT;
            return true;
        }

        return requestNative(List.of(clazz), false);
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
        return CONFIRMED_RECEIPTS.containsKey(internalName)
                || (nativeAllowed() && NATIVE_HEALTH_CONFIRMED.contains(clazz));
    }

    public static boolean isHealthTransformConfirmed(Class<?> clazz, Backend expectedBackend) {
        if (clazz == null || expectedBackend == null) return false;
        if (expectedBackend == Backend.JVMTI) {
            return nativeAllowed() && NATIVE_HEALTH_CONFIRMED.contains(clazz);
        }
        ConfirmedReceipt receipt = CONFIRMED_RECEIPTS.get(clazz.getName().replace('.', '/'));
        return receipt != null && receipt.backend() == expectedBackend;
    }

    static void invalidateHealthTransformReceipt(String internalName) {
        if (internalName == null) return;
        CONFIRMED_RECEIPTS.remove(internalName.replace('.', '/'));
        String normalized = internalName.replace('.', '/');
        NATIVE_HEALTH_CONFIRMED.removeIf(type -> type.getName().replace('.', '/').equals(normalized));
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
                EarlyLogWriter.info("[EcaTransformerManager] Agent health transform not confirmed for "
                        + clazz.getName());
            }
        }

        if (requestNative(List.of(clazz), true) && NATIVE_HEALTH_CONFIRMED.contains(clazz)) {
            return new HealthTransformResult(Backend.JVMTI, true);
        }
        return new HealthTransformResult(Backend.NONE, false);
    }

    public static boolean retransformInternalName(String internalName) {
        if (internalName == null || internalName.isEmpty()) return false;
        if (isCoremodBackend() && !nativeAllowed()) return false;
        Class<?> owner = loadClass(internalName);
        if (!isCoremodBackend() && owner != null && tryAgentRetransform(owner)) {
            backend = Backend.AGENT;
            return true;
        }
        return owner != null ? requestNative(List.of(owner), false)
                : requestNativeNames(Set.of(internalName));
    }

    public static boolean retransformLoadedInternalNames(Set<String> internalNames) {
        if (internalNames == null || internalNames.isEmpty()) return false;
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (!isCoremodBackend() && inst != null) {
            List<Class<?>> targets = new ArrayList<>();
            try {
                for (Class<?> clazz : inst.getAllLoadedClasses()) {
                    if (!inst.isModifiableClass(clazz)) continue;
                    if (internalNames.contains(clazz.getName().replace('.', '/'))) {
                        targets.add(clazz);
                    }
                }
            } catch (Throwable t) {
                EarlyLogWriter.info("[EcaTransformerManager] Agent target enumeration failed: "
                        + t.getMessage());
            }
            if (retransformClassesWithAgent(inst, targets)) {
                backend = Backend.AGENT;
                return true;
            }
        }

        return requestNativeNames(internalNames);
    }

    public static boolean forEachLoadedClass(Consumer<Class<?>> consumer) {
        if (consumer == null) return false;
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null || isCoremodBackend()) return forEachNativeClass(consumer);
        try {
            for (Class<?> clazz : inst.getAllLoadedClasses()) {
                consumer.accept(clazz);
            }
            return true;
        } catch (Throwable t) {
            EarlyLogWriter.info("[EcaTransformerManager] Agent loaded-class enumeration failed: "
                    + t.getMessage());
            return forEachNativeClass(consumer);
        }
    }

    public static boolean forEachLoadedInternalName(Consumer<LoadedClassInfo> consumer) {
        if (consumer == null) return false;
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (!isCoremodBackend() && inst != null) {
            try {
                for (Class<?> clazz : inst.getAllLoadedClasses()) {
                    String internalName = clazz.getName().replace('.', '/');
                    boolean modifiable = inst.isModifiableClass(clazz);
                    int entityType = classifyEntity(clazz);
                    consumer.accept(new LoadedClassInfo(internalName, modifiable,
                            entityType == 1, entityType == 2));
                }
                return true;
            } catch (Throwable t) {
                EarlyLogWriter.info("[EcaTransformerManager] Agent internal-name enumeration failed: "
                        + t.getMessage());
            }
        }
        return forEachNativeClass(type -> {
            int entityType = classifyEntity(type);
            consumer.accept(new LoadedClassInfo(type.getName().replace('.', '/'),
                    NativeRuntimeBridge.isModifiable(type), entityType == 1, entityType == 2));
        });
    }

    private static boolean tryAgentLoadComplete() {
        Instrumentation inst;
        long generation;
        List<Class<?>> targets = new ArrayList<>();
        try {
            inst = acquireInstrumentation();
            if (inst == null || !inst.isRetransformClassesSupported()) return tryNativeLoadComplete();
            EcaClassTransformer.prepareNativeTargets(List.of());
            if (!isCoremodBackend() && !EcaClassTransformer.ensureRegistered(inst)) {
                return tryNativeLoadComplete();
            }
            // 整理器和校验器必须先于首批请求注册，避免为获取回执再转换全部目标。
            generation = ensureTerminalAgentTransformers(inst, true);
            if (generation <= 0L) return tryNativeLoadComplete();
            for (Class<?> type : inst.getAllLoadedClasses()) {
                if (classifyEntity(type) != 0
                        || (!isCoremodBackend() && EcaClassTransformer.isNativeLoadCompleteTarget(type))) {
                    targets.add(type);
                }
            }
            EcaClassTransformer.prepareNativeTargets(targets);
        } catch (Throwable t) {
            EarlyLogWriter.log("INFO", "[EcaTransformerManager] Agent load-complete preparation failed", t);
            return tryNativeLoadComplete();
        }

        long started = System.nanoTime();
        List<Class<?>> agentTargets = new ArrayList<>();
        Set<Class<?>> agentConfirmed = new HashSet<>();
        for (Class<?> type : targets) {
            if (isModifiable(inst, type)) agentTargets.add(type);
        }
        int batchSize = 32;
        for (int start = 0; start < agentTargets.size(); start += batchSize) {
            int end = Math.min(start + batchSize, agentTargets.size());
            Class<?>[] batch = agentTargets.subList(start, end).toArray(new Class<?>[0]);
            boolean[] receipts = retransformHealthBatch(inst, batch, generation, 0);
            for (int i = 0; i < batch.length; i++) {
                if (receipts[i]) agentConfirmed.add(batch[i]);
            }
        }
        // 复查只读取首轮结果；后续请求若使回执失效，该类也必须交给原生补救。
        List<Class<?>> fallback = new ArrayList<>();
        for (Class<?> type : targets) {
            if (!agentConfirmed.contains(type)
                    || (classifyEntity(type) != 0 && !isHealthTransformConfirmed(type, Backend.AGENT))) {
                fallback.add(type);
            }
        }
        int confirmed = targets.size() - fallback.size();
        if (confirmed > 0) backend = isCoremodBackend() ? Backend.COREMOD : Backend.AGENT;
        EarlyLogWriter.info("[EcaTransformerManager] Agent first pass targets=" + targets.size()
                + ", confirmed=" + confirmed + ", fallback=" + fallback.size()
                + ", elapsedMs=" + (System.nanoTime() - started) / 1_000_000L);
        int nativeConfirmed = applyNativeLoadCompleteFallback(fallback);
        EarlyLogWriter.info("[EcaTransformerManager] Load-complete review targets=" + targets.size()
                + ", agentConfirmed=" + confirmed + ", nativeConfirmed=" + nativeConfirmed
                + ", unresolved=" + (fallback.size() - nativeConfirmed));
        if (confirmed + nativeConfirmed == 0 && !targets.isEmpty()) {
            backend = Backend.NONE;
            logAllFailed();
        }
        return confirmed + nativeConfirmed == targets.size();
    }

    private static boolean[] retransformHealthBatch(Instrumentation inst, Class<?>[] batch,
                                                    long generation, int lockIndex) {
        // 与单类请求共用锁，避免批量回执被并发请求覆盖；递归深度不超过批量大小。
        if (lockIndex < batch.length) {
            String name = batch[lockIndex].getName().replace('.', '/');
            Object lock = HEALTH_TRANSFORM_LOCKS.computeIfAbsent(name, ignored -> new Object());
            synchronized (lock) {
                return retransformHealthBatch(inst, batch, generation, lockIndex + 1);
            }
        }

        String[] names = new String[batch.length];
        long[] epochs = new long[batch.length];
        boolean[] confirmed = new boolean[batch.length];
        boolean applied = false;
        try {
            for (int i = 0; i < batch.length; i++) {
                names[i] = batch[i].getName().replace('.', '/');
                if (classifyEntity(batch[i]) != 0) epochs[i] = beginReceipt(names[i], generation);
            }
            EcaClassTransformer.retransformOwnedClasses(inst, batch);
            applied = true;
            for (int i = 0; i < batch.length; i++) {
                confirmed[i] = epochs[i] == 0L || receiptConfirmed(names[i], epochs[i]);
            }
        } catch (Throwable t) {
            EarlyLogWriter.log("INFO", "[EcaTransformerManager] Agent first-pass batch failed; queuing "
                    + batch.length + " classes for native fallback", t);
        } finally {
            for (int i = 0; i < batch.length; i++) {
                if (epochs[i] == 0L) continue;
                // 回调验证通过不代表 JVM 已接受整批转换，失败批次的临时确认必须作废。
                if (!applied) {
                    long epoch = epochs[i];
                    CONFIRMED_RECEIPTS.computeIfPresent(names[i],
                            (ignored, receipt) -> receipt.epoch() == epoch ? null : receipt);
                }
                endReceipt(names[i], epochs[i]);
            }
        }
        return confirmed;
    }

    private static Instrumentation acquireInstrumentation() {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst != null) return inst;
        EcaAgent.adoptSystemInstrumentation();
        inst = EcaAgent.getInstrumentation();
        if (inst != null) return inst;
        AgentLoader.enableSelfAttach();
        AgentLoader.loadAgent();
        return EcaAgent.getInstrumentation();
    }

    private static boolean tryAgentRetransform(Class<?> clazz) {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null || clazz == null) return false;
        try {
            if (!inst.isModifiableClass(clazz)) return false;
            RuntimeBytecodeProvider.beginSelfRetransform();
            try {
                inst.retransformClasses(clazz);
            } finally {
                RuntimeBytecodeProvider.endSelfRetransform();
            }
            return true;
        } catch (Throwable t) {
            EarlyLogWriter.log("INFO", "[EcaTransformerManager] Agent retransform failed for "
                    + clazz.getName(), t);
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
                        if (NativeRuntimeBridge.isTransforming()) return null;
                        return EcaClassTransformer.normalizeHealthTail(name, bytes);
                    }
                }, true);
                inst.addTransformer(new ClassFileTransformer() {
                    @Override
                    public byte[] transform(ClassLoader loader, String name, Class<?> beingRedefined,
                                            ProtectionDomain domain, byte[] bytes) {
                        if (NativeRuntimeBridge.isTransforming()) return null;
                        confirmReceipt(name, bytes, generation);
                        return null;
                    }
                }, true);
                terminalTransformGeneration = generation;
                EarlyLogWriter.info("[EcaTransformerManager] Registered terminal health transformers generation="
                        + generation);
                return generation;
            } catch (Throwable t) {
                EarlyLogWriter.info("[EcaTransformerManager] Terminal health transformer registration failed: "
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
        if (EcaClassTransformer.verifyNormalizedHealthTail(normalized, bytes)) {
            CONFIRMED_RECEIPTS.put(normalized, new ConfirmedReceipt(pending.epoch(), Backend.AGENT));
        }
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
        RuntimeBytecodeProvider.beginSelfRetransform();
        try {
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
                            EarlyLogWriter.info("[EcaTransformerManager] Agent retransform failed for "
                                    + clazz.getName() + ": " + classFailure.getMessage());
                        }
                    }
                }
            }
        } finally {
            RuntimeBytecodeProvider.endSelfRetransform();
        }
        if (successCount > 0) {
            EarlyLogWriter.info("[EcaTransformerManager] Retransformed " + successCount
                    + " selected mod classes via agent");
        }
        return successCount > 0;
    }

    // Explicit targets preserve ClassLoader identity when a bridge requires native confirmation.
    public static boolean retransformExplicitClasses(List<? extends Class<?>> classes) {
        if (classes == null || classes.isEmpty()) return false;
        return requestNative(classes, false);
    }

    private static boolean nativeAllowed() {
        return NativeRuntimeBridge.isPackaged() && !EcaConfiguration.getForceCompatibilityModeSafely()
                && EcaConfiguration.getDefenceEnableRadicalLogicSafely();
    }

    private static boolean ensureNativeActive() {
        synchronized (NATIVE_LOCK) {
            if (!nativeAllowed()) {
                if (nativeActive) NativeRuntimeBridge.deactivate();
                nativeActive = false;
                NATIVE_HEALTH_CONFIRMED.clear();
                return false;
            }
            if (nativeActive) return true;
            try {
                EcaClassTransformer.prepareNativeTargets(List.of());
                boolean nativeOwnsLoads = isCoremodBackend();
                nativeActive = NativeRuntimeBridge.activate(new ClassFileTransformer() {
                    @Override
                    public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                            ProtectionDomain domain, byte[] bytes) {
                        NativeRequest request = NATIVE_REQUEST.get();
                        if (type == null && nativeOwnsLoads) {
                            RuntimeBytecodeProvider.captureAnalysisInput(name, bytes);
                            // Coremod already handles protected base classes and containers at definition time.
                            if (name == null || TransformerWhitelist.isSystemProtectedInternal(name)) return null;
                            return EcaClassTransformer.transformNative(name, null, bytes, false);
                        }
                        if (request == null || type == null || !request.targets().contains(type)) return null;
                        String internalName = type.getName().replace('.', '/');
                        return EcaClassTransformer.transformNative(internalName, type, bytes, request.health());
                    }
                }, new ClassFileTransformer() {
                    @Override
                    public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                            ProtectionDomain domain, byte[] bytes) {
                        NativeRequest request = NATIVE_REQUEST.get();
                        if (type == null && nativeOwnsLoads) {
                            RuntimeBytecodeProvider.captureNativeOutput(name, bytes);
                            return null;
                        }
                        if (request == null && type != null) {
                            // Leave fresh Agent receipts intact, but retire an older native confirmation.
                            NATIVE_HEALTH_CONFIRMED.remove(type);
                            return null;
                        }
                        if (request == null || type == null || !request.targets().contains(type)) return null;
                        String internalName = type.getName().replace('.', '/');
                        request.outputs().put(type, bytes.clone());
                        if (request.health() && EcaClassTransformer.verifyNormalizedHealthTail(internalName, bytes)) {
                            request.healthConfirmed().add(type);
                        }
                        return null;
                    }
                });
                return nativeActive;
            } catch (Throwable t) {
                EarlyLogWriter.info("[EcaTransformerManager] Native activation failed: " + t);
                return false;
            }
        }
    }

    private static boolean requestNative(List<? extends Class<?>> classes, boolean health) {
        if (classes.isEmpty() || NATIVE_REQUEST.get() != null || !ensureNativeActive()) return false;
        try {
            List<Class<?>> targets = new ArrayList<>(classes.stream().filter(type -> type != null
                    && NativeRuntimeBridge.isModifiable(type)).distinct().toList());
            if (targets.isEmpty()) return false;
            EcaClassTransformer.prepareNativeTargets(targets);
            boolean anyConfirmed = false;
            for (Class<?> type : targets) {
                NativeRequest request = new NativeRequest(Set.of(type), new HashMap<>(), new HashSet<>(), health);
                NATIVE_REQUEST.set(request);
                RuntimeBytecodeProvider.beginSelfRetransform();
                boolean applied;
                try {
                    applied = NativeRuntimeBridge.retransform(new Class<?>[]{type});
                } finally {
                    RuntimeBytecodeProvider.endSelfRetransform();
                    NATIVE_REQUEST.remove();
                }
                // Callback output is provisional until RetransformClasses also reports success.
                if (applied && request.outputs().containsKey(type)) {
                    RuntimeBytecodeProvider.captureNativeOutput(type.getName().replace('.', '/'),
                            request.outputs().get(type));
                    if (!health || request.healthConfirmed().contains(type)) {
                        if (health) NATIVE_HEALTH_CONFIRMED.add(type);
                        anyConfirmed = true;
                    }
                }
            }
            if (anyConfirmed) backend = Backend.JVMTI;
            return anyConfirmed;
        } catch (Throwable t) {
            EarlyLogWriter.info("[EcaTransformerManager] Native request failed: " + t);
            return false;
        }
    }

    private static Class<?>[] nativeClasses() {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst != null) {
            try {
                return inst.getAllLoadedClasses();
            } catch (Throwable t) {
                EarlyLogWriter.info("[EcaTransformerManager] Agent enumeration unavailable: " + t);
            }
        }
        return NativeRuntimeBridge.collectedClasses();
    }

    private static boolean requestNativeNames(Set<String> names) {
        if (!ensureNativeActive()) return false;
        List<Class<?>> targets = new ArrayList<>();
        for (Class<?> type : nativeClasses()) {
            if (names.contains(type.getName().replace('.', '/'))) targets.add(type);
        }
        return requestNative(targets, false);
    }

    private static boolean tryNativeLoadComplete() {
        if (!ensureNativeActive()) {
            backend = isCoremodBackend() ? Backend.COREMOD : Backend.NONE;
            logAllFailed();
            return false;
        }
        List<Class<?>> targets = new ArrayList<>();
        for (Class<?> type : nativeClasses()) {
            if (classifyEntity(type) != 0 || EcaClassTransformer.isNativeLoadCompleteTarget(type)) targets.add(type);
        }
        int confirmed = applyNativeLoadCompleteFallback(targets);
        EarlyLogWriter.info("[EcaTransformerManager] Native load-complete targets=" + targets.size()
                + ", confirmed=" + confirmed + ", unresolved=" + (targets.size() - confirmed));
        return !targets.isEmpty() && confirmed == targets.size();
    }

    private static int applyNativeLoadCompleteFallback(List<Class<?>> targets) {
        int confirmed = 0;
        for (Class<?> type : targets) {
            if (requestNative(List.of(type), classifyEntity(type) != 0)) {
                confirmed++;
            } else {
                EarlyLogWriter.info("[EcaTransformerManager] Load-complete transform unresolved for "
                        + type.getName());
            }
        }
        return confirmed;
    }

    private static boolean forEachNativeClass(Consumer<Class<?>> consumer) {
        if (!ensureNativeActive()) return false;
        Class<?>[] classes = nativeClasses();
        for (Class<?> type : classes) consumer.accept(type);
        return classes.length > 0;
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

    private static boolean isCoremodBackend() {
        return Backend.COREMOD.name().equals(System.getProperty(TRANSFORMATION_BACKEND_KEY));
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
            EcaLogger.info("WARNING! ECA runtime transformation backend is unavailable");
        } catch (Throwable ignored) {
            EarlyLogWriter.info("WARNING! ECA runtime transformation backend is unavailable");
        }
    }
}
