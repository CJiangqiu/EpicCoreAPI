package net.eca.agent;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

// Agent加载器
/**
 * Utility class for loading the ECA agent at runtime.
 * Handles Unsafe-based permission bypass and VirtualMachine attachment.
 */
public final class AgentLoader {

    private static final String AGENT_RESOURCE_PATH = "/net/eca/agent/agent.jar";
    private static volatile boolean agentLoaded = false;

    // 初始化Agent自附着权限（使用Unsafe）
    /**
     * Enable agent self-attach permission using Unsafe.
     * Should be called in a static block before any agent loading attempt.
     * @return true if successful
     */
    public static boolean enableSelfAttach() {
        try {
            allowAttachSelfViaUnsafe();
            return true;
        } catch (Throwable t) {
            // 降级到系统属性方法
            try {
                System.setProperty("jdk.attach.allowAttachSelf", "true");
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    // 使用Unsafe直接修改HotSpotVirtualMachine.ALLOW_ATTACH_SELF字段
    /**
     * Use Unsafe to directly modify HotSpotVirtualMachine.ALLOW_ATTACH_SELF field.
     * @throws Exception if modification fails
     */
    private static void allowAttachSelfViaUnsafe() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);

        Class<?> vmClass = Class.forName("sun.tools.attach.HotSpotVirtualMachine");
        Field allowAttachSelfField = vmClass.getDeclaredField("ALLOW_ATTACH_SELF");

        Method staticFieldBaseMethod = unsafeClass.getMethod("staticFieldBase", Field.class);
        Method staticFieldOffsetMethod = unsafeClass.getMethod("staticFieldOffset", Field.class);
        Method putObjectMethod = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);

        Object fieldBase = staticFieldBaseMethod.invoke(unsafe, allowAttachSelfField);
        long fieldOffset = (long) staticFieldOffsetMethod.invoke(unsafe, allowAttachSelfField);

        putObjectMethod.invoke(unsafe, fieldBase, fieldOffset, Boolean.TRUE);
    }

    // 加载Agent
    /**
     * Load the ECA agent.
     * Extracts agent.jar from resources and attaches it to the current JVM.
     * @param callerClass the caller class (used for module opening)
     * @return true if agent loaded successfully
     */
    public static synchronized boolean loadAgent(Class<?> callerClass) {
        if (agentLoaded) {
            return true;
        }

        // 先检查是否已经可以访问
        if (isAgentFunctional()) {
            agentLoaded = true;
            return true;
        }

        try {
            // 创建临时目录
            Path tmpDir = Files.createTempDirectory("eca-agent");

            // 提取agent.jar
            Path agentPath = extractAgent(tmpDir.resolve("agent.jar"));

            // 获取当前进程PID
            String pid = String.valueOf(ProcessHandle.current().pid());

            // 使用VirtualMachine附着
            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid);

            try {
                String callerClassName = callerClass != null ? callerClass.getName() : null;
                vmClass.getMethod("loadAgent", String.class, String.class)
                        .invoke(vm, agentPath.toString(), callerClassName);
            } finally {
                vmClass.getMethod("detach").invoke(vm);
            }

            // 验证加载结果
            agentLoaded = isAgentFunctional();
            return agentLoaded;

        } catch (Throwable t) {
            AgentLogWriter.error("[AgentLoader] Failed to load agent", t);
            return false;
        }
    }

    // 提取agent.jar到指定路径
    /**
     * Extract agent.jar from resources to the specified path.
     * @param targetPath the target path
     * @return the absolute path of the extracted file
     * @throws Exception if extraction fails
     */
    private static Path extractAgent(Path targetPath) throws Exception {
        try (InputStream in = AgentLoader.class.getResourceAsStream(AGENT_RESOURCE_PATH)) {
            if (in == null) {
                throw new FileNotFoundException("Agent resource not found: " + AGENT_RESOURCE_PATH);
            }
            Files.copy(in, targetPath, REPLACE_EXISTING);
        }
        targetPath.toFile().deleteOnExit();
        return targetPath.toAbsolutePath();
    }

    // 检查Agent功能是否可用
    /**
     * Check if agent functionality is available.
     * Tests by attempting to access HashMap internal fields via MethodHandles.
     * @return true if agent is functional
     */
    public static boolean isAgentFunctional() {
        try {
            Class<?> hashMapClass = Class.forName("java.util.HashMap");
            Class<?> nodeArrayClass = Class.forName("[Ljava.util.HashMap$Node;");
            MethodHandles.privateLookupIn(hashMapClass, MethodHandles.lookup())
                    .findVarHandle(hashMapClass, "table", nodeArrayClass);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // 检查Agent是否已加载
    /**
     * Check if the agent has been loaded.
     * @return true if loaded
     */
    public static boolean isLoaded() {
        return agentLoaded || EcaAgent.isInitialized();
    }

    private AgentLoader() {}
}
