package net.eca.jvmti;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.lang.instrument.ClassFileTransformer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Optional JVMTI transport for ECA's defensive transformations inside the game JVM.
 * It changes no transformation policy: the game-side dispatcher owns targets and guards.
 * Only JDK types cross the extension bridge; JNA is supplied by the game runtime.
 */
public final class JvmTiBackend {
    private static final int JVMTI_VERSION_1_2 = 0x30010200;
    private static final int JNI_VERSION_1_6 = 0x00010006;
    private static final int JNI_GET_ENV = 6;
    private static final int JNI_NEW_GLOBAL_REF = 21;
    private static final int JNI_DELETE_GLOBAL_REF = 22;
    private static final int JNI_NEW_LOCAL_REF = 25;
    // Function numbers in jvmti.h are one-based; table offsets are zero-based.
    private static final int SET_EVENT_NOTIFICATION = 1;
    private static final int IS_MODIFIABLE_CLASS = 44;
    private static final int ALLOCATE = 45;
    private static final int DEALLOCATE = 46;
    private static final int GET_CAPABILITIES = 88;
    private static final int SET_EVENT_CALLBACKS = 121;
    private static final int ADD_CAPABILITIES = 141;
    private static final int RETRANSFORM_CLASSES = 151;
    private static final int CLASS_FILE_LOAD_HOOK = 54;
    private static final int CLASS_PREPARE = 56;
    private static final int CAP_RETRANSFORM = 37;
    private static final int CAPABILITIES_SIZE = 16;
    private static final int CALLBACKS_SIZE = 37 * Native.POINTER_SIZE;
    private static final Map<String, ?> OBJECT_ARGUMENTS = Map.of(Library.OPTION_ALLOW_OBJECTS, true);

    private final Consumer<String> logger;
    private final Set<Class<?>> collected = Collections.newSetFromMap(new WeakHashMap<>());
    private final ThreadLocal<Boolean> inCallback = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> requesting = ThreadLocal.withInitial(() -> false);
    // Registered native callback pointers must always retain their Java callback objects.
    private final PrepareCallback prepareCallback = this::onClassPrepare;
    private final LoadCallback loadCallback = this::onClassFileLoad;
    private Pointer javaVM;
    private Pointer collectorEnv;
    private Pointer transformEnv;
    private volatile boolean active;
    private volatile ClassFileTransformer transformer;
    private volatile ClassFileTransformer observer;

    private JvmTiBackend(Consumer<String> logger) {
        this.logger = logger;
    }

    public static Map<String, Object> open(Consumer<String> logger) {
        JvmTiBackend backend = new JvmTiBackend(logger);
        if (!backend.initialize()) return Map.of();
        return Map.of(
                "activate", (BiPredicate<ClassFileTransformer, ClassFileTransformer>) backend::activate,
                "deactivate", (Runnable) backend::deactivate,
                "classes", (Supplier<Class<?>[]>) backend::classes,
                "modifiable", (Predicate<Class<?>>) backend::isModifiable,
                "retransform", (Predicate<Class<?>[]>) backend::retransform);
    }

    private boolean initialize() {
        // The JDK 17 capability bit-field layout below is supported on 64-bit little-endian JVMs.
        if (Native.POINTER_SIZE != 8 || ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            log("Unsupported native layout; retaining the existing transformation backend");
            return false;
        }
        try {
            Path javaHome = Path.of(System.getProperty("java.home"));
            Path library = javaHome.resolve("bin/server/jvm.dll");
            if (!Files.isRegularFile(library)) library = javaHome.resolve("lib/server/libjvm.so");
            if (!Files.isRegularFile(library)) library = javaHome.resolve("lib/server/libjvm.dylib");
            String libraryName = Files.isRegularFile(library) ? library.toString() : "jvm";
            Function getVMs = Function.getFunction(libraryName, "JNI_GetCreatedJavaVMs");
            PointerByReference vm = new PointerByReference();
            IntByReference count = new IntByReference();
            if (!ok(getVMs.invokeInt(new Object[]{vm, 1, count}), "JNI_GetCreatedJavaVMs")
                    || count.getValue() != 1 || vm.getValue() == null) return false;
            javaVM = vm.getValue();
            // Warm JNI object conversion before installing callbacks to avoid recursive class loading.
            Pointer jni = environment(JNI_VERSION_1_6);
            if (jni == null) return false;
            Pointer reference = globalReference(jni, JvmTiBackend.class);
            if (reference == null) return false;
            try {
                if (javaObject(jni, reference, Class.class) != JvmTiBackend.class) return false;
            } finally {
                deleteGlobalReference(jni, reference);
            }
            collectorEnv = environment(JVMTI_VERSION_1_2);
            if (collectorEnv != null) {
                try (Memory callbacks = callbacks(prepareCallback, 6)) {
                    if (ok(function(collectorEnv, SET_EVENT_CALLBACKS).invokeInt(new Object[]{
                            collectorEnv, callbacks, CALLBACKS_SIZE}), "Collector callbacks")) {
                        ok(notifyEvent(collectorEnv, CLASS_PREPARE, true), "ClassPrepare enable");
                    }
                }
            }
            log("Prepared current-JVM channel; collected classes are held weakly");
            return true;
        } catch (Throwable t) {
            log("Initialization failed: " + t);
            // A partially registered collector still needs its callback object kept alive.
            return collectorEnv != null;
        }
    }

    private synchronized boolean activate(ClassFileTransformer transform, ClassFileTransformer capture) {
        if (transform == null || capture == null) return false;
        if (active) return true;
        try {
            // A separate, late-created environment runs after the startup Instrumentation environments.
            if (transformEnv == null) transformEnv = environment(JVMTI_VERSION_1_2);
            if (transformEnv == null || !requestRetransformation()) return false;
            transformer = transform;
            observer = capture;
            try (Memory callbacks = callbacks(loadCallback, 4)) {
                if (!ok(function(transformEnv, SET_EVENT_CALLBACKS).invokeInt(new Object[]{
                        transformEnv, callbacks, CALLBACKS_SIZE}), "Transform callbacks")) return false;
            }
            active = true;
            if (!ok(notifyEvent(transformEnv, CLASS_FILE_LOAD_HOOK, true), "ClassFileLoadHook enable")) {
                active = false;
                return false;
            }
            log("Native retransformation enabled");
            return true;
        } catch (Throwable t) {
            active = false;
            log("Activation failed: " + t);
            return false;
        }
    }

    private synchronized void deactivate() {
        if (!active || transformEnv == null) return;
        active = false;
        try {
            if (ok(notifyEvent(transformEnv, CLASS_FILE_LOAD_HOOK, false), "ClassFileLoadHook disable")) {
                // Keep both callbacks alive while their addresses remain installed in the VM.
                log("Native retransformation disabled");
            }
        } catch (Throwable t) {
            log("Deactivation failed: " + t);
        }
    }

    private boolean requestRetransformation() {
        try (Memory request = new Memory(CAPABILITIES_SIZE);
             Memory granted = new Memory(CAPABILITIES_SIZE)) {
            request.clear();
            granted.clear();
            request.setByte(CAP_RETRANSFORM / 8, (byte) (1 << (CAP_RETRANSFORM % 8)));
            if (!ok(function(transformEnv, ADD_CAPABILITIES).invokeInt(
                    new Object[]{transformEnv, request}), "AddCapabilities")) return false;
            if (!ok(function(transformEnv, GET_CAPABILITIES).invokeInt(
                    new Object[]{transformEnv, granted}), "GetCapabilities")) return false;
            boolean supported = (granted.getByte(CAP_RETRANSFORM / 8) & (1 << (CAP_RETRANSFORM % 8))) != 0;
            if (!supported) log("VM did not grant retransformation capability");
            return supported;
        }
    }

    private Class<?>[] classes() {
        synchronized (collected) {
            return collected.toArray(Class<?>[]::new);
        }
    }

    private boolean isModifiable(Class<?> type) {
        if (type == null || type.isArray() || type.isPrimitive()) return false;
        Pointer env = transformEnv != null ? transformEnv : collectorEnv;
        if (env == null) return false;
        try (Memory result = new Memory(1)) {
            result.clear();
            int code = (Integer) function(env, IS_MODIFIABLE_CLASS).invoke(Integer.class,
                    new Object[]{env, type, result}, OBJECT_ARGUMENTS);
            return code == 0 && result.getByte(0) != 0;
        } catch (Throwable t) {
            log("Class capability lookup failed: " + t);
            return false;
        }
    }

    private boolean retransform(Class<?>[] types) {
        if (!active || types == null || types.length == 0) return false;
        Pointer jni = environment(JNI_VERSION_1_6);
        if (jni == null) return false;
        Pointer[] references = new Pointer[types.length];
        try (Memory array = new Memory((long) types.length * Native.POINTER_SIZE)) {
            for (int i = 0; i < types.length; i++) {
                if (!isModifiable(types[i])) return false;
                // JNI locals created inside a JNA invocation expire when that invocation returns.
                // Temporary global references keep the exact Class alive across the native calls.
                references[i] = globalReference(jni, types[i]);
                if (references[i] == null) return false;
                array.setPointer((long) i * Native.POINTER_SIZE, references[i]);
            }
            requesting.set(true);
            try {
                return ok(function(transformEnv, RETRANSFORM_CLASSES).invokeInt(
                        new Object[]{transformEnv, types.length, array}), "RetransformClasses");
            } finally {
                requesting.remove();
            }
        } catch (Throwable t) {
            log("Retransformation failed: " + t);
            return false;
        } finally {
            for (Pointer reference : references) deleteGlobalReference(jni, reference);
        }
    }

    private void onClassPrepare(Pointer env, Pointer jni, Pointer thread, Pointer klass) {
        if (jni == null || klass == null || inCallback.get()) return;
        inCallback.set(true);
        try {
            Class<?> type = javaObject(jni, klass, Class.class);
            if (type != null) {
                synchronized (collected) {
                    collected.add(type);
                }
            }
        } catch (Throwable t) {
            log("Class collection failed: " + t);
        } finally {
            inCallback.remove();
        }
    }

    private void onClassFileLoad(Pointer env, Pointer jni, Pointer redefined, Pointer loader,
                                 String name, Pointer domain, int length, Pointer data,
                                 Pointer outputLength, Pointer outputData) {
        if (!active || data == null || length <= 0 || inCallback.get()) return;
        inCallback.set(true);
        Pointer allocated = null;
        try {
            Class<?> type = javaObject(jni, redefined, Class.class);
            ClassLoader classLoader = javaObject(jni, loader, ClassLoader.class);
            byte[] input = data.getByteArray(0, length);
            // Other agents' retransforms are observed for invalidation without applying ECA changes.
            byte[] output = redefined == null || requesting.get()
                    ? transformer.transform(classLoader, name, type, null, input) : null;
            if (output != null) {
                PointerByReference destination = new PointerByReference();
                if (!ok(function(env, ALLOCATE).invokeInt(new Object[]{
                        env, (long) output.length, destination}), "Allocate")) return;
                allocated = destination.getValue();
                if (allocated == null) return;
                allocated.write(0, output, 0, output.length);
                outputLength.setInt(0, output.length);
                outputData.setPointer(0, allocated);
                allocated = null; // The JVM owns the returned buffer from this point onward.
            }
            // Confirmation follows successful native allocation, not just Java-side transformation.
            observer.transform(classLoader, name, type, null, output == null ? input : output);
        } catch (Throwable t) {
            log("Class callback failed: " + t);
        } finally {
            if (allocated != null) {
                try {
                    ok(function(env, DEALLOCATE).invokeInt(new Object[]{env, allocated}), "Deallocate");
                } catch (Throwable t) {
                    log("Callback buffer release failed: " + t);
                }
            }
            inCallback.remove();
        }
    }

    private Pointer environment(int version) {
        if (javaVM == null) return null;
        try {
            PointerByReference result = new PointerByReference();
            int code = function(javaVM, JNI_GET_ENV).invokeInt(new Object[]{javaVM, result, version});
            return ok(code, "GetEnv") ? result.getValue() : null;
        } catch (Throwable t) {
            log("Environment lookup failed: " + t);
            return null;
        }
    }

    private static Pointer globalReference(Pointer jni, Class<?> type) {
        return (Pointer) function(jni, JNI_NEW_GLOBAL_REF).invoke(Pointer.class,
                new Object[]{jni, type}, OBJECT_ARGUMENTS);
    }

    private void deleteGlobalReference(Pointer jni, Pointer reference) {
        if (reference == null) return;
        try {
            function(jni, JNI_DELETE_GLOBAL_REF).invokeVoid(new Object[]{jni, reference});
        } catch (Throwable t) {
            log("Global reference release failed: " + t);
        }
    }

    private static <T> T javaObject(Pointer jni, Pointer reference, Class<T> type) {
        if (jni == null || reference == null) return null;
        // invokeObject returns the JNI reference to Java while the native frame is still valid.
        return type.cast(function(jni, JNI_NEW_LOCAL_REF).invoke(type,
                new Object[]{jni, reference}, OBJECT_ARGUMENTS));
    }

    private static Function function(Pointer environment, int index) {
        Pointer table = environment.getPointer(0);
        return Function.getFunction(table.getPointer((long) index * Native.POINTER_SIZE), Function.C_CONVENTION);
    }

    private static Memory callbacks(Callback callback, int index) {
        Memory memory = new Memory(CALLBACKS_SIZE);
        memory.clear();
        memory.setPointer((long) index * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(callback));
        return memory;
    }

    private static int notifyEvent(Pointer environment, int event, boolean enabled) {
        return function(environment, SET_EVENT_NOTIFICATION).invokeInt(
                new Object[]{environment, enabled ? 1 : 0, event, Pointer.NULL});
    }

    private boolean ok(int code, String operation) {
        if (code != 0) log(operation + " failed, code=" + code);
        return code == 0;
    }

    private void log(String message) {
        try {
            logger.accept("[JvmTiBackend] " + message);
        } catch (Throwable ignored) {
            // No Java exception may escape a native callback boundary.
        }
    }

    public interface PrepareCallback extends Callback {
        void invoke(Pointer env, Pointer jni, Pointer thread, Pointer klass);
    }

    public interface LoadCallback extends Callback {
        void invoke(Pointer env, Pointer jni, Pointer redefined, Pointer loader, String name,
                    Pointer domain, int length, Pointer data, Pointer outputLength, Pointer outputData);
    }
}
