package net.eca.agent;

import java.util.Collections;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 包名白名单 - 用于保护重要的库和框架免受危险操作影响
 * 此类维护一个受保护的包名前缀列表，用于：
 * - AllReturn 转换排除
 * - Agent 字节码转换跳过
 * - 其他危险操作的安全检查
 */
public final class PackageWhitelist {

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
        // ECA
        "net.eca."
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
