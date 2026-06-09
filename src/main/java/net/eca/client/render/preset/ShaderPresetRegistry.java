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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/* 自定义着色器预设注册表（消费端"开箱即用加载器"）。第三方 mod 把标准三件套（vsh/fsh/json）放进
   assets/<ns>/shaders/core/，用 @RegisterShaderPreset + 静态块调用 register(id) 登记即可，无需任何渲染样板。
   纯客户端：着色器子系统不存在于专用服务端，扫描入口在 LoadCompleteHandler 中受 dist.isClient() 门控。 */
@OnlyIn(Dist.CLIENT)
public final class ShaderPresetRegistry {

    private ShaderPresetRegistry() {}

    private static final Map<ResourceLocation, GenericPresetShader> SHADERS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ShaderPreset> PRESETS = new ConcurrentHashMap<>();

    //是否已至少经历过一次 RegisterShadersEvent；用于处理"注册晚于着色器加载"时的即时编译，避免要等下次资源重载才生效
    private static volatile boolean shadersLoaded = false;

    //扫描所有 mod 文件里带 @RegisterShaderPreset 的类并触发其静态初始化（静态块内调用 register）
    public static void scanAndRegisterAll() {
        ModList.get().forEachModFile(modFile -> {
            for (IModInfo modInfo : modFile.getModInfos()) {
                modFile.getScanResult().getAnnotations().forEach(annotationData -> {
                    if (RegisterShaderPreset.class.getName().equals(annotationData.annotationType().getClassName())) {
                        String className = annotationData.clazz().getClassName();
                        try {
                            Class.forName(className, true, Thread.currentThread().getContextClassLoader());
                        } catch (ClassNotFoundException e) {
                            EcaLogger.error("Failed to load shader preset class {}: {}", className, e.getMessage());
                        }
                    }
                });
            }
        });
    }

    // 注册一个自定义着色器预设
    /**
     * Register a custom shader preset by its resource id.
     * The id resolves to a standard core shader three-file set at {@code assets/<namespace>/shaders/core/<path>.vsh/.fsh/.json}.
     * Registration builds the five RenderTypes immediately and registers the skybox variant into the global effect registry;
     * the underlying shader is compiled on the next {@code RegisterShadersEvent}, or right away if shaders are already loaded.
     * Calling twice with the same id is a no-op.
     * @param id the preset resource id
     */
    public static void register(ResourceLocation id) {
        if (id == null || PRESETS.containsKey(id)) {
            return;
        }
        GenericPresetShader shader = new GenericPresetShader(id);
        ShaderPreset preset = new ShaderPreset(id, shader);
        SHADERS.put(id, shader);
        PRESETS.put(id, preset);
        GlobalEffectRegistry.registerSkyboxPreset(id, preset.skybox());

        //注册晚于 RegisterShadersEvent 时（首帧着色器已加载完毕），用当前资源管理器立即编译，免得等到下次资源重载
        if (shadersLoaded) {
            shader.reload(Minecraft.getInstance().getResourceManager());
        }
    }

    // 获取已注册的自定义着色器预设
    /**
     * Get a registered shader preset by its id.
     * @param id the preset resource id
     * @return the preset exposing the five RenderTypes, or null if not registered
     */
    public static ShaderPreset getPreset(ResourceLocation id) {
        return id == null ? null : PRESETS.get(id);
    }

    //在 RegisterShadersEvent 中调用（首帧加载与每次资源重载）：重建所有已登记预设的 ShaderInstance
    public static void onRegisterShaders(RegisterShadersEvent event) {
        shadersLoaded = true;
        ResourceProvider provider = event.getResourceProvider();
        for (GenericPresetShader shader : SHADERS.values()) {
            shader.reload(provider);
        }
    }
}
