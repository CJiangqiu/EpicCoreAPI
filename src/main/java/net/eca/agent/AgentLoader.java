package net.eca.agent;

import sun.misc.Unsafe;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Handles self-attach and agent loading.
 * Called from CoreMod (EcaTransformationService) at the earliest possible stage.
 */
public final class AgentLoader {

    private static final String AGENT_RESOURCE_PATH = "/net/eca/agent/agent.jar";

    //使用 Unsafe 修改 HotSpotVirtualMachine.ALLOW_ATTACH_SELF
    public static void enableSelfAttach() {
        try {
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);

            Class<?> vmClass = Class.forName("sun.tools.attach.HotSpotVirtualMachine");
            Field allowField = vmClass.getDeclaredField("ALLOW_ATTACH_SELF");

            Object base = unsafe.staticFieldBase(allowField);
            long offset = unsafe.staticFieldOffset(allowField);
            unsafe.putObject(base, offset, Boolean.TRUE);

            AgentLogWriter.info("[AgentLoader] Self-attach enabled via Unsafe");
        } catch (Throwable t) {
            // 降级到系统属性
            System.setProperty("jdk.attach.allowAttachSelf", "true");
            AgentLogWriter.info("[AgentLoader] Self-attach enabled via system property (fallback)");
        }
    }

    //提取 agent.jar 并附着到当前 JVM，传入调用者类名用于桥接 Instrumentation
    public static boolean loadAgent() {
        if (EcaAgent.getInstrumentation() != null) {
            return true;
        }

        try {
            Path tmpDir = Files.createTempDirectory("eca-agent");
            Path agentJar = tmpDir.resolve("agent.jar");

            try (InputStream in = AgentLoader.class.getResourceAsStream(AGENT_RESOURCE_PATH)) {
                if (in == null) {
                    throw new FileNotFoundException("Agent resource not found: " + AGENT_RESOURCE_PATH);
                }
                Files.copy(in, agentJar, REPLACE_EXISTING);
            }
            agentJar.toFile().deleteOnExit();
            tmpDir.toFile().deleteOnExit();

            String pid = String.valueOf(ProcessHandle.current().pid());
            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid);

            try {
                // 传入调用者类名，让 agentmain 桥接 Instrumentation 到调用者的 ClassLoader
                String callerClassName = AgentLoader.class.getName();
                vmClass.getMethod("loadAgent", String.class, String.class)
                    .invoke(vm, agentJar.toAbsolutePath().toString(), callerClassName);
            } finally {
                vmClass.getMethod("detach").invoke(vm);
            }

            boolean success = EcaAgent.getInstrumentation() != null;
            AgentLogWriter.info("[AgentLoader] Agent load " + (success ? "succeeded" : "FAILED"));
            return success;

        } catch (Throwable t) {
            AgentLogWriter.error("[AgentLoader] Failed to load agent", t);
            return false;
        }
    }

    private AgentLoader() {}
}
