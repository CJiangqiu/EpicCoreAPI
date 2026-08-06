package net.eca.util.call_bridge;

import net.eca.coremod.EcaTransformerManager;
import net.eca.coremod.RuntimeBytecodeProvider;
import net.eca.util.EcaLogger;

import java.io.InputStream;
import java.security.CodeSource;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class CallBridgeManager {

    private static final int MAX_SCAN_CLASSES = 4096;
    private static final long EMPTY_SCAN_RETRY_NANOS = 30_000_000_000L;
    private static final ThreadLocal<Authorization> AUTHORIZATION = new ThreadLocal<>();
    private static final Set<String> INSTALLED_SOURCES = ConcurrentHashMap.newKeySet();
    private static final Set<String> INSTALLING_SOURCES = ConcurrentHashMap.newKeySet();
    private static final Set<String> RUNTIME_CONFIRMED_SOURCES = ConcurrentHashMap.newKeySet();
    private static final Set<String> JVMTI_INSTALLED_SOURCES = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> EMPTY_SOURCES = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Class<?>>> WATCHDOGS_BY_SOURCE = new ConcurrentHashMap<>();

    private static final class Authorization {
        private final Authorization parent;
        private boolean runtimeObserved;

        private Authorization(Authorization parent) {
            this.parent = parent;
        }
    }

    public record AuthorizedResult<T>(T value, boolean runtimeObserved) {}

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    private CallBridgeManager() {}

    public static void prepare(Object target) {
        if (target != null) prepare(target instanceof Class<?> type ? type : target.getClass());
    }

    public static <T> T callAuthorized(Object target, Supplier<T> invocation) {
        Objects.requireNonNull(invocation, "invocation");
        prepare(target);
        Authorization previous = AUTHORIZATION.get();
        Authorization current = new Authorization(previous);
        AUTHORIZATION.set(current);
        try {
            T value = invocation.get();
            if (current.runtimeObserved) markRuntimeConfirmed(target);
            return value;
        } finally {
            restore(previous);
        }
    }

    public static <T> T callAuthorizedThrowing(Object target, ThrowingSupplier<T> invocation)
            throws Throwable {
        return callAuthorizedObservedThrowing(target, invocation).value();
    }

    public static <T> AuthorizedResult<T> callAuthorizedObservedThrowing(
            Object target, ThrowingSupplier<T> invocation) throws Throwable {
        Objects.requireNonNull(invocation, "invocation");
        prepare(target);
        Authorization previous = AUTHORIZATION.get();
        Authorization current = new Authorization(previous);
        AUTHORIZATION.set(current);
        try {
            T value = invocation.get();
            if (current.runtimeObserved) markRuntimeConfirmed(target);
            return new AuthorizedResult<>(value, current.runtimeObserved);
        } finally {
            restore(previous);
        }
    }

    public static void runAuthorized(Object target, Runnable invocation) {
        Objects.requireNonNull(invocation, "invocation");
        callAuthorized(target, () -> {
            invocation.run();
            return null;
        });
    }

    public static boolean isAuthorized() {
        return AUTHORIZATION.get() != null;
    }

    static void noteRuntimeObservation() {
        for (Authorization current = AUTHORIZATION.get(); current != null; current = current.parent) {
            current.runtimeObserved = true;
        }
    }

    public static boolean hasWatchdogs(Object target) {
        String sourceKey = sourceKey(target);
        Map<String, Class<?>> watchdogs = sourceKey == null ? null : WATCHDOGS_BY_SOURCE.get(sourceKey);
        return watchdogs != null && !watchdogs.isEmpty();
    }

    public static boolean forceJvmTi(Object target) {
        Class<?> targetClass = targetClass(target);
        String sourceKey = sourceKey(targetClass);
        if (targetClass == null || sourceKey == null) return false;
        Map<String, Class<?>> watchdogs = WATCHDOGS_BY_SOURCE.get(sourceKey);
        if (watchdogs == null || watchdogs.isEmpty()) {
            CodeSource source = codeSource(targetClass);
            if (source == null || source.getLocation() == null) return false;
            watchdogs = scanWatchdogs(targetClass, source);
            if (watchdogs.isEmpty()) return false;
            registerWatchdogs(sourceKey, watchdogs);
        }
        if (JVMTI_INSTALLED_SOURCES.contains(sourceKey)) return true;
        boolean requested = EcaTransformerManager.retransformLoadedInternalNamesWithJvmTi(watchdogs.keySet());
        Set<String> confirmed = confirmedWatchdogs(watchdogs);
        if (requested && confirmed.size() == watchdogs.size()) {
            JVMTI_INSTALLED_SOURCES.add(sourceKey);
            EcaLogger.info("[CallBridge] watchdog JVMTI fallback confirmed classes={} source={}",
                    confirmed.size(), codeSource(targetClass).getLocation());
            return true;
        }
        EcaLogger.info("[CallBridge] watchdog JVMTI fallback unconfirmed confirmed={} expected={} requested={} source={}",
                confirmed.size(), watchdogs.size(), requested, codeSource(targetClass).getLocation());
        return false;
    }

    private static void prepare(Class<?> targetClass) {
        CodeSource source = codeSource(targetClass);
        if (source == null || source.getLocation() == null) return;
        String sourceKey = loaderIdentity(targetClass.getClassLoader()) + "|" + source.getLocation();
        if (INSTALLED_SOURCES.contains(sourceKey) || !INSTALLING_SOURCES.add(sourceKey)) return;
        try {
            Long retryAfter = EMPTY_SOURCES.get(sourceKey);
            if (retryAfter != null && System.nanoTime() - retryAfter < 0L) return;
            EMPTY_SOURCES.remove(sourceKey, retryAfter);
            Map<String, Class<?>> watchdogs = scanWatchdogs(targetClass, source);
            if (watchdogs.isEmpty()) {
                EMPTY_SOURCES.put(sourceKey, System.nanoTime() + EMPTY_SCAN_RETRY_NANOS);
                return;
            }
            registerWatchdogs(sourceKey, watchdogs);
            boolean agentRequested = EcaTransformerManager.retransformLoadedInternalNames(watchdogs.keySet());
            Set<String> confirmed = confirmedWatchdogs(watchdogs);
            Set<String> missing = new HashSet<>(watchdogs.keySet());
            missing.removeAll(confirmed);
            boolean jvmTiRequested = false;
            if (!missing.isEmpty()) {
                jvmTiRequested = EcaTransformerManager.retransformLoadedInternalNamesWithJvmTi(missing);
                confirmed.addAll(confirmedWatchdogs(watchdogs, missing));
                missing.removeAll(confirmed);
            }
            if (missing.isEmpty()) {
                INSTALLED_SOURCES.add(sourceKey);
                EcaLogger.info("[CallBridge] watchdog bridge confirmed classes={} agentRequested={} jvmTiRequested={} source={}",
                        confirmed.size(), agentRequested, jvmTiRequested, source.getLocation());
            } else {
                EcaLogger.info("[CallBridge] watchdog bridge unconfirmed confirmed={} missing={} agentRequested={} jvmTiRequested={} source={}",
                        confirmed.size(), missing.size(), agentRequested, jvmTiRequested,
                        source.getLocation());
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.info("[CallBridge] watchdog scan failed source={} msg={}",
                    source.getLocation(), t.toString());
        } finally {
            INSTALLING_SOURCES.remove(sourceKey);
        }
    }

    private static void registerWatchdogs(String sourceKey, Map<String, Class<?>> watchdogs) {
        WATCHDOGS_BY_SOURCE.put(sourceKey, Map.copyOf(watchdogs));
        for (String watchdog : watchdogs.keySet()) CallWatchdogTransformer.register(watchdog);
        EMPTY_SOURCES.remove(sourceKey);
    }

    private static void markRuntimeConfirmed(Object target) {
        String sourceKey = sourceKey(target);
        if (sourceKey != null) RUNTIME_CONFIRMED_SOURCES.add(sourceKey);
    }

    public static boolean isRuntimeConfirmed(Object target) {
        String sourceKey = sourceKey(target);
        return sourceKey != null && RUNTIME_CONFIRMED_SOURCES.contains(sourceKey);
    }

    private static Class<?> targetClass(Object target) {
        if (target instanceof Class<?> type) return type;
        return target == null ? null : target.getClass();
    }

    private static String sourceKey(Object target) {
        Class<?> targetClass = targetClass(target);
        if (targetClass == null) return null;
        CodeSource source = codeSource(targetClass);
        if (source == null || source.getLocation() == null) return null;
        return loaderIdentity(targetClass.getClassLoader()) + "|" + source.getLocation();
    }

    private static Map<String, Class<?>> scanWatchdogs(Class<?> targetClass, CodeSource source) {
        Map<String, Class<?>> matches = new LinkedHashMap<>();
        int[] scanned = {0};
        boolean enumerated = EcaTransformerManager.forEachLoadedClass(
                candidate -> scanCandidate(targetClass, source, candidate, scanned, matches));
        if (!enumerated) {
            EcaTransformerManager.forEachLoadedInternalName(info -> {
                Class<?> candidate = loadClass(info.internalName(), targetClass.getClassLoader());
                scanCandidate(targetClass, source, candidate, scanned, matches);
            });
        }
        EcaLogger.info("[CallBridge] watchdog scan classes={} matches={} source={}",
                scanned[0], matches.size(), source.getLocation());
        return matches;
    }

    private static void scanCandidate(Class<?> targetClass, CodeSource source, Class<?> candidate,
                                      int[] scanned, Map<String, Class<?>> matches) {
        if (scanned[0] >= MAX_SCAN_CLASSES || !isCandidate(targetClass, source, candidate)) return;
        scanned[0]++;
        byte[] bytes = classBytes(candidate);
        if (CallWatchdogTransformer.isWatchdog(bytes)) {
            matches.put(candidate.getName().replace('.', '/'), candidate);
        }
    }

    private static Set<String> confirmedWatchdogs(Map<String, Class<?>> watchdogs) {
        return confirmedWatchdogs(watchdogs, watchdogs.keySet());
    }

    private static Set<String> confirmedWatchdogs(Map<String, Class<?>> watchdogs,
                                                   Set<String> candidates) {
        Set<String> confirmed = new HashSet<>();
        for (String internalName : candidates) {
            byte[] bytes = RuntimeBytecodeProvider.get(watchdogs.get(internalName));
            if (CallWatchdogTransformer.verifyTransform(internalName, bytes)) {
                confirmed.add(internalName);
            }
        }
        return confirmed;
    }

    private static boolean isCandidate(Class<?> targetClass, CodeSource source, Class<?> candidate) {
        if (candidate == null || candidate.isArray() || candidate.isPrimitive()
                || candidate.getClassLoader() != targetClass.getClassLoader()) return false;
        return sameCodeSource(source, codeSource(candidate));
    }

    private static byte[] classBytes(Class<?> type) {
        byte[] bytes = RuntimeBytecodeProvider.getAnalysis(type);
        if (bytes != null) return bytes;
        bytes = RuntimeBytecodeProvider.get(type);
        if (bytes != null) return bytes;
        String resource = type.getName().replace('.', '/') + ".class";
        ClassLoader loader = type.getClassLoader();
        try (InputStream input = loader == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource)) {
            return input == null ? null : input.readAllBytes();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private static Class<?> loadClass(String internalName, ClassLoader loader) {
        if (internalName == null || internalName.contains("/0x")) return null;
        try {
            return Class.forName(internalName.replace('/', '.'), false, loader);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private static CodeSource codeSource(Class<?> type) {
        try {
            return type == null || type.getProtectionDomain() == null
                    ? null : type.getProtectionDomain().getCodeSource();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private static boolean sameCodeSource(CodeSource first, CodeSource second) {
        return first != null && second != null
                && Objects.equals(first.getLocation(), second.getLocation());
    }

    private static String loaderIdentity(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    private static void restore(Authorization previous) {
        if (previous == null) AUTHORIZATION.remove();
        else AUTHORIZATION.set(previous);
    }
}
