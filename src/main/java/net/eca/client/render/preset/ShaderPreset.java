package net.eca.client.render.preset;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/* 一个自定义预设对外暴露的 5 种 RenderType 集合，按顶点格式分两组：BLOCK profile（天空盒 / Boss 血条）共享
   blockState，NEW_ENTITY profile（Boss 实体层 / 物品层 / 实体效果层）共享 entityState。每个 ShaderState 读对应
   profile 的当前 ShaderInstance（资源重载换实例后自动跟随），并在 setupRenderState 时喂入该 profile 的标准 uniform。
   组织方式与内置 XxxRenderTypes 一致。entityEffect 依赖纹理，按纹理缓存，避免每帧重复构造 RenderType。 */
@OnlyIn(Dist.CLIENT)
public final class ShaderPreset {

    private final ResourceLocation id;
    private final String name;
    private final RenderStateShard.ShaderStateShard entityShaderState;
    private final RenderType bossBar;
    private final RenderType bossLayer;
    private final RenderType skybox;
    private final RenderType item;
    private final Map<ResourceLocation, RenderType> entityEffectByTexture = new ConcurrentHashMap<>();

    ShaderPreset(ResourceLocation id, GenericPresetShader shader) {
        this.id = id;
        this.name = "eca_preset_" + id.getNamespace() + "_" + id.getPath().replace('/', '_');
        RenderStateShard.ShaderStateShard blockShaderState = profileState(shader.block());
        this.entityShaderState = profileState(shader.entity());
        this.bossBar = PresetRenderTypes.bossBar(name, blockShaderState);
        this.skybox = PresetRenderTypes.skybox(name, blockShaderState);
        this.bossLayer = PresetRenderTypes.bossLayer(name, entityShaderState);
        this.item = PresetRenderTypes.item(name, entityShaderState);
    }

    //用一个 profile 的当前 ShaderInstance 构造 ShaderState，并在渲染前喂入该 profile 的标准 uniform
    private static RenderStateShard.ShaderStateShard profileState(GenericPresetShader.Profile profile) {
        return new RenderStateShard.ShaderStateShard(profile::getShader) {
            @Override
            public void setupRenderState() {
                super.setupRenderState();
                profile.applyUniforms();
            }
        };
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

    //实体效果叠加层：纹理相关，按纹理缓存对应的 RenderType，统一走 NEW_ENTITY profile 的 entityState
    public RenderType entityEffect(ResourceLocation texture) {
        return entityEffectByTexture.computeIfAbsent(texture, t -> PresetRenderTypes.entityEffect(name, entityShaderState, t));
    }
}
