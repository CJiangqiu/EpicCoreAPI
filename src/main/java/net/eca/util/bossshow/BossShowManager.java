package net.eca.util.bossshow;

import net.eca.api.RegisterBossShow;
import net.eca.util.EcaLogger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry + loader for BossShow cutscenes.
 *
 * <p>Two kinds of registration:</p>
 * <ul>
 *   <li><b>Code</b> — Classes annotated {@code @RegisterBossShow} register a {@link BossShow}
 *       instance in a static block. This provides server-side event hooks (onMarkerEvent etc).</li>
 *   <li><b>JSON</b> — Definitions loaded from {@code config/eca/bossshow/<namespace>/<name>.json}.
 *       These provide the keyframes, trigger config, and target entity type.</li>
 * </ul>
 *
 * <p>A cutscene is playable if at least a JSON definition exists. If a code hook also exists
 * for the same id, it's invoked for keyframe events. Code-registered cutscenes without a matching
 * JSON file will auto-generate a minimal template file on first scan.</p>
 */
public final class BossShowManager {

    private static final Map<ResourceLocation, BossShow> CODE_REGISTRY = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, BossShowDefinition> DEFINITIONS = new ConcurrentHashMap<>();

    private static final Path CONFIG_DIR = Paths.get("config", "eca", "bossshow");

    private BossShowManager() {}

    //注册一个 Java 侧 BossShow 实例（通常在 static {} 块中调用）
    public static boolean register(BossShow bossShow) {
        if (bossShow == null) {
            EcaLogger.error("Cannot register null BossShow");
            return false;
        }
        ResourceLocation id = bossShow.id();
        if (CODE_REGISTRY.containsKey(id)) {
            EcaLogger.error("BossShow id {} already registered as {}, skipping {}",
                id, CODE_REGISTRY.get(id).getClass().getName(), bossShow.getClass().getName());
            return false;
        }
        CODE_REGISTRY.put(id, bossShow);
        return true;
    }

    //扫描所有 mod 的 @RegisterBossShow 注解并触发静态初始化，然后加载 JSON 定义
    public static void scanAndRegisterAll() {
        ModList.get().forEachModFile(modFile -> {
            for (IModInfo modInfo : modFile.getModInfos()) {
                modFile.getScanResult().getAnnotations().forEach(annotationData -> {
                    if (RegisterBossShow.class.getName().equals(annotationData.annotationType().getClassName())) {
                        String className = annotationData.clazz().getClassName();
                        try {
                            Class.forName(className, true, Thread.currentThread().getContextClassLoader());
                        } catch (ClassNotFoundException e) {
                            EcaLogger.error("Failed to load BossShow class {}: {}", className, e.getMessage());
                        }
                    }
                });
            }
        });

        loadModDataDefinitions();
        loadAllJsonDefinitions();
        autoGenerateMissingTemplates();
    }

    //从所有 mod jar 的 data/<modid>/bossshow/**/*.json 加载定义（Source.MOD）
    private static void loadModDataDefinitions() {
        int[] count = {0};
        ModList.get().forEachModFile(modFile -> {
            for (IModInfo modInfo : modFile.getModInfos()) {
                String modid = modInfo.getModId();
                Path bossshowDir = modFile.findResource("data", modid, "bossshow");
                if (!Files.isDirectory(bossshowDir)) continue;
                count[0] += scanModDataDirectory(bossshowDir, bossshowDir, modid);
            }
        });
        if (count[0] > 0) {
            EcaLogger.info("Loaded {} BossShow definition(s) from mod data", count[0]);
        }
    }

    private static int scanModDataDirectory(Path root, Path current, String namespace) {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    count += scanModDataDirectory(root, path, namespace);
                } else if (path.getFileName().toString().toLowerCase().endsWith(".json")) {
                    if (loadModDataFile(root, path, namespace)) count++;
                }
            }
        } catch (IOException e) {
            EcaLogger.error("Failed to list mod BossShow directory {}: {}", current, e.getMessage());
        }
        return count;
    }

    private static boolean loadModDataFile(Path root, Path file, String namespace) {
        try {
            Path rel = root.relativize(file);
            StringBuilder pathBuilder = new StringBuilder();
            for (int i = 0; i < rel.getNameCount(); i++) {
                if (i > 0) pathBuilder.append('/');
                String seg = rel.getName(i).toString();
                if (i == rel.getNameCount() - 1 && seg.toLowerCase().endsWith(".json")) {
                    seg = seg.substring(0, seg.length() - 5);
                }
                pathBuilder.append(seg);
            }
            ResourceLocation id = ResourceLocation.tryParse(namespace + ":" + pathBuilder);
            if (id == null) {
                EcaLogger.error("Mod BossShow file {} produced invalid ResourceLocation", file);
                return false;
            }

            String content = Files.readString(file, StandardCharsets.UTF_8);
            BossShowDefinition def = BossShowJsonCodec.parse(content, id, BossShowDefinition.Source.MOD);
            if (def != null) {
                DEFINITIONS.put(id, def);
                return true;
            }
        } catch (Throwable t) {
            EcaLogger.error("Failed to load mod BossShow file {}: {}", file, t.getMessage());
        }
        return false;
    }

    //从 config/eca/bossshow/**/*.json 加载所有定义（Source.CONFIG，覆盖同 id 的 MOD 定义）
    public static void loadAllJsonDefinitions() {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
                return;
            }
            scanDirectory(CONFIG_DIR, CONFIG_DIR);
        } catch (Throwable t) {
            EcaLogger.error("Failed to scan BossShow config directory: {}", t.getMessage());
        }
        EcaLogger.info("Loaded {} BossShow definition(s) from JSON", DEFINITIONS.size());
    }

    private static void scanDirectory(Path root, Path current) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    //跳过顶层 lang/ 目录，由 BossShowLangOverride 专门处理字幕翻译
                    if (current.equals(root) && "lang".equals(path.getFileName().toString())) {
                        continue;
                    }
                    scanDirectory(root, path);
                } else if (path.getFileName().toString().toLowerCase().endsWith(".json")) {
                    loadJsonFile(root, path);
                }
            }
        } catch (IOException e) {
            EcaLogger.error("Failed to list BossShow directory {}: {}", current, e.getMessage());
        }
    }

    private static void loadJsonFile(Path root, Path file) {
        try {
            //路径转 ResourceLocation：<ns>/<path>.json → ns:path
            Path rel = root.relativize(file);
            int nameCount = rel.getNameCount();
            if (nameCount < 2) {
                EcaLogger.warn("BossShow file {} must be under a namespace subdirectory; skipping", file);
                return;
            }
            String namespace = rel.getName(0).toString();
            StringBuilder pathBuilder = new StringBuilder();
            for (int i = 1; i < nameCount; i++) {
                if (i > 1) pathBuilder.append('/');
                String seg = rel.getName(i).toString();
                if (i == nameCount - 1 && seg.toLowerCase().endsWith(".json")) {
                    seg = seg.substring(0, seg.length() - 5);
                }
                pathBuilder.append(seg);
            }
            ResourceLocation id = ResourceLocation.tryParse(namespace + ":" + pathBuilder);
            if (id == null) {
                EcaLogger.error("BossShow file {} produced invalid ResourceLocation", file);
                return;
            }

            String content = Files.readString(file, StandardCharsets.UTF_8);
            BossShowDefinition def = BossShowJsonCodec.parse(content, id, BossShowDefinition.Source.CONFIG);
            if (def != null) {
                DEFINITIONS.put(id, def);
            }
        } catch (Throwable t) {
            EcaLogger.error("Failed to load BossShow file {}: {}", file, t.getMessage());
        }
    }

    //为 code-registered 但没有 json 的 BossShow 自动生成模板
    private static void autoGenerateMissingTemplates() {
        for (Map.Entry<ResourceLocation, BossShow> entry : CODE_REGISTRY.entrySet()) {
            ResourceLocation id = entry.getKey();
            if (DEFINITIONS.containsKey(id)) continue;

            BossShow bossShow = entry.getValue();
            BossShowDefinition template = buildTemplate(id, bossShow.targetType());
            try {
                Path target = CONFIG_DIR.resolve(id.getNamespace()).resolve(id.getPath() + ".json");
                Files.createDirectories(target.getParent());
                Files.writeString(target, BossShowJsonCodec.serialize(template), StandardCharsets.UTF_8);
                DEFINITIONS.put(id, template);
                EcaLogger.info("Generated BossShow template: {}", target);
            } catch (Throwable t) {
                EcaLogger.error("Failed to generate BossShow template for {}: {}", id, t.getMessage());
            }
        }
    }

    private static BossShowDefinition buildTemplate(ResourceLocation id, EntityType<?> targetType) {
        //空白模板：无 sample，无 marker。code-registered cutscene 由作者后续录制填充
        return new BossShowDefinition(id, targetType, new Trigger.Custom(""), true, false,
            new ArrayList<>(), new ArrayList<>(), BossShowDefinition.Source.CODE, 0f);
    }

    //==================== 查询 API ====================

    public static BossShowDefinition get(ResourceLocation id) {
        return DEFINITIONS.get(id);
    }

    public static BossShow getCodeHook(ResourceLocation id) {
        return CODE_REGISTRY.get(id);
    }

    public static Map<ResourceLocation, BossShowDefinition> getAllDefinitions() {
        return Collections.unmodifiableMap(DEFINITIONS);
    }

    //按目标实体类型枚举所有 range 触发的定义
    public static List<BossShowDefinition> getRangeDefinitionsForType(EntityType<?> type) {
        List<BossShowDefinition> out = new java.util.ArrayList<>();
        for (BossShowDefinition def : DEFINITIONS.values()) {
            if (def.targetType() == type && def.trigger() instanceof Trigger.Range) {
                out.add(def);
            }
        }
        return out;
    }

    //保存定义到 JSON（编辑器使用）
    public static boolean save(BossShowDefinition def) {
        if (def == null) return false;
        try {
            Path target = CONFIG_DIR.resolve(def.id().getNamespace()).resolve(def.id().getPath() + ".json");
            Files.createDirectories(target.getParent());
            Files.writeString(target, BossShowJsonCodec.serialize(def), StandardCharsets.UTF_8);
            DEFINITIONS.put(def.id(), def);
            return true;
        } catch (Throwable t) {
            EcaLogger.error("Failed to save BossShow {}: {}", def.id(), t.getMessage());
            return false;
        }
    }

    //从内存和磁盘删除一个定义（编辑器使用）
    //返回 true 表示已确认从 DEFINITIONS 移除（无论文件是否存在）
    public static boolean delete(ResourceLocation id) {
        if (id == null) return false;
        try {
            Path target = CONFIG_DIR.resolve(id.getNamespace()).resolve(id.getPath() + ".json");
            Files.deleteIfExists(target);
            DEFINITIONS.remove(id);
            return true;
        } catch (Throwable t) {
            EcaLogger.error("Failed to delete BossShow {}: {}", id, t.getMessage());
            return false;
        }
    }

    //手动触发重载（命令使用）
    public static void reload() {
        DEFINITIONS.clear();
        loadModDataDefinitions();
        loadAllJsonDefinitions();
        autoGenerateMissingTemplates();
    }

    //工具：从字符串解析 ResourceLocation
    public static ResourceLocation parseId(String s) {
        return ResourceLocation.tryParse(s);
    }

    //按 EntityType 反查 ResourceLocation，供序列化使用
    public static ResourceLocation idOfType(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type);
    }

    //统计：防止未使用警告
    public static int definitionCount() {
        return DEFINITIONS.size();
    }

    public static int codeRegistryCount() {
        return CODE_REGISTRY.size();
    }

    //清空所有（测试/卸载使用）
    public static void clear() {
        DEFINITIONS.clear();
        //注意：CODE_REGISTRY 不清，因为静态块只执行一次
    }

    //通过 entity-type ResourceLocation 查类型
    public static EntityType<?> entityTypeFromId(ResourceLocation id) {
        return id != null ? BuiltInRegistries.ENTITY_TYPE.get(id) : null;
    }

    //HashMap 副本供迭代安全
    public static Map<ResourceLocation, BossShowDefinition> snapshot() {
        return new HashMap<>(DEFINITIONS);
    }
}
