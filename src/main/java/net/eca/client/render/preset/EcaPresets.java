package net.eca.client.render.preset;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Set;

/* 着色器预设 RenderType 运行时查表。用预设 id ("modid:name") 获取对应的 RenderType，
   供 Boss 扩展、实体扩展、物品扩展等直接调用。
   ECA 启动时自动发现所有可用预设，可用 getPresetIds() 枚举。 */
@OnlyIn(Dist.CLIENT)
public final class EcaPresets {

    private EcaPresets() {}

    private static ShaderPreset resolve(String id) {
        ResourceLocation resourceId = ResourceLocation.tryParse(id);
        if (resourceId == null) return null;
        return ShaderPresetRegistry.getPreset(resourceId);
    }

    /** Boss 血条边框 */
    public static RenderType bossBar(String id) {
        ShaderPreset preset = resolve(id);
        return preset != null ? preset.bossBar() : null;
    }

    /** Boss 实体额外渲染层 */
    public static RenderType bossLayer(String id) {
        ShaderPreset preset = resolve(id);
        return preset != null ? preset.bossLayer() : null;
    }

    /** 全局天空盒 */
    public static RenderType skybox(String id) {
        ShaderPreset preset = resolve(id);
        return preset != null ? preset.skybox() : null;
    }

    /** 物品额外渲染层 */
    public static RenderType item(String id) {
        ShaderPreset preset = resolve(id);
        return preset != null ? preset.item() : null;
    }

    /** 枚举所有已注册的预设 id */
    public static Set<ResourceLocation> getPresetIds() {
        return ShaderPresetRegistry.getPresetIds();
    }
}
