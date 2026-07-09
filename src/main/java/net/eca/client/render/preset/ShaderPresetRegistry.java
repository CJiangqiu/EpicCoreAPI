package net.eca.client.render.preset;

import net.eca.api.RegisterShaderPreset;
import net.eca.util.EcaLogger;
import net.eca.util.entity_extension.GlobalEffectRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/* 着色器预设自动发现注册表（消费端零代码加载器）。
   第三方 mod 只需把标准五文件（<name>.fsh + <name>_block.vsh/.json + <name>_entity.vsh/.json）
   放进 assets/<ns>/shaders/core/，或把 ECA 导出的五文件放入 config/eca/shadergenerator/<ns>/<name>/，
   ECA 启动时自动扫描注册，无需任何 Java 代码或注解。

   预设 id = namespace:name。
   BLOCK profile → skybox / boss bar 的 RenderType。
   NEW_ENTITY profile → boss layer / item / entity effect 的 RenderType。
   纯客户端：服务端无着色器子系统。 */
@OnlyIn(Dist.CLIENT)
public final class ShaderPresetRegistry {

    private ShaderPresetRegistry() {}

    private static final Map<ResourceLocation, GenericPresetShader> SHADERS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ShaderPreset> PRESETS = new ConcurrentHashMap<>();

    private static volatile boolean shadersLoaded = false;

    /* 启动时三路扫描：@RegisterShaderPreset 注解 → mod assets 文件 → config 导出目录 */
    public static void scanAndRegisterAll() {
        scanAnnotations();
        scanModPresets();
        scanConfigPresets();
        if (!PRESETS.isEmpty()) {
            EcaLogger.info("Shader preset auto-discovery: {} preset(s) registered from {}",
                PRESETS.size(), PRESETS.keySet());
        }
    }

    /* 扫描 @RegisterShaderPreset 注解：读 value() 得到预设 id，作为 MCR 可发现的清单条目 */
    private static void scanAnnotations() {
        ModList.get().forEachModFile(modFile -> {
            for (IModInfo modInfo : modFile.getModInfos()) {
                modFile.getScanResult().getAnnotations().forEach(annotationData -> {
                    if (RegisterShaderPreset.class.getName().equals(annotationData.annotationType().getClassName())) {
                        String idStr = (String) annotationData.annotationData().get("value");
                        if (idStr != null && !idStr.isBlank()) {
                            ResourceLocation id = ResourceLocation.tryParse(idStr);
                            if (id != null) {
                                register(id);
                            }
                        }
                    }
                });
            }
        });
    }

    /* config/eca/shadergenerator/<namespace>/<shaderName>/ 下每个有效五文件目录为一个预设 */
    private static void scanConfigPresets() {
        Path configDir = Path.of("config", "eca", "shadergenerator");
        if (!Files.isDirectory(configDir)) return;
        try (DirectoryStream<Path> namespaces = Files.newDirectoryStream(configDir)) {
            for (Path nsDir : namespaces) {
                if (!Files.isDirectory(nsDir)) continue;
                String namespace = nsDir.getFileName().toString();
                if (!isValidNamespace(namespace)) continue;
                try (DirectoryStream<Path> presets = Files.newDirectoryStream(nsDir)) {
                    for (Path presetDir : presets) {
                        if (!Files.isDirectory(presetDir)) continue;
                        String name = presetDir.getFileName().toString();
                        if (hasPresetFiles(presetDir, name)) {
                            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, name);
                            register(id);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            EcaLogger.info("Shader preset config scan skipped: {}", e.toString());
        }
    }

    /* assets/<ns>/shaders/core/<name>.fsh 匹配到 <name>_block.* + <name>_entity.* 即为预设。
       UnionFileSystem 不支持 getPathMatcher → 不用 glob 过滤，手动 endsWith(".fsh")。 */
    private static void scanModPresets() {
        ModList.get().forEachModFile(modFile -> {
            for (IModInfo modInfo : modFile.getModInfos()) {
                String modid = modInfo.getModId();
                Path shaderDir = modFile.findResource("assets", modid, "shaders", "core");
                if (shaderDir == null || !Files.isDirectory(shaderDir)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(shaderDir)) {
                    for (Path fsh : files) {
                        String fileName = fsh.getFileName().toString();
                        if (!fileName.endsWith(".fsh")) continue;
                        String name = fileName.substring(0, fileName.length() - 4);
                        if (hasPresetFiles(shaderDir, name)) {
                            register(ResourceLocation.fromNamespaceAndPath(modid, name));
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    EcaLogger.info("Shader preset scan skipped mod {}: {}", modid, e.toString());
                }
            }
        });
    }

    private static boolean hasPresetFiles(Path dir, String name) {
        return Files.exists(dir.resolve(name + ".fsh"))
            && Files.exists(dir.resolve(name + "_block.vsh"))
            && Files.exists(dir.resolve(name + "_block.json"))
            && Files.exists(dir.resolve(name + "_entity.vsh"))
            && Files.exists(dir.resolve(name + "_entity.json"));
    }

    private static boolean isValidNamespace(String s) {
        return s != null && s.matches("[a-z0-9_.-]+");
    }

    /* 程序化注册入口（供极少数需要手动注册的场景使用）。id 需对应已有的五文件资源。 */
    public static void register(ResourceLocation id) {
        if (id == null || PRESETS.containsKey(id)) {
            return;
        }
        GenericPresetShader shader = new GenericPresetShader(id);
        ShaderPreset preset = new ShaderPreset(id, shader);
        SHADERS.put(id, shader);
        PRESETS.put(id, preset);
        GlobalEffectRegistry.registerSkyboxPreset(id, preset.skybox());

        if (shadersLoaded) {
            shader.reload(Minecraft.getInstance().getResourceManager());
        }
    }

    public static ShaderPreset getPreset(ResourceLocation id) {
        return id == null ? null : PRESETS.get(id);
    }

    public static Set<ResourceLocation> getPresetIds() {
        return Set.copyOf(PRESETS.keySet());
    }

    /* RegisterShadersEvent 中调用：重建所有已登记预设的 ShaderInstance */
    public static void onRegisterShaders(RegisterShadersEvent event) {
        shadersLoaded = true;
        ResourceProvider provider = event.getResourceProvider();
        for (GenericPresetShader shader : SHADERS.values()) {
            shader.reload(provider);
        }
    }
}
