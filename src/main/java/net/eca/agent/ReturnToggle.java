package net.eca.agent;

import net.eca.util.EcaLogger;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ReturnToggle {
    private static volatile boolean allReturnEnabled = false;
    private static volatile boolean loadTimeTransformEnabled = false;
    private static final Set<String> allowedPackagePrefixes = ConcurrentHashMap.newKeySet();
    private static final Set<String> explicitTargets = ConcurrentHashMap.newKeySet();
    private static final Set<String> activeClassNames = ConcurrentHashMap.newKeySet();
    private static final Set<String> returnHitLogged = ConcurrentHashMap.newKeySet();
    private static final Set<String> modPackagePrefixes = ConcurrentHashMap.newKeySet();

    public static boolean isAllReturnEnabled() {
        return allReturnEnabled;
    }

    public static void setAllReturnEnabled(boolean enabled) {
        allReturnEnabled = enabled;
    }

    public static boolean isLoadTimeTransformEnabled() {
        return loadTimeTransformEnabled;
    }

    public static void setLoadTimeTransformEnabled(boolean enabled) {
        loadTimeTransformEnabled = enabled;
    }

    public static void setModPackagePrefixes(Set<String> prefixes) {
        modPackagePrefixes.clear();
        if (prefixes != null) {
            for (String prefix : prefixes) {
                String normalized = normalizePrefix(prefix);
                if (normalized != null) {
                    modPackagePrefixes.add(normalized);
                }
            }
        }
    }

    public static Set<String> getModPackagePrefixes() {
        return new HashSet<>(modPackagePrefixes);
    }

    public static boolean shouldReturn(String internalClassName) {
        if (!allReturnEnabled) {
            return false;
        }
        if (PackageWhitelist.isProtected(internalClassName)) {
            return false;
        }
        boolean shouldReturn = activeClassNames.contains(internalClassName)
            || matchesAllowedPrefix(internalClassName);
        if (shouldReturn && returnHitLogged.add(internalClassName)) {
            EcaLogger.info("AllReturn hit: {}", internalClassName);
        }
        return shouldReturn;
    }

    public static boolean shouldTransformClass(String internalClassName) {
        if (internalClassName == null) {
            return false;
        }
        if (isExcludedInternal(internalClassName)) {
            return false;
        }
        // 显式目标
        if (explicitTargets.contains(internalClassName)) {
            return true;
        }
        // 允许的包前缀（运行时添加的）
        if (matchesAllowedPrefix(internalClassName)) {
            return true;
        }
        // 加载时转换：检查是否属于任何 mod
        if (loadTimeTransformEnabled && matchesModPrefix(internalClassName)) {
            return true;
        }
        return false;
    }

    public static boolean isExcludedBinaryName(String binaryClassName) {
        if (binaryClassName == null) {
            return true;
        }
        return isExcludedInternal(binaryClassName.replace('.', '/'));
    }

    public static void addAllowedPackagePrefix(String internalPrefix) {
        String normalized = normalizePrefix(internalPrefix);
        if (normalized != null) {
            allowedPackagePrefixes.add(normalized);
        }
    }

    public static void addExplicitTarget(String internalClassName) {
        if (internalClassName != null) {
            explicitTargets.add(internalClassName);
        }
    }

    public static void addExplicitTargets(String[] internalClassNames) {
        if (internalClassNames != null) {
            for (String name : internalClassNames) {
                if (name != null) {
                    explicitTargets.add(name);
                }
            }
        }
    }

    public static boolean isExplicitTarget(String internalClassName) {
        if (internalClassName == null) {
            return false;
        }
        if (isExcludedInternal(internalClassName)) {
            return false;
        }
        return explicitTargets.contains(internalClassName);
    }

    public static void registerTransformedClass(String internalClassName) {
        if (internalClassName != null) {
            activeClassNames.add(internalClassName);
            explicitTargets.remove(internalClassName);
        }
    }

    /**
     * 获取所有已转换的类名（内部格式）
     * 用于验证恢复时 retransform 所有已转换的类
     * @return 已转换类名的副本
     */
    public static Set<String> getActiveClassNames() {
        return new HashSet<>(activeClassNames);
    }

    public static void clearAllTargets() {
        allowedPackagePrefixes.clear();
        explicitTargets.clear();
        activeClassNames.clear();
        returnHitLogged.clear();
        // 注意：不清除 modPackagePrefixes，它是永久的
    }

    private static boolean matchesAllowedPrefix(String internalClassName) {
        if (allowedPackagePrefixes.isEmpty()) {
            return false;
        }
        for (String prefix : allowedPackagePrefixes) {
            if (internalClassName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesModPrefix(String internalClassName) {
        if (modPackagePrefixes.isEmpty()) {
            return false;
        }
        for (String prefix : modPackagePrefixes) {
            if (internalClassName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizePrefix(String internalPrefix) {
        if (internalPrefix == null || internalPrefix.isEmpty()) {
            return null;
        }
        String normalized = internalPrefix.replace('.', '/');
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return normalized;
    }

    private static boolean isExcludedInternal(String internalClassName) {
        return PackageWhitelist.isProtected(internalClassName);
    }

    private ReturnToggle() {}
}
