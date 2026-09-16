package net.eca.coremod;

import net.eca.agent.AgentLogWriter;
import net.eca.util.EcaLogger;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Optional in-process instrumentation bridge for defensive entity-state protection.
 * The standard artifact contains no native implementation; only the _1 artifact embeds it.
 * JDK-only handles share one backend across the early and game ClassLoaders.
 */
final class NativeRuntimeBridge {
    private static final String RESOURCE = "/net/eca/optional/jvmti-backend.jar";
    private static final String SHARED_KEY = "net.eca.optional.jvmti.handles";
    private static final String LOCK_KEY = "net.eca.optional.jvmti.lock";
    private static final String PROVIDER = "net.eca.jvmti.JvmTiBackend";
    private static final boolean PACKAGED = NativeRuntimeBridge.class.getResource(RESOURCE) != null;
    private static final ThreadLocal<Boolean> TRANSFORMING = ThreadLocal.withInitial(() -> false);
    private static volatile Map<String, Object> handles;

    private NativeRuntimeBridge() {}

    static boolean isPackaged() {
        return PACKAGED;
    }

    static boolean isTransforming() {
        return TRANSFORMING.get();
    }

    // Configuration classes are not safe to initialize while ModLauncher is constructing layers.
    static void prepareEarly() {
        if (!PACKAGED) return;
        Path config = Path.of("config", "eca.toml");
        if (!Files.isRegularFile(config)) return;
        try {
            boolean defence = false;
            boolean requested = false;
            boolean compatible = false;
            for (String raw : Files.readAllLines(config)) {
                String line = raw.split("#", 2)[0].trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    defence = "[Defence]".equals(line);
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator < 0) continue;
                String key = line.substring(0, separator).trim().replace("\"", "");
                boolean enabled = Boolean.parseBoolean(line.substring(separator + 1).trim());
                if ("Force Compatibility Mode".equals(key)) compatible = enabled;
                if (defence && "Enable Radical Logic".equals(key)) requested = enabled;
            }
            if (requested && !compatible) prepare();
        } catch (Throwable t) {
            logFailure("Early configuration read failed", t);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> prepare() {
        if (!PACKAGED) return Map.of();
        if (handles != null) return handles;
        Object initializationLock = System.getProperties().computeIfAbsent(LOCK_KEY, ignored -> new Object());
        // Native initialization loads classes and must not hold the global system-properties monitor.
        synchronized (initializationLock) {
            Object shared = System.getProperties().get(SHARED_KEY);
            if (shared instanceof Map<?, ?> existing) {
                handles = (Map<String, Object>) existing;
                return handles;
            }
            ClassLoader dependencyLoader = findJnaLoader();
            // An early layer may not expose JNA yet; allow the game layer to retry later.
            if (dependencyLoader == null) return Map.of();
            URLClassLoader loader = null;
            try {
                Path directory = Files.createTempDirectory("eca-jvmti-");
                Path archive = directory.resolve("jvmti-backend.jar");
                // deleteOnExit runs in reverse registration order.
                directory.toFile().deleteOnExit();
                archive.toFile().deleteOnExit();
                try (InputStream input = NativeRuntimeBridge.class.getResourceAsStream(RESOURCE)) {
                    if (input == null) return Map.of();
                    Files.copy(input, archive);
                }
                loader = new URLClassLoader(new URL[]{archive.toUri().toURL()},
                        dependencyLoader);
                Class<?> provider = Class.forName(PROVIDER, true, loader);
                Consumer<String> logger = AgentLogWriter::info;
                handles = (Map<String, Object>) provider.getMethod("open", Consumer.class)
                        .invoke(null, logger);
                // Retain the loader with the callback handles for the lifetime of the JVM.
                System.getProperties().put(SHARED_KEY, handles);
                if (handles.isEmpty()) loader.close();
                return handles;
            } catch (Throwable t) {
                handles = Map.of();
                System.getProperties().put(SHARED_KEY, handles);
                if (loader != null) {
                    try { loader.close(); } catch (Exception ignored) {}
                }
                logFailure("Optional backend initialization failed", t);
                return handles;
            }
        }
    }

    private static ClassLoader findJnaLoader() {
        ClassLoader[] candidates = {
                NativeRuntimeBridge.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) continue;
            try {
                Class<?> nativeType = Class.forName("com.sun.jna.Native", false, candidate);
                ClassLoader definingLoader = nativeType.getClassLoader();
                Class.forName("com.sun.jna.Function", false, definingLoader);
                Class.forName("com.sun.jna.Callback", false, definingLoader);
                return definingLoader;
            } catch (ClassNotFoundException ignored) {
                // JNA may belong to another launcher layer.
            } catch (Throwable t) {
                logFailure("Runtime JNA lookup failed", t);
            }
        }
        logFailure("Runtime JNA unavailable", new ClassNotFoundException("com.sun.jna.Native"));
        return null;
    }

    @SuppressWarnings("unchecked")
    static boolean activate(ClassFileTransformer transformer, ClassFileTransformer observer) {
        Object operation = prepare().get("activate");
        return operation instanceof BiPredicate<?, ?>
                && ((BiPredicate<ClassFileTransformer, ClassFileTransformer>) operation)
                .test(transformer, observer);
    }

    @SuppressWarnings("unchecked")
    static Class<?>[] collectedClasses() {
        Object operation = prepare().get("classes");
        return operation instanceof Supplier<?> ? ((Supplier<Class<?>[]>) operation).get() : new Class<?>[0];
    }

    @SuppressWarnings("unchecked")
    static boolean isModifiable(Class<?> type) {
        Object operation = prepare().get("modifiable");
        return operation instanceof Predicate<?> && ((Predicate<Class<?>>) operation).test(type);
    }

    @SuppressWarnings("unchecked")
    static boolean retransform(Class<?>[] classes) {
        Object operation = prepare().get("retransform");
        if (!(operation instanceof Predicate<?>)) return false;
        boolean previous = TRANSFORMING.get();
        TRANSFORMING.set(true);
        try {
            return ((Predicate<Class<?>[]>) operation).test(classes);
        } finally {
            if (previous) TRANSFORMING.set(true);
            else TRANSFORMING.remove();
        }
    }

    static void deactivate() {
        Map<String, Object> current = handles;
        if (current == null) return;
        Object operation = current.get("deactivate");
        if (operation instanceof Runnable action) action.run();
    }

    private static void logFailure(String message, Throwable failure) {
        try {
            EcaLogger.info("[NativeRuntimeBridge] " + message + ": " + failure);
        } catch (Throwable ignored) {
            AgentLogWriter.info("[NativeRuntimeBridge] " + message + ": " + failure);
        }
    }
}
