package net.eca.client.render.preset;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/* 一个自定义预设对外暴露的 5 种 RenderType 集合。五者共享同一个 ShaderState：它读 GenericPresetShader 的当前
   ShaderInstance（资源重载换实例后自动跟随），并在 setupRenderState 时喂入标准 uniform。组织方式与内置 XxxRenderTypes 一致。
   entityEffect 依赖纹理，按纹理缓存，避免每帧重复构造 RenderType。 */
@OnlyIn(Dist.CLIENT)
public final class ShaderPreset {

    private final ResourceLocation id;
    private final String name;
    private final RenderStateShard.ShaderStateShard shaderState;
    private final RenderType bossBar;
    private final RenderType bossLayer;
    private final RenderType skybox;
    private final RenderType item;
    private final Map<ResourceLocation, RenderType> entityEffectByTexture = new ConcurrentHashMap<>();

    ShaderPreset(ResourceLocation id, GenericPresetShader shader) {
        this.id = id;
        this.name = "eca_preset_" + id.getNamespace() + "_" + id.getPath().replace('/', '_');
        this.shaderState = new RenderStateShard.ShaderStateShard(shader::getShader) {
            @Override
            public void setupRenderState() {
                super.setupRenderState();
                shader.applyUniforms();
            }
        };
        this.bossBar = PresetRenderTypes.bossBar(name, shaderState);
        this.bossLayer = PresetRenderTypes.bossLayer(name, shaderState);
        this.skybox = PresetRenderTypes.skybox(name, shaderState);
        this.item = PresetRenderTypes.item(name, shaderState);
    }

    public ResourceLocation id() {
        return id;
    }

    public RenderType bossBar() {
        return bossBar;
    }

    public RenderType bossLayer() {
        return bossLayer;
    }

    public RenderType skybox() {
        return skybox;
    }

    public RenderType item() {
        return item;
    }

    //实体效果叠加层：纹理相关，按纹理缓存对应的 RenderType
    public RenderType entityEffect(ResourceLocation texture) {
        return entityEffectByTexture.computeIfAbsent(texture, t -> PresetRenderTypes.entityEffect(name, shaderState, t));
    }
}
