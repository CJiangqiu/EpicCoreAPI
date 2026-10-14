package net.eca.coremod;

import net.eca.coremod.EarlyLogWriter;
import net.eca.agent.AgentLoader;
import net.eca.agent.EcaAgent;
import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;
import net.eca.util.health.MethodProbe;
import net.eca.util.health.ConstOverride;
import net.eca.util.health.WarmupTimings;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final int TRANSFORM_BATCH_SIZE = 32;

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
    private static final AtomicLong REVIEW_TRACE_ID = new AtomicLong();
    private static final Set<Class<?>> NATIVE_HEALTH_CONFIRMED = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> NATIVE_REPAIRED = ConcurrentHashMap.newKeySet();
    private static final Map<Class<?>, Backend> REVIEWED_CLASSES = new ConcurrentHashMap<>();

    private record NativeRequest(Set<Class<?>> targets, Map<Class<?>, byte[]> outputs,
                                 boolean health, boolean review, long traceId,
                                 List<ReviewTrace> trace, Set<Class<?>> repairs) {}
    private record ReviewTrace(Class<?> type, String stage, long generation, byte[] bytes) {}

    private record PendingReceipt(long epoch, long generation, byte[][] diagnosticOutput) {}
    private record ConfirmedReceipt(long epoch, Backend backend) {}

    private record HealthRequestKey(Object bridge, List<ConstOverride.Site> constants,
                                    long externalRevision, boolean nativeEnabled) {}
    private static final class FailureState {
        final AtomicLong externalRevision = new AtomicLong();
        volatile HealthRequestKey failed;
    }
    private static final ClassValue<FailureState> HEALTH_FAILURES = new ClassValue<>() {
        @Override
        protected FailureState computeValue(Class<?> type) {
            return new FailureState();
        }
    };
    private static final ClassValue<Boolean> DECLARES_HEALTH_HOOK = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return EcaClassTransformer.declaresHealthHook(type);
        }
    };

    private static boolean requiresLocalTransform(Class<?> type) {
        String name = type.getName().replace('.', '/');
        return classifyEntity(type) == 0 || DECLARES_HEALTH_HOOK.get(type)
                || MethodProbe.hasTransformSpecs(name) || ConstOverride.hasSites(name);
    }

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
        try (WarmupTimings.Scope ignored = WarmupTimings.enter(WarmupTimings.Stage.TRANSFORM)) {
            return retransformHealthClassTimed(clazz, refreshTerminal);
        }
    }

    private static HealthTransformResult retransformHealthClassTimed(Class<?> clazz, boolean refreshTerminal) {
        if (clazz == null) return new HealthTransformResult(Backend.NONE, false);
        if (EcaConfiguration.getForceCompatibilityModeSafely()) {
            return new HealthTransformResult(Backend.NONE, false);
        }
        String internalName = clazz.getName().replace('.', '/');
        Object lock = HEALTH_TRANSFORM_LOCKS.computeIfAbsent(internalName, ignored -> new Object());
        synchronized (lock) {
            FailureState state = HEALTH_FAILURES.get(clazz);
            HealthRequestKey key = new HealthRequestKey(MethodProbe.transformRequestKey(internalName),
                    ConstOverride.transformRequestKey(internalName), state.externalRevision.get(), nativeAllowed());
            // 刷新转换器不等于补丁变化，同一失败请求不能随每个实体分析反复执行。
            if (key.equals(state.failed)) return new HealthTransformResult(Backend.NONE, false);
            HealthTransformResult result = retransformHealthClassLocked(clazz, internalName, refreshTerminal);
            state.failed = result.confirmed() ? null : key;
            if (!result.confirmed()) {
                TransformAudit.report(clazz, "runtime-cache", "FAILED: identical patch requests suppressed", null);
            }
            return result;
        }
    }

    static void observeExternalTransform(Class<?> type) {
        if (type == null || RuntimeBytecodeProvider.isSelfRetransform()) return;
        RuntimeBytecodeProvider.advanceBytecodeRevision();
        FailureState state = HEALTH_FAILURES.get(type);
        state.externalRevision.incrementAndGet();
        state.failed = null;
    }

    public static boolean isHealthTransformConfirmed(Class<?> clazz) {
        if (clazz == null) return false;
        return REVIEWED_CLASSES.containsKey(clazz);
    }

    public static boolean isHealthTransformConfirmed(Class<?> clazz, Backend expectedBackend) {
        if (clazz == null || expectedBackend == null) return false;
        return REVIEWED_CLASSES.get(clazz) == expectedBackend;
    }

    static void invalidateHealthTransformReceipt(String internalName) {
        if (internalName == null) return;
        CONFIRMED_RECEIPTS.remove(internalName.replace('.', '/'));
        String normalized = internalName.replace('.', '/');
        NATIVE_HEALTH_CONFIRMED.removeIf(type -> type.getName().replace('.', '/').equals(normalized));
        REVIEWED_CLASSES.keySet().removeIf(type -> type.getName().replace('.', '/').equals(normalized));
    }

    private static HealthTransformResult retransformHealthClassLocked(
            Class<?> clazz, String internalName, boolean refreshTerminal) {
        Instrumentation inst = EcaAgent.getInstrumentation();
        boolean agentRequested = false;
        if (inst != null && isModifiable(inst, clazz)) {
            long generation = ensureTerminalAgentTransformers(inst, refreshTerminal);
            if (generation > 0L) {
                long epoch = beginReceipt(clazz, generation);
                boolean requested = tryAgentRetransform(clazz);
                agentRequested = requested;
                reportAgentOutput(clazz, epoch, requested);
                endReceipt(internalName, epoch);
            }
        }

        if (reviewClasses(List.of(clazz), "runtime-review",
                agentRequested ? Backend.AGENT : Backend.JVMTI).contains(clazz)) {
            backend = REVIEWED_CLASSES.get(clazz);
            return new HealthTransformResult(backend, true);
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
            if (requiresLocalTransform(type) && isModifiable(inst, type)) agentTargets.add(type);
        }
        int batchSize = TRANSFORM_BATCH_SIZE;
        for (int start = 0; start < agentTargets.size(); start += batchSize) {
            int end = Math.min(start + batchSize, agentTargets.size());
            Class<?>[] batch = agentTargets.subList(start, end).toArray(new Class<?>[0]);
            boolean[] receipts = retransformHealthBatch(inst, batch, generation, 0);
            for (int i = 0; i < batch.length; i++) {
                if (receipts[i]) agentConfirmed.add(batch[i]);
            }
        }
        EarlyLogWriter.info("[EcaTransformerManager] Agent first pass targets=" + targets.size()
                + ", requested=" + agentTargets.size()
                + ", localReceipts=" + agentConfirmed.size()
                + ", elapsedMs=" + (System.nanoTime() - started) / 1_000_000L);
        // 回执不能代表后续链输出；复查回调只补救当前类缺失的 Hook。
        Set<Class<?>> reviewed = reviewClasses(targets, "load-complete-review", Backend.AGENT);
        int nativeConfirmed = (int) reviewed.stream()
                .filter(type -> REVIEWED_CLASSES.get(type) == Backend.JVMTI).count();
        int confirmed = reviewed.size() - nativeConfirmed;
        if (!reviewed.isEmpty()) backend = nativeConfirmed > 0 ? Backend.JVMTI
                : isCoremodBackend() ? Backend.COREMOD : Backend.AGENT;
        EarlyLogWriter.info("[EcaTransformerManager] Load-complete review targets=" + targets.size()
                + ", agentConfirmed=" + confirmed + ", nativeConfirmed=" + nativeConfirmed
                + ", unresolved=" + (targets.size() - reviewed.size()));
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
                if (classifyEntity(batch[i]) != 0) epochs[i] = beginReceipt(batch[i], generation);
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
                reportAgentOutput(batch[i], epochs[i], applied);
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

    private static Set<Class<?>> reviewClasses(List<Class<?>> targets, String phase, Backend origin) {
        Set<Class<?>> passed = new HashSet<>();
        if (targets.isEmpty()) return passed;
        long started = System.nanoTime();
        for (Class<?> type : targets) {
            REVIEWED_CLASSES.remove(type);
            NATIVE_HEALTH_CONFIRMED.remove(type);
        }
        if (!ensureNativeActive()) {
            for (Class<?> type : targets) {
                REVIEWED_CLASSES.remove(type);
                TransformAudit.report(type, phase, "UNVERIFIED: independent observer unavailable", null);
            }
            return passed;
        }
        Map<Class<?>, byte[]> observed = new HashMap<>();
        try {
            EcaClassTransformer.prepareNativeTargets(targets);
        } catch (Throwable failure) {
            EarlyLogWriter.info("[TransformAudit] Review preparation failed: " + failure.getClass().getSimpleName());
            return passed;
        }
        Set<Class<?>> inheritedOnly = new HashSet<>();
        List<Class<?>> localTargets = new ArrayList<>();
        for (Class<?> type : targets) {
            if (requiresLocalTransform(type)) localTargets.add(type);
            else inheritedOnly.add(type);
        }
        collectNativeBatches(localTargets, true, true, phase, observed);
        Map<Class<?>, Boolean> localValidity = new HashMap<>();
        for (Class<?> type : targets) {
            localValidity.put(type, inheritedOnly.contains(type) || verifyOutput(type, observed.get(type)));
        }
        int nativeConfirmed = 0;
        // 先收齐所有输出，再检查继承关系，避免子类顺序影响验收。
        for (Class<?> type : targets) {
            byte[] bytes = observed.get(type);
            boolean valid = localValidity.get(type);
            boolean nativeRequired = origin == Backend.JVMTI || NATIVE_REPAIRED.contains(type);
            for (Class<?> parent = type.getSuperclass(); valid && parent != null;
                 parent = parent.getSuperclass()) {
                if (classifyEntity(parent) == 0) continue;
                valid = localValidity.containsKey(parent) ? localValidity.get(parent)
                        : REVIEWED_CLASSES.containsKey(parent);
                nativeRequired |= NATIVE_REPAIRED.contains(parent)
                        || REVIEWED_CLASSES.get(parent) == Backend.JVMTI;
            }
            if (valid) {
                passed.add(type);
                REVIEWED_CLASSES.put(type, nativeRequired ? Backend.JVMTI : origin);
                if (nativeRequired) {
                    NATIVE_HEALTH_CONFIRMED.add(type);
                    nativeConfirmed++;
                }
            }
            TransformAudit.report(type, phase, valid
                    ? inheritedOnly.contains(type) ? "PASS: inherited contract" : "PASS"
                    : inheritedOnly.contains(type) ? "FAIL: inherited contract"
                    : bytes == null ? "UNVERIFIED: no accepted output; modifiable="
                            + NativeRuntimeBridge.isModifiable(type) + " hidden=" + type.isHidden()
                            + " synthetic=" + type.isSynthetic()
                    : localValidity.get(type) ? "FAIL: inherited contract"
                    : "FAIL: local hooks or requested patches", bytes);
        }
        EarlyLogWriter.info("[TransformAudit] phase=" + phase + " targets=" + targets.size()
                + " passed=" + passed.size() + " agentPreserved=" + (passed.size() - nativeConfirmed)
                + " nativeConfirmed=" + nativeConfirmed
                + " inheritedOnly=" + inheritedOnly.size() + " localTargets=" + localTargets.size()
                + " unresolved=" + (targets.size() - passed.size())
                + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000L);
        return passed;
    }

    private static void collectNativeBatches(List<? extends Class<?>> targets, boolean health,
                                             boolean review, String phase,
                                             Map<Class<?>, byte[]> acceptedOutputs) {
        List<Class<?>> modifiable = new ArrayList<>();
        for (Class<?> type : targets) {
            if (type != null && NativeRuntimeBridge.isModifiable(type)) modifiable.add(type);
        }
        long started = System.nanoTime();
        EarlyLogWriter.info("[TransformAudit] phase=" + phase + " begin targets=" + targets.size()
                + " modifiable=" + modifiable.size() + " batchSize=" + TRANSFORM_BATCH_SIZE);
        for (int start = 0; start < modifiable.size(); start += TRANSFORM_BATCH_SIZE) {
            int end = Math.min(start + TRANSFORM_BATCH_SIZE, modifiable.size());
            long batchStarted = System.nanoTime();
            collectNativeBatch(modifiable.subList(start, end), health, review, acceptedOutputs);
            EarlyLogWriter.info("[TransformAudit] phase=" + phase + " progress=" + end + "/"
                    + modifiable.size() + " accepted=" + acceptedOutputs.size()
                    + " batchMs=" + (System.nanoTime() - batchStarted) / 1_000_000L
                    + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000L);
        }
    }

    private static void collectNativeBatch(List<Class<?>> batch, boolean health, boolean review,
                                           Map<Class<?>, byte[]> acceptedOutputs) {
        if (batch.isEmpty()) return;
        NativeRequest request = new NativeRequest(Set.copyOf(batch), new HashMap<>(), health, review,
                review ? REVIEW_TRACE_ID.incrementAndGet() : 0L, new ArrayList<>(), new HashSet<>());
        NATIVE_REQUEST.set(request);
        RuntimeBytecodeProvider.beginSelfRetransform();
        boolean accepted = false;
        try {
            Class<?>[] types = batch.toArray(Class<?>[]::new);
            accepted = review ? NativeRuntimeBridge.observe(types) : NativeRuntimeBridge.retransform(types);
        } catch (Throwable failure) {
            EarlyLogWriter.info("[TransformAudit] Batch request failed: " + failure.getClass().getSimpleName());
        } finally {
            RuntimeBytecodeProvider.endSelfRetransform();
            NATIVE_REQUEST.remove();
        }
        reportReviewTrace(request, accepted);
        if (accepted) {
            acceptedOutputs.putAll(request.outputs());
            for (Class<?> type : request.repairs()) {
                byte[] output = request.outputs().get(type);
                if (output == null) continue;
                NATIVE_REPAIRED.add(type);
                RuntimeBytecodeProvider.captureNativeOutput(type.getName().replace('.', '/'), output);
            }
            if (review && !request.repairs().isEmpty()) {
                EarlyLogWriter.info("[TransformAudit] reviewRequest=" + request.traceId()
                        + " acceptedRepairs=" + request.repairs().size());
            }
        } else if (batch.size() > 1) {
            // 整批被 JVM 拒绝时丢弃临时回执，二分隔离故障类。
            EarlyLogWriter.info("[TransformAudit] Splitting rejected batch size=" + batch.size());
            int middle = batch.size() / 2;
            collectNativeBatch(batch.subList(0, middle), health, review, acceptedOutputs);
            collectNativeBatch(batch.subList(middle, batch.size()), health, review, acceptedOutputs);
        } else {
            Class<?> type = batch.get(0);
            TransformAudit.reportRejected(type, request.outputs().get(type));
        }
    }

    // 回调只记录三个基础类的快照，验证和日志输出放在请求返回之后。
    private static void captureReviewTrace(Class<?> type, String stage, long generation, byte[] bytes) {
        NativeRequest request = NATIVE_REQUEST.get();
        if (request == null || !request.review() || !TransformAudit.isCoreTarget(type)
                || !request.targets().contains(type) || bytes == null) return;
        request.trace().add(new ReviewTrace(type, stage, generation, bytes.clone()));
    }

    private static void reportReviewTrace(NativeRequest request, boolean accepted) {
        if (!request.review()) return;
        try {
            Map<Class<?>, byte[]> previous = new HashMap<>();
            int sequence = 0;
            for (ReviewTrace trace : request.trace()) {
                byte[] before = previous.put(trace.type(), trace.bytes());
                EarlyLogWriter.info("[TransformAudit] reviewRequest=" + request.traceId()
                        + " sequence=" + ++sequence + " target=" + trace.type().getName()
                        + " stage=" + trace.stage() + " generation=" + trace.generation()
                        + " requestAccepted=" + accepted + " localHooks="
                        + (verifyOutput(trace.type(), trace.bytes()) ? "PASS" : "FAIL")
                        + " bytes=" + trace.bytes().length + " changedFromPrevious="
                        + (before == null ? "FIRST" : Boolean.toString(!Arrays.equals(before, trace.bytes()))));
            }
            for (Class<?> type : request.targets()) {
                if (!TransformAudit.isCoreTarget(type)) continue;
                long javaCallbacks = request.trace().stream().filter(trace -> trace.type() == type
                        && trace.stage().startsWith("java-")
                        && !trace.stage().equals("java-normalizer-output")).count();
                EarlyLogWriter.info("[TransformAudit] reviewRequest=" + request.traceId()
                        + " target=" + type.getName() + " javaCallbacks=" + javaCallbacks
                        + " nativeOutput=" + request.outputs().containsKey(type)
                        + " requestAccepted=" + accepted);
            }
        } catch (RuntimeException failure) {
            EarlyLogWriter.info("[TransformAudit] Review trace failed: " + failure.getClass().getSimpleName());
        }
    }

    private static boolean verifyOutput(Class<?> type, byte[] bytes) {
        if (bytes == null) return false;
        try {
            String name = type.getName().replace('.', '/');
            return ContainerReplacementTransformer.isTarget(name)
                    ? ContainerReplacementTransformer.verify(name, bytes)
                    : EcaClassTransformer.verifyNormalizedHealthTail(name, bytes);
        } catch (Throwable failure) {
            EarlyLogWriter.info("[TransformAudit] Bytecode verification failed: " + failure.getClass().getSimpleName());
            return false;
        }
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
                        boolean suppressed = NativeRuntimeBridge.isTransforming();
                        captureReviewTrace(beingRedefined, suppressed ? "java-normalizer-skipped"
                                : "java-normalizer-input", generation, bytes);
                        if (suppressed) return null;
                        byte[] output = EcaClassTransformer.normalizeHealthTail(name, bytes);
                        captureReviewTrace(beingRedefined, "java-normalizer-output", generation,
                                output == null ? bytes : output);
                        return output;
                    }
                }, true);
                inst.addTransformer(new ClassFileTransformer() {
                    @Override
                    public byte[] transform(ClassLoader loader, String name, Class<?> beingRedefined,
                                            ProtectionDomain domain, byte[] bytes) {
                        boolean suppressed = NativeRuntimeBridge.isTransforming();
                        captureReviewTrace(beingRedefined, suppressed ? "java-receipt-skipped"
                                : "java-receipt", generation, bytes);
                        if (suppressed) return null;
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

    private static long beginReceipt(Class<?> type, long generation) {
        String internalName = type.getName().replace('.', '/');
        long epoch = TRANSFORM_EPOCH.incrementAndGet();
        PENDING_RECEIPTS.put(internalName, new PendingReceipt(epoch, generation,
                TransformAudit.isCoreTarget(type) ? new byte[1][] : null));
        CONFIRMED_RECEIPTS.remove(internalName);
        return epoch;
    }

    private static void confirmReceipt(String internalName, byte[] bytes, long generation) {
        if (internalName == null || bytes == null) return;
        String normalized = internalName.replace('.', '/');
        PendingReceipt pending = PENDING_RECEIPTS.get(normalized);
        if (pending == null || pending.generation() != generation) return;
        if (pending.diagnosticOutput() != null) pending.diagnosticOutput()[0] = bytes.clone();
        if (EcaClassTransformer.verifyNormalizedHealthTail(normalized, bytes)) {
            CONFIRMED_RECEIPTS.put(normalized, new ConfirmedReceipt(pending.epoch(), Backend.AGENT));
        }
    }

    private static boolean receiptConfirmed(String internalName, long epoch) {
        ConfirmedReceipt receipt = CONFIRMED_RECEIPTS.get(internalName);
        return receipt != null && receipt.epoch() == epoch;
    }

    private static void reportAgentOutput(Class<?> type, long epoch, boolean accepted) {
        if (!TransformAudit.isCoreTarget(type)) return;
        PendingReceipt pending = PENDING_RECEIPTS.get(type.getName().replace('.', '/'));
        if (pending == null || pending.epoch() != epoch || pending.diagnosticOutput() == null) return;
        byte[] output = pending.diagnosticOutput()[0];
        TransformAudit.reportStage(type, "agent-output", !accepted ? "REQUEST_REJECTED"
                : verifyOutput(type, output) ? "REQUEST_ACCEPTED: local hooks PASS"
                : "REQUEST_ACCEPTED: local hooks FAIL", output);
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
                REVIEWED_CLASSES.clear();
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
                        if (request == null && type != null && NATIVE_REPAIRED.contains(type)) {
                            String internalName = type.getName().replace('.', '/');
                            return ContainerReplacementTransformer.isTarget(internalName)
                                    ? ContainerReplacementTransformer.transform(internalName, bytes)
                                    : EcaClassTransformer.normalizeHealthTail(internalName, bytes);
                        }
                        if (type == null && nativeOwnsLoads) {
                            RuntimeBytecodeProvider.captureAnalysisInput(name, bytes);
                            // Coremod already handles protected base classes and containers at definition time.
                            if (name == null || TransformerWhitelist.isSystemProtectedInternal(name)) return null;
                            return EcaClassTransformer.transformNative(name, null, bytes, false);
                        }
                        if (request == null || type == null || !request.targets().contains(type)) return null;
                        captureReviewTrace(type, "native-input", 0L, bytes);
                        String internalName = type.getName().replace('.', '/');
                        // 父类契约在整批接受后统一检查，不能据此重写本类。
                        if (request.review() && verifyOutput(type, bytes)) return null;
                        TransformAudit.captureNativeInput(type, bytes);
                        try {
                            byte[] output = EcaClassTransformer.transformNative(internalName, type, bytes, request.health());
                            if (output != null) request.repairs().add(type);
                            return output;
                        } finally {
                            TransformAudit.endNativeCapture();
                        }
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
                            observeExternalTransform(type);
                            REVIEWED_CLASSES.remove(type);
                            NATIVE_HEALTH_CONFIRMED.remove(type);
                            return null;
                        }
                        if (request == null || type == null || !request.targets().contains(type)) return null;
                        captureReviewTrace(type, "native-observer", 0L, bytes);
                        request.outputs().put(type, bytes.clone());
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
            Map<Class<?>, byte[]> outputs = new HashMap<>();
            collectNativeBatches(targets, health, false, "repair", outputs);
            for (Class<?> type : targets) {
                byte[] output = outputs.get(type);
                if (output != null) {
                    if (TransformAudit.isCoreTarget(type)) {
                        TransformAudit.reportStage(type, "repair-output",
                                verifyOutput(type, output) ? "REQUEST_ACCEPTED: local hooks PASS"
                                        : "REQUEST_ACCEPTED: local hooks FAIL", output);
                    }
                    NATIVE_REPAIRED.add(type);
                    RuntimeBytecodeProvider.captureNativeOutput(type.getName().replace('.', '/'), output);
                    if (!health || verifyOutput(type, output)) {
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
        Set<Class<?>> reviewed = reviewClasses(targets, "native-review", Backend.JVMTI);
        for (Class<?> type : targets) {
            if (!reviewed.contains(type)) REVIEWED_CLASSES.remove(type);
        }
        return reviewed.size();
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
