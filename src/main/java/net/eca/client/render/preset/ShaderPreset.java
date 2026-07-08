package net.eca.client.render.preset;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/* 一个自定义预设的 5 种 RenderType 集合，由双 profile 五文件组成。
   BLOCK profile（<name>_block.vsh/.json）→ skybox / boss bar。
   NEW_ENTITY profile（<name>_entity.vsh/.json）→ boss layer / item / entity effect。
   共享 <name>.fsh。entityEffect 按纹理缓存。 */
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
