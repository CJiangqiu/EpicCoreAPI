package net.eca.agent;

import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * Instrumentation entry point for defensive entity-state protection and compatibility
 * within the game JVM. Transformation targets and guards are defined by the coremod layer.
 * This entry point stores Instrumentation and bridges it across ECA ClassLoaders.
 */
public final class EcaAgent {

    private static volatile Instrumentation instrumentation;

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        instrumentation = inst;
        AgentLogWriter.info("[EcaAgent] Instrumentation acquired (agent ClassLoader)");

        // The coremod loader may hold a separate EcaAgent class with its own static state.
        if (args != null && !args.isBlank()) {
            bridgeInstrumentation(args, inst);
        }
        signalRelaunchReady();
    }

    // Share the JVM-provided handle with ECA's copy in the caller's ClassLoader.
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
                // Identical class objects already share the same Instrumentation field.
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

    // Reuse startup instrumentation when available, avoiding an additional self-attach.
    public static boolean adoptSystemInstrumentation() {
        if (instrumentation != null) return true;
        try {
            Class<?> systemAgent = Class.forName(EcaAgent.class.getName(), false,
                    ClassLoader.getSystemClassLoader());
            if (systemAgent == EcaAgent.class) return false;
            Object candidate = systemAgent.getMethod("getInstrumentation").invoke(null);
            if (!(candidate instanceof Instrumentation systemInstrumentation)) return false;
            instrumentation = systemInstrumentation;
            AgentLogWriter.info("[EcaAgent] Adopted startup Instrumentation");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void signalRelaunchReady() {
        String pipe = System.getProperty("forgevm.relaunch.readyPipe");
        String nonce = System.getProperty("forgevm.relaunch.readyNonce");
        if (pipe == null || pipe.isBlank() || nonce == null || nonce.isBlank()) return;
        try (RandomAccessFile ready = new RandomAccessFile(pipe, "rw")) {
            ready.write((nonce + "\n").getBytes(StandardCharsets.UTF_8));
            System.clearProperty("forgevm.relaunch.readyNonce");
            AgentLogWriter.info("[EcaAgent] Trusted relaunch handoff completed");
        } catch (Throwable t) {
            AgentLogWriter.warn("[EcaAgent] Trusted relaunch handoff failed: " + t.getMessage());
        }
    }

    private EcaAgent() {}
}
