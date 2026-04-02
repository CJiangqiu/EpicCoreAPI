package net.eca.coremod;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized whitelist for classes/packages that should NOT be transformed by ECA.
 * Used by EcaClassTransformer, HealthAnalyzer, and any future transformers.
 */
public final class TransformerWhitelist {

    private static final Set<String> BUILTIN = Set.of(
        // JDK
        "java.", "javax.", "sun.", "jdk.", "com.sun.",
        // Minecraft & Mod API
        "net.minecraft.", "com.mojang.", "net.minecraftforge.", "cpw.mods.", "net.minecrell.",
        "net.fabricmc.", "org.sinytra.connector.",
        // 常用库和mod
        "org.lwjgl.", "com.google.", "org.apache.", "io.netty.", "it.unimi.", "org.slf4j.",
        "org.joml.", "com.fasterxml.", "org.openjdk.", "org.checkerframework.", "com.electronwill.",
        "org.antlr.", "com.github.benmanes.caffeine.", "org.codehaus.", "org.yaml.", "com.typesafe.",
        "joptsimple.", "org.jline.", "org.w3c.", "org.xml.", "oshi.", "com.ibm.",
        "netscape.javascript.", "kotlin.", "kotlinx.", "org.ow2.", "ca.weblite.", "paulscode.",
        "org.spongepowered.", "org.objectweb.asm.", "com.llamalad7.mixinextras.",
        "mezz.jei.", "snownee.jade.", "software.bernie.", "com.github.alexthe666.citadel.",
        "me.jellysquid.mods.sodium.", "com.supermartijn642.fusion.", "com.supermartijn642.core.",
        "dev.architectury.", "dev.kosmx.playerAnim.", "dev.tr7zw.entityculling.",
        "malte0811.ferritecore.", "net.irisshaders.", "net.raphimc.immediatelyfast.",
        "net.tslat.smartbrainlib.", "top.theillusivec4.caelus.", "top.theillusivec4.curios.",
        "virtuoel.pehkui.","net.caffeinemc.", "org.embeddedt.", "oculus.", "kroppeb.", "io.github.douira.",
        "org.anarres.", "net.jodah.","thedarkcolour.", "me.lucko.spark.", "de.odysseus.", "org.jcp.", "com.eliotlash.mclib.",
        "com.mrcrayfish.","com.tterrag.", "oolloo.","native0.",
        "com.chaosthedude.", "net.mehvahdjukaar.", "io.github.flemmli97.", "io.redspace.ironsspellbooks.",
        "com.obscuria.", "jeresources.", "vazkii.patchouli.", "com.bawnorton.mixinsquared.", "terrablender.",
        "vectorwing.farmersdelight.", "jackiecrazy.attributizer.", "com.fe.", "com.mega.",
        // 友好 Mod
        "net.eca.", "net.the_last_sword.", "net.mcreator.ultimateskeletons.","com.core.dream_sakura.","com.github.L_Ender.",
        "com.github.tartaricacid."

    );

    private static final Set<String> custom = Collections.synchronizedSet(new HashSet<>());

    //首段快速索引：提取 "java."→"java", "com.mojang."→"com" 等
    //如果类名的第一段不在此集合中，可以直接跳过整个白名单遍历
    private static final Set<String> FIRST_SEGMENTS = buildFirstSegments();

    private static Set<String> buildFirstSegments() {
        Set<String> segments = new HashSet<>();
        for (String prefix : BUILTIN) {
            int dot = prefix.indexOf('.');
            if (dot > 0) segments.add(prefix.substring(0, dot));
        }
        return segments;
    }

    //结果缓存：避免重复遍历
    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();

    //检查类是否受保护（二进制名格式，如 "net.minecraft.world.entity.Entity"）
    public static boolean isProtected(String binaryClassName) {
        if (binaryClassName == null) return true;

        Boolean cached = CACHE.get(binaryClassName);
        if (cached != null) return cached;

        boolean result = checkProtected(binaryClassName);
        if (CACHE.size() < 10000) {
            CACHE.put(binaryClassName, result);
        }
        return result;
    }

    private static boolean checkProtected(String binaryClassName) {
        //首段快速排除：大部分第三方 mod 类的首段（如 "io"、"com"）会命中，但仍需精确匹配
        int firstDot = binaryClassName.indexOf('.');
        if (firstDot > 0) {
            String firstSeg = binaryClassName.substring(0, firstDot);
            if (!FIRST_SEGMENTS.contains(firstSeg) && !hasCustomWithFirstSegment(firstSeg)) {
                return false;
            }
        }

        for (String prefix : BUILTIN) {
            if (binaryClassName.startsWith(prefix)) return true;
        }
        for (String prefix : custom) {
            if (binaryClassName.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean hasCustomWithFirstSegment(String firstSeg) {
        for (String prefix : custom) {
            if (prefix.startsWith(firstSeg + ".")) return true;
        }
        return false;
    }

    //检查类是否受保护（内部名格式，如 "net/minecraft/world/entity/Entity"）
    public static boolean isProtectedInternal(String internalClassName) {
        if (internalClassName == null) return true;
        return isProtected(internalClassName.replace('/', '.'));
    }

    //运行时添加自定义保护包前缀
    public static void addCustom(String prefix) {
        if (prefix == null || prefix.isEmpty()) return;
        String normalized = prefix.replace('/', '.');
        if (!normalized.endsWith(".")) normalized += ".";
        custom.add(normalized);
        CACHE.clear();
    }

    //移除自定义保护包前缀
    public static boolean removeCustom(String prefix) {
        if (prefix == null || prefix.isEmpty()) return false;
        String normalized = prefix.replace('/', '.');
        if (!normalized.endsWith(".")) normalized += ".";
        boolean removed = custom.remove(normalized);
        if (removed) CACHE.clear();
        return removed;
    }

    //获取所有保护前缀（只读）
    public static Set<String> getAll() {
        Set<String> all = new HashSet<>(BUILTIN);
        all.addAll(custom);
        return Collections.unmodifiableSet(all);
    }

    private TransformerWhitelist() {}
}
