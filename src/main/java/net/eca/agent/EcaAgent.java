package net.eca.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;

/**
 * Ultra-thin Java Agent entry point.
 * Stores Instrumentation and bridges it to the caller's ClassLoader.
 */
public final class EcaAgent {

    private static volatile Instrumentation instrumentation;

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        instrumentation = inst;
        AgentLogWriter.info("[EcaAgent] Instrumentation acquired (agent ClassLoader)");

        // 桥接到调用者的 ClassLoader（CoreMod 层）
        if (args != null) {
            bridgeInstrumentation(args, inst);
        }
    }

    //通过调用者类名找到 CoreMod ClassLoader，反射设置其 EcaAgent.instrumentation
    private static void bridgeInstrumentation(String callerClassName, Instrumentation inst) {
        try {
            Class<?> callerClass = findLoadedClass(inst, callerClassName);
            if (callerClass == null) {
                AgentLogWriter.warn("[EcaAgent] Caller class not found: " + callerClassName);
                return;
            }

            ClassLoader targetLoader = callerClass.getClassLoader();
            if (targetLoader == null) {
                return;
            }

            Class<?> targetEcaAgent = Class.forName("net.eca.agent.EcaAgent", false, targetLoader);
            if (targetEcaAgent == EcaAgent.class) {
                // 同一个类，无需桥接
                return;
            }

            Field instField = targetEcaAgent.getDeclaredField("instrumentation");
            instField.setAccessible(true);
            instField.set(null, inst);

            AgentLogWriter.info("[EcaAgent] Bridged Instrumentation to " + targetLoader.getClass().getSimpleName());
        } catch (Throwable t) {
            AgentLogWriter.warn("[EcaAgent] Bridge failed: " + t.getMessage());
        }
    }

    private static Class<?> findLoadedClass(Instrumentation inst, String className) {
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (clazz.getName().equals(className)) {
                return clazz;
            }
        }
        return null;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    private EcaAgent() {}
}
