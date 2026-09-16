package net.eca.coremod;

import net.eca.agent.AgentLogWriter;

import java.lang.reflect.Method;

/**
 * Optional Pro runtime bridge. Non-Pro artifacts do not contain the provider.
 */
public final class ProRuntimeBridge {
    private static final String PROVIDER = "net.eca.pro.EcaProRuntime";
    private static volatile Class<?> provider;
    private static volatile boolean resolved;

    private ProRuntimeBridge() {
    }

    public static void prepareEarly() {
        invoke("prepareEarly");
    }

    public static void afterAgentReady() {
        invoke("afterAgentReady");
    }

    public static void activateFallbackMonitor() {
        invoke("activateFallbackMonitor");
    }

    private static void invoke(String methodName) {
        Class<?> type = resolveProvider();
        if (type == null) return;
        try {
            Method method = type.getMethod(methodName);
            method.invoke(null);
        } catch (Throwable t) {
            AgentLogWriter.info("[ProRuntimeBridge] " + methodName + " failed: " + rootMessage(t));
        }
    }

    private static Class<?> resolveProvider() {
        if (resolved) return provider;
        synchronized (ProRuntimeBridge.class) {
            if (resolved) return provider;
            try {
                provider = Class.forName(PROVIDER, true, ProRuntimeBridge.class.getClassLoader());
            } catch (ClassNotFoundException ignored) {
                provider = null;
            } catch (Throwable t) {
                AgentLogWriter.info("[ProRuntimeBridge] Provider initialization failed: " + rootMessage(t));
            }
            resolved = true;
            return provider;
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
