package net.eca.coremod;

import net.eca.agent.AgentLogWriter;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

/**
 * Optional runtime-extension bridge. Platform artifacts contain only this inert boundary.
 */
public final class RuntimeExtensionBridge {
    public static final String EARLY_DISPLAY_TARGET = "net/minecraftforge/fml/earlydisplay/DisplayWindow";
    private static final String PROVIDER = "net.eca.pro.EcaProRuntime";
    private static final String EARLY_DISPLAY_TRANSFORMER = "net.eca.coremod.LoadingScreenTransformer";
    private static volatile Class<?> provider;
    private static volatile Class<?> earlyDisplayTransformer;
    private static volatile boolean resolved;
    private static volatile boolean earlyDisplayResolved;
    private static volatile boolean earlyPrepared;

    private RuntimeExtensionBridge() {
    }

    public static synchronized void prepareEarly() {
        if (earlyPrepared) return;
        earlyPrepared = true;
        AgentLogWriter.resetForNewSession();
        invoke("prepareEarly");
    }

    public static void afterAgentReady() {
        invoke("afterAgentReady");
    }

    public static void refreshInterception() {
        invoke("refreshInterception");
    }

    public static boolean hasEarlyDisplayTransformer() {
        return resolveEarlyDisplayTransformer() != null;
    }

    public static void installEarlyDisplayTransformer(Instrumentation instrumentation) {
        Class<?> type = resolveEarlyDisplayTransformer();
        if (type == null || instrumentation == null) return;
        try {
            Object instance = type.getDeclaredConstructor().newInstance();
            if (!(instance instanceof ClassFileTransformer transformer)) return;
            instrumentation.addTransformer(transformer, true);
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (!EARLY_DISPLAY_TARGET.replace('/', '.').equals(loaded.getName())) continue;
                instrumentation.retransformClasses(loaded);
                break;
            }
            AgentLogWriter.info("[RuntimeExtensionBridge] Early display transformer registered");
        } catch (Throwable t) {
            AgentLogWriter.info("[RuntimeExtensionBridge] Early display transformer failed: " + rootMessage(t));
        }
    }

    public static byte[] transformEarlyDisplay(byte[] classBytes) {
        Class<?> type = resolveEarlyDisplayTransformer();
        if (type == null) return null;
        try {
            Method method = type.getMethod("transform", byte[].class);
            Object result = method.invoke(null, (Object) classBytes);
            return result instanceof byte[] transformed ? transformed : null;
        } catch (Throwable t) {
            AgentLogWriter.info("[RuntimeExtensionBridge] Early display transformation failed: " + rootMessage(t));
            return null;
        }
    }

    private static Object invoke(String methodName) {
        return invoke(methodName, new Class<?>[0]);
    }

    private static Object invoke(String methodName, Class<?>[] parameterTypes, Object... arguments) {
        Class<?> type = resolveProvider();
        if (type == null) return null;
        try {
            Method method = type.getMethod(methodName, parameterTypes);
            return method.invoke(null, arguments);
        } catch (Throwable t) {
            AgentLogWriter.info("[RuntimeExtensionBridge] " + methodName + " failed: " + rootMessage(t));
            return null;
        }
    }

    private static Class<?> resolveProvider() {
        if (resolved) return provider;
        synchronized (RuntimeExtensionBridge.class) {
            if (resolved) return provider;
            try {
                provider = Class.forName(PROVIDER, true, RuntimeExtensionBridge.class.getClassLoader());
            } catch (ClassNotFoundException ignored) {
                provider = null;
            } catch (Throwable t) {
                AgentLogWriter.info("[RuntimeExtensionBridge] Provider initialization failed: " + rootMessage(t));
            }
            resolved = true;
            return provider;
        }
    }

    private static Class<?> resolveEarlyDisplayTransformer() {
        if (earlyDisplayResolved) return earlyDisplayTransformer;
        synchronized (RuntimeExtensionBridge.class) {
            if (earlyDisplayResolved) return earlyDisplayTransformer;
            try {
                earlyDisplayTransformer = Class.forName(EARLY_DISPLAY_TRANSFORMER, true,
                        RuntimeExtensionBridge.class.getClassLoader());
            } catch (ClassNotFoundException ignored) {
                earlyDisplayTransformer = null;
            } catch (Throwable t) {
                AgentLogWriter.info("[RuntimeExtensionBridge] Early display provider failed: " + rootMessage(t));
            }
            earlyDisplayResolved = true;
            return earlyDisplayTransformer;
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
