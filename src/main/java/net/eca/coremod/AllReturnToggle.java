package net.eca.coremod;

import net.eca.coremod.EarlyLogWriter;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runtime toggle for AllReturn functionality.
 * When enabled, transformed classes' void/boolean methods will early-return.
 *
 * Guards are injected at class load time via EcaClassTransformer.
 * This class only controls the runtime on/off behavior.
 */
public final class AllReturnToggle {

    // 注入字节码通过 System.getProperties().get(CHECKER_KEY) 间接调用，避免 classloader 隔离问题
    static final String CHECKER_KEY = "eca.allreturn.checker";

    private static volatile boolean enabled = false;
    private static final Set<String> allowedPrefixes = ConcurrentHashMap.newKeySet();
    private static final Set<String> transformedClasses = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedModRoots = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Integer> networkBypassDepth = new ThreadLocal<>();

    //运行时检查：该类的方法是否应该提前返回
    public static boolean shouldReturn(String internalClassName) {
        if (!enabled) return false;
        if (networkBypassDepth.get() != null) return false;
        if (TransformerWhitelist.isProtectedInternal(internalClassName)) return false;
        return matchesPrefix(internalClassName);
    }

    //加载时检查：该类是否应该被注入 guard（不在白名单中的类都注入，运行时由 shouldReturn 控制开关）
    public static boolean shouldInjectGuard(String internalClassName) {
        if (internalClassName == null) return false;
        ensureCheckerRegistered();
        return !TransformerWhitelist.isProtectedInternal(internalClassName);
    }

    private static volatile boolean checkerRegistered = false;

    //确保 Predicate checker 已注册到 System.getProperties()（注入字节码通过此间接调用）
    @SuppressWarnings("unchecked")
    private static synchronized void ensureCheckerRegistered() {
        if (checkerRegistered) return;
        Predicate<String> checker = AllReturnToggle::shouldReturn;
        System.getProperties().put(CHECKER_KEY, checker);
        checkerRegistered = true;
        EarlyLogWriter.info("[AllReturnToggle] Registered checker to System.getProperties()");
    }

    private static boolean matchesPrefix(String internalClassName) {
        for (String prefix : allowedPrefixes) {
            if (internalClassName.startsWith(prefix)) return true;
        }
        return false;
    }

    // ==================== 开关控制 ====================

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        if (value) ensureCheckerRegistered();
        enabled = value;
        EarlyLogWriter.info("[AllReturnToggle] Enabled: " + value);
    }

    // ==================== 包前缀管理 ====================

    public static void addAllowedPrefix(String internalPrefix) {
        String normalized = normalize(internalPrefix);
        if (normalized != null) {
            allowedPrefixes.add(normalized);
            String modRoot = extractModRoot(normalized);
            if (modRoot != null && loggedModRoots.add(modRoot)) {
                EarlyLogWriter.info("[AllReturnToggle] Added mod: " + modRoot);
            }
        }
    }

    // 提取 mod 根包（前3段路径，如 "com/example/mymod/"）
    private static String extractModRoot(String internalPrefix) {
        int slashCount = 0;
        for (int i = 0; i < internalPrefix.length(); i++) {
            if (internalPrefix.charAt(i) == '/') {
                slashCount++;
                if (slashCount == 3) {
                    return internalPrefix.substring(0, i + 1);
                }
            }
        }
        return internalPrefix;
    }

    public static void removeAllowedPrefix(String internalPrefix) {
        String normalized = normalize(internalPrefix);
        if (normalized != null) allowedPrefixes.remove(normalized);
    }

    public static Set<String> getAllowedPrefixes() {
        return new HashSet<>(allowedPrefixes);
    }

    // ==================== 已转换类追踪 ====================

    public static void registerTransformed(String internalClassName) {
        if (internalClassName != null) transformedClasses.add(internalClassName);
    }

    public static Set<String> getTransformedClasses() {
        return new HashSet<>(transformedClasses);
    }

    // 网络基础调用及其同步辅助方法必须完整执行，豁免不能泄漏到其他线程或后续处理器。
    /**
     * Executes a synchronous network operation without AllReturn interception on this thread.
     * @param operation the codec or protocol validation operation
     * @param <T> the operation result type
     * @return the original operation result
     */
    public static <T> T withNetworkBypass(Supplier<T> operation) {
        Integer previousDepth = networkBypassDepth.get();
        networkBypassDepth.set(previousDepth == null ? 1 : previousDepth + 1);
        try {
            return operation.get();
        } finally {
            if (previousDepth == null) {
                networkBypassDepth.remove();
            } else {
                networkBypassDepth.set(previousDepth);
            }
        }
    }

    // ==================== 清理 ====================

    public static void clearAll() {
        enabled = false;
        allowedPrefixes.clear();
        loggedModRoots.clear();
        // 已加载类的 guard 仍然存在，检查器和转换记录跟随进程保留。
    }

    private static String normalize(String prefix) {
        if (prefix == null || prefix.isEmpty()) return null;
        String n = prefix.replace('.', '/');
        if (!n.endsWith("/")) n += "/";
        return n;
    }

    private AllReturnToggle() {}
}
