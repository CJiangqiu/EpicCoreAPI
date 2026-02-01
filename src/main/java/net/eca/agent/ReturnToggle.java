package net.eca.agent;

import net.eca.util.EcaLogger;

import java.util.Collections;
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

    /**
     * 包名白名单 - 用于保护重要的库和框架免受危险操作影响
     * 此类维护一个受保护的包名前缀列表，用于：
     * - AllReturn 转换排除
     * - 其他危险操作的安全检查
     */
    public static final class PackageWhitelist {

        /**
         * 预设保护包名前缀（不可变）
         */
        private static final Set<String> BUILTIN = Set.of(
            // JDK
            "java.", "javax.", "sun.", "jdk.", "com.sun.",
            // Minecraft & Forge
            "net.minecraft.", "com.mojang.", "net.minecraftforge.", "cpw.mods.", "net.minecrell.",
            "net.fabricmc.", "org.sinytra.connector.",
            // 字节码框架
            "org.spongepowered.", "org.objectweb.asm.", "com.llamalad7.mixinextras.",
            // LWJGL
            "org.lwjgl.",
            // 常用库
            "com.google.", "org.apache.", "io.netty.", "it.unimi.", "org.slf4j.",
            "org.joml.", "com.fasterxml.", "org.openjdk.", "org.checkerframework.", "com.electronwill.",
            "org.antlr.", "com.github.benmanes.caffeine.", "org.codehaus.", "org.yaml.", "com.typesafe.",
            "joptsimple.", "org.jline.", "org.w3c.", "org.xml.", "oshi.", "com.ibm.",
            "netscape.javascript.", "kotlin.", "org.ow2.", "ca.weblite.", "paulscode.",
            // 常见 Mod
            "mezz.jei.", "snownee.jade.", "software.bernie.geckolib.", "com.github.alexthe666.citadel.", "me.jellysquid.mods.sodium.",
            "org.embeddedt.modernfix.", "org.embeddedt.embeddium.", "com.supermartijn642.fusion.", "com.supermartijn642.core.", "dev.architectury.",
            "dev.kosmx.playerAnim.", "dev.tr7zw.entityculling.",  "malte0811.ferritecore.", "net.irisshaders.",
            "net.raphimc.immediatelyfast.", "net.tslat.smartbrainlib.", "top.theillusivec4.caelus.", "top.theillusivec4.curios.", "virtuoel.pehkui.",
            // 友好mod
            "net.eca.","net.mcreator.ultimateskeletons."
        );

        /**
         * 运行时添加的自定义保护
         */
        private static final Set<String> custom = ConcurrentHashMap.newKeySet();

        /**
         * 检查类是否受保护（内部名格式，使用 / 分隔）
         * @param internalClassName 内部类名，如 "net/minecraft/world/entity/Entity"
         * @return true 如果类受保护
         */
        public static boolean isProtected(String internalClassName) {
            if (internalClassName == null) {
                return true;
            }
            return isProtectedBinary(internalClassName.replace('/', '.'));
        }

        /**
         * 检查类是否受保护（二进制名格式，使用 . 分隔）
         * @param binaryClassName 二进制类名，如 "net.minecraft.world.entity.Entity"
         * @return true 如果类受保护
         */
        public static boolean isProtectedBinary(String binaryClassName) {
            if (binaryClassName == null) {
                return true;
            }
            for (String prefix : BUILTIN) {
                if (binaryClassName.startsWith(prefix)) {
                    return true;
                }
            }
            for (String prefix : custom) {
                if (binaryClassName.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 添加自定义保护包名前缀
         * @param packagePrefix 包名前缀，如 "com.example."
         */
        public static void addProtection(String packagePrefix) {
            String normalized = normalize(packagePrefix);
            if (normalized != null) {
                custom.add(normalized);
            }
        }

        /**
         * 移除自定义保护包名前缀（不能移除预设保护）
         * @param packagePrefix 包名前缀
         * @return true 如果成功移除
         */
        public static boolean removeProtection(String packagePrefix) {
            String normalized = normalize(packagePrefix);
            if (normalized == null) {
                return false;
            }
            if (BUILTIN.contains(normalized)) {
                return false;
            }
            return custom.remove(normalized);
        }

        /**
         * 获取所有保护包名前缀
         * @return 保护列表副本
         */
        public static Set<String> getAll() {
            Set<String> all = new HashSet<>(BUILTIN);
            all.addAll(custom);
            return Collections.unmodifiableSet(all);
        }

        /**
         * 标准化包名前缀（确保以 . 结尾）
         */
        private static String normalize(String prefix) {
            if (prefix == null || prefix.isEmpty()) {
                return null;
            }
            String normalized = prefix.replace('/', '.');
            if (!normalized.endsWith(".")) {
                normalized = normalized + ".";
            }
            return normalized;
        }

        private PackageWhitelist() {}
    }
}
