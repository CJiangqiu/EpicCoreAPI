package net.eca.coremod;

import net.eca.agent.AgentLogWriter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized whitelist for classes/packages that should NOT be transformed by ECA.
 * <p>
 * Two protection levels:
 * <ul>
 *   <li><b>TRANSFORM</b> — Skip ALL ECA transformations (AllReturn + defensive hooks).
 *       Built-in: JDK, Minecraft, Forge, core libraries, ECA itself.</li>
 *   <li><b>ALLRETURN</b> — Skip AllReturn only, defensive hooks still apply.
 *       Built-in: Friendly/compatible mods.</li>
 * </ul>
 * Both levels can be extended at runtime via API or JSON files in config/eca/.
 */
public final class TransformerWhitelist {

    // 跳过全部转换（内置）
    private static final Set<String> SYSTEM = Set.of(
        // JDK
        "java.", "javax.", "sun.", "jdk.", "com.sun.",
        // Minecraft & Mod API
        "net.minecraft.", "com.mojang.", "net.minecraftforge.", "cpw.mods.", "net.minecrell.",
        "net.fabricmc.", "org.sinytra.connector.",
        // 核心库
        "org.lwjgl.", "com.google.", "org.apache.", "io.netty.", "it.unimi.", "org.slf4j.",
        "org.joml.", "com.fasterxml.", "org.openjdk.", "org.checkerframework.", "com.electronwill.",
        "org.antlr.", "com.github.benmanes.caffeine.", "org.codehaus.", "org.yaml.", "com.typesafe.",
        "joptsimple.", "org.jline.", "org.w3c.", "org.xml.", "oshi.", "com.ibm.",
        "netscape.javascript.", "kotlin.", "kotlinx.", "org.ow2.", "ca.weblite.", "paulscode.",
        "org.spongepowered.", "org.objectweb.asm.", "com.llamalad7.mixinextras.", "com.tterrag.", "oolloo.", "native0.",
        "me.lucko.spark.", "de.odysseus.", "org.jcp.", "com.eliotlash.mclib.",
        // ECA 自身
        "net.eca."
    );

    // 只跳过 AllReturn（内置）
    private static final Set<String> FRIENDLY = Set.of(
        "mezz.jei.", "snownee.jade.", "software.bernie.", "com.github.alexthe666.citadel.",
        "me.jellysquid.mods.sodium.", "com.supermartijn642.fusion.", "com.supermartijn642.core.",
        "dev.architectury.", "dev.kosmx.playerAnim.", "dev.tr7zw.entityculling.",
        "malte0811.ferritecore.", "net.irisshaders.", "net.raphimc.immediatelyfast.",
        "net.tslat.smartbrainlib.", "top.theillusivec4.caelus.", "top.theillusivec4.curios.",
        "virtuoel.pehkui.", "net.caffeinemc.", "org.embeddedt.", "oculus.", "kroppeb.", "io.github.douira.",
        "org.anarres.", "net.jodah.", "thedarkcolour.", "com.mrcrayfish.",
        "com.chaosthedude.", "net.mehvahdjukaar.", "io.github.flemmli97.", "io.redspace.ironsspellbooks.",
        "com.obscuria.", "jeresources.", "vazkii.patchouli.", "com.bawnorton.mixinsquared.", "terrablender.",
        "vectorwing.farmersdelight.", "jackiecrazy.attributizer.", "com.fe.", "com.mega.",
        "net.the_last_sword.", "net.mcreator.ultimateskeletons.", "com.core.dream_sakura.", "com.github.L_Ender.",
        "com.github.tartaricacid."
    );

    // 运行时自定义：跳过 AllReturn（API + JSON）
    private static final Set<String> customAllReturn = Collections.synchronizedSet(new HashSet<>());
    // 运行时自定义：跳过全部转换（API + JSON）
    private static final Set<String> customTransform = Collections.synchronizedSet(new HashSet<>());

    private static volatile boolean jsonLoaded = false;

    // ==================== 首段快速索引 ====================

    private static volatile Set<String> SYSTEM_FIRST_SEGMENTS = buildFirstSegments(SYSTEM, customTransform);
    private static volatile Set<String> ALL_FIRST_SEGMENTS = buildAllFirstSegments();

    private static Set<String> buildFirstSegments(Set<String>... prefixSets) {
        Set<String> segments = new HashSet<>();
        for (Set<String> prefixes : prefixSets) {
            for (String prefix : prefixes) {
                int dot = prefix.indexOf('.');
                if (dot > 0) segments.add(prefix.substring(0, dot));
            }
        }
        return segments;
    }

    private static Set<String> buildAllFirstSegments() {
        Set<String> segments = new HashSet<>(SYSTEM_FIRST_SEGMENTS);
        segments.addAll(buildFirstSegments(FRIENDLY, customAllReturn));
        return segments;
    }

    private static void rebuildFirstSegments() {
        SYSTEM_FIRST_SEGMENTS = buildFirstSegments(SYSTEM, customTransform);
        ALL_FIRST_SEGMENTS = buildAllFirstSegments();
    }

    // ==================== 结果缓存 ====================

    private static final ConcurrentHashMap<String, Boolean> CACHE_SYSTEM = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> CACHE_ALL = new ConcurrentHashMap<>();

    // ==================== 系统级保护：所有转换器都跳过 ====================

    //检查类是否属于系统级保护（二进制名格式）
    public static boolean isSystemProtected(String binaryClassName) {
        if (binaryClassName == null) return true;

        Boolean cached = CACHE_SYSTEM.get(binaryClassName);
        if (cached != null) return cached;

        boolean result = checkSystemProtected(binaryClassName);
        if (CACHE_SYSTEM.size() < 10000) {
            CACHE_SYSTEM.put(binaryClassName, result);
        }
        return result;
    }

    private static boolean checkSystemProtected(String binaryClassName) {
        int firstDot = binaryClassName.indexOf('.');
        if (firstDot > 0) {
            String firstSeg = binaryClassName.substring(0, firstDot);
            if (!SYSTEM_FIRST_SEGMENTS.contains(firstSeg)) return false;
        }
        for (String prefix : SYSTEM) {
            if (binaryClassName.startsWith(prefix)) return true;
        }
        for (String prefix : customTransform) {
            if (binaryClassName.startsWith(prefix)) return true;
        }
        return false;
    }

    //检查类是否属于系统级保护（内部名格式）
    public static boolean isSystemProtectedInternal(String internalClassName) {
        if (internalClassName == null) return true;
        return isSystemProtected(internalClassName.replace('/', '.'));
    }

    // ==================== 全级保护：AllReturn 跳过（SYSTEM + FRIENDLY + custom） ====================

    //检查类是否受保护（二进制名格式）— AllReturn 和其他攻击性转换器使用
    public static boolean isProtected(String binaryClassName) {
        if (binaryClassName == null) return true;

        Boolean cached = CACHE_ALL.get(binaryClassName);
        if (cached != null) return cached;

        boolean result = checkProtected(binaryClassName);
        if (CACHE_ALL.size() < 10000) {
            CACHE_ALL.put(binaryClassName, result);
        }
        return result;
    }

    private static boolean checkProtected(String binaryClassName) {
        int firstDot = binaryClassName.indexOf('.');
        if (firstDot > 0) {
            String firstSeg = binaryClassName.substring(0, firstDot);
            if (!ALL_FIRST_SEGMENTS.contains(firstSeg)
                && !hasCustomWithFirstSegment(firstSeg, customAllReturn)
                && !hasCustomWithFirstSegment(firstSeg, customTransform)) {
                return false;
            }
        }

        for (String prefix : SYSTEM) {
            if (binaryClassName.startsWith(prefix)) return true;
        }
        for (String prefix : FRIENDLY) {
            if (binaryClassName.startsWith(prefix)) return true;
        }
        for (String prefix : customAllReturn) {
            if (binaryClassName.startsWith(prefix)) return true;
        }
        for (String prefix : customTransform) {
            if (binaryClassName.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean hasCustomWithFirstSegment(String firstSeg, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (prefix.startsWith(firstSeg + ".")) return true;
        }
        return false;
    }

    //检查类是否受保护（内部名格式）
    public static boolean isProtectedInternal(String internalClassName) {
        if (internalClassName == null) return true;
        return isProtected(internalClassName.replace('/', '.'));
    }

    // ==================== 运行时自定义前缀 ====================

    // --- AllReturn 白名单（跳过 AllReturn，防御 Hook 仍注入） ---

    //添加 AllReturn 白名单前缀
    public static void addAllReturn(String prefix) {
        String normalized = normalizePrefix(prefix);
        if (normalized == null) return;
        customAllReturn.add(normalized);
        rebuildFirstSegments();
        CACHE_ALL.clear();
    }

    //移除 AllReturn 白名单前缀
    public static boolean removeAllReturn(String prefix) {
        String normalized = normalizePrefix(prefix);
        if (normalized == null) return false;
        boolean removed = customAllReturn.remove(normalized);
        if (removed) {
            rebuildFirstSegments();
            CACHE_ALL.clear();
        }
        return removed;
    }

    // --- 转换白名单（跳过全部 ECA 转换） ---

    //添加转换白名单前缀
    public static void addTransform(String prefix) {
        String normalized = normalizePrefix(prefix);
        if (normalized == null) return;
        customTransform.add(normalized);
        rebuildFirstSegments();
        CACHE_SYSTEM.clear();
        CACHE_ALL.clear();
    }

    //移除转换白名单前缀
    public static boolean removeTransform(String prefix) {
        String normalized = normalizePrefix(prefix);
        if (normalized == null) return false;
        boolean removed = customTransform.remove(normalized);
        if (removed) {
            rebuildFirstSegments();
            CACHE_SYSTEM.clear();
            CACHE_ALL.clear();
        }
        return removed;
    }

    // --- 查询 ---

    //获取所有 AllReturn 白名单前缀（内置 + 自定义）
    public static Set<String> getAllAllReturn() {
        Set<String> all = new HashSet<>(FRIENDLY);
        all.addAll(customAllReturn);
        return Collections.unmodifiableSet(all);
    }

    //获取所有转换白名单前缀（内置 + 自定义）
    public static Set<String> getAllTransform() {
        Set<String> all = new HashSet<>(SYSTEM);
        all.addAll(customTransform);
        return Collections.unmodifiableSet(all);
    }

    //获取所有保护前缀（只读，两级合并）
    public static Set<String> getAll() {
        Set<String> all = new HashSet<>(SYSTEM);
        all.addAll(FRIENDLY);
        all.addAll(customAllReturn);
        all.addAll(customTransform);
        return Collections.unmodifiableSet(all);
    }

    // ==================== 兼容旧 API ====================

    /** @deprecated Use {@link #addAllReturn(String)} instead. */
    @Deprecated
    public static void addCustom(String prefix) {
        addAllReturn(prefix);
    }

    /** @deprecated Use {@link #removeAllReturn(String)} instead. */
    @Deprecated
    public static boolean removeCustom(String prefix) {
        return removeAllReturn(prefix);
    }

    // ==================== JSON 配置加载 ====================

    private static final String CONFIG_DIR = "config/eca";
    private static final String TYPE_ALLRETURN = "allreturn";
    private static final String TYPE_TRANSFORM = "transform";

    //从 config/eca/ 加载 JSON 白名单（必须在 ClassFileTransformer 注册前��用）
    public static void loadJsonWhitelist() {
        if (jsonLoaded) return;
        jsonLoaded = true;
        loadFromConfigDirectory();
        rebuildFirstSegments();
    }

    private static void loadFromConfigDirectory() {
        try {
            Path dir = Paths.get(CONFIG_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            List<Path> jsonFiles = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
                for (Path path : stream) {
                    jsonFiles.add(path);
                }
            }

            if (jsonFiles.isEmpty()) {
                generateExampleFiles(dir);
                return;
            }

            for (Path file : jsonFiles) {
                loadJsonFile(file);
            }
        } catch (Throwable t) {
            AgentLogWriter.error("[TransformerWhitelist] Failed to load config/eca/", t);
        }
    }

    private static void loadJsonFile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String type = extractJsonString(content, "type");
            if (type == null) return;

            List<String> packages = extractJsonArray(content, "packages");
            if (packages.isEmpty()) return;

            int count = 0;
            for (String pkg : packages) {
                if (pkg.isEmpty()) continue;
                String normalized = normalizePrefix(pkg);
                if (normalized == null) continue;

                if (TYPE_ALLRETURN.equals(type)) {
                    customAllReturn.add(normalized);
                    count++;
                } else if (TYPE_TRANSFORM.equals(type)) {
                    customTransform.add(normalized);
                    count++;
                }
            }

            if (count > 0) {
                AgentLogWriter.info("[TransformerWhitelist] Loaded " + count + " prefixes (" + type + ") from " + file.getFileName());
            }
        } catch (Throwable t) {
            AgentLogWriter.error("[TransformerWhitelist] Failed to parse: " + file.getFileName(), t);
        }
    }

    // 简易 JSON 字符串值提取（不依赖 Gson）
    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int keyIndex = json.indexOf(pattern);
        if (keyIndex < 0) return null;

        int colonIndex = json.indexOf(':', keyIndex + pattern.length());
        if (colonIndex < 0) return null;

        int quoteStart = json.indexOf('"', colonIndex + 1);
        if (quoteStart < 0) return null;

        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;

        return json.substring(quoteStart + 1, quoteEnd).trim();
    }

    // 简易 JSON 数组提取
    private static List<String> extractJsonArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String pattern = "\"" + key + "\"";
        int keyIndex = json.indexOf(pattern);
        if (keyIndex < 0) return result;

        int bracketStart = json.indexOf('[', keyIndex + pattern.length());
        if (bracketStart < 0) return result;

        int bracketEnd = json.indexOf(']', bracketStart);
        if (bracketEnd < 0) return result;

        String arrayContent = json.substring(bracketStart + 1, bracketEnd);
        int i = 0;
        while (i < arrayContent.length()) {
            int qs = arrayContent.indexOf('"', i);
            if (qs < 0) break;
            int qe = arrayContent.indexOf('"', qs + 1);
            if (qe < 0) break;
            String value = arrayContent.substring(qs + 1, qe).trim();
            if (!value.isEmpty()) result.add(value);
            i = qe + 1;
        }
        return result;
    }

    private static void generateExampleFiles(Path dir) {
        writeExample(dir.resolve("allreturn_whitelist_example.json"),
            "{\n" +
            "  \"type\": \"allreturn\",\n" +
            "  \"description\": \"Skip AllReturn transformation only. Defensive hooks still apply. " +
                "Only 'type' and 'packages' fields are required, other fields are ignored.\",\n" +
            "  \"packages\": [\n" +
            "    \"com.example.yourmod.\"\n" +
            "  ]\n" +
            "}\n"
        );
        writeExample(dir.resolve("transform_whitelist_example.json"),
            "{\n" +
            "  \"type\": \"transform\",\n" +
            "  \"description\": \"Skip ALL ECA transformations including defensive hooks. " +
                "Only 'type' and 'packages' fields are required, other fields are ignored.\",\n" +
            "  \"packages\": [\n" +
            "    \"com.example.modA.\",\n" +
            "    \"com.example.modB.\",\n" +
            "    \"net.example.modC.\"\n" +
            "  ]\n" +
            "}\n"
        );
        AgentLogWriter.info("[TransformerWhitelist] Generated example whitelist files in config/eca/");
    }

    private static void writeExample(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AgentLogWriter.error("[TransformerWhitelist] Failed to write: " + path.getFileName(), e);
        }
    }

    // ==================== 工具方法 ====================

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return null;
        String normalized = prefix.replace('/', '.');
        if (!normalized.endsWith(".")) normalized += ".";
        return normalized;
    }

    private TransformerWhitelist() {}
}
