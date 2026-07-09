package net.eca.client.render.preset;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/* 通用预设 RenderType 工厂：把原本每个内置预设各写一份的 5 种 RenderType 抽成"名字 + ShaderState"参数化的构造。
   各档的顶点格式与渲染状态与内置预设逐项一致，保证自定义预设在 boss 条 / 实体层 / 天空盒 / 物品 / 实体效果上的行为完全等价。 */
@OnlyIn(Dist.CLIENT)
public final class PresetRenderTypes {

    private PresetRenderTypes() {}

    public static RenderType bossBar(String name, RenderStateShard.ShaderStateShard shaderState) {
        return RenderType.create(name + "_boss_bar",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                .setShaderState(shaderState)
                .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(RenderType.NO_DEPTH_TEST)
                .setWriteMaskState(RenderType.COLOR_WRITE)
                .createCompositeState(false)
        );
    }

    public static RenderType bossLayer(String name, RenderStateShard.ShaderStateShard shaderState) {
        return RenderType.create(name + "_boss_layer",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                .setShaderState(shaderState)
                .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderType.NO_CULL)
                .setWriteMaskState(RenderType.COLOR_WRITE)
                .createCompositeState(false)
        );
    }

    public static RenderType skybox(String name, RenderStateShard.ShaderStateShard shaderState) {
        return RenderType.create(name + "_skybox",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            256,
            true,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(shaderState)
                .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(RenderType.NO_DEPTH_TEST)
                .setWriteMaskState(RenderType.COLOR_WRITE)
                .createCompositeState(false)
        );
    }

    public static RenderType item(String name, RenderStateShard.ShaderStateShard shaderState) {
        return RenderType.create(name + "_item",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            256,
            true,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(shaderState)
                .setTextureState(RenderType.BLOCK_SHEET_MIPPED)
                .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(RenderType.LEQUAL_DEPTH_TEST)
                .setCullState(RenderType.NO_CULL)
                .setOverlayState(RenderType.OVERLAY)
                .setWriteMaskState(RenderType.COLOR_WRITE)
                .createCompositeState(true)
        );
    }

    static RenderType entityEffect(String name, RenderStateShard.ShaderStateShard shaderState, ResourceLocation texture) {
        return RenderType.create(name + "_entity_effect",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            256,
            true,
            true,
            RenderType.CompositeState.builder()
                .setShaderState(shaderState)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(RenderType.LEQUAL_DEPTH_TEST)
                .setLightmapState(RenderType.LIGHTMAP)
                .setOverlayState(RenderType.OVERLAY)
                .setCullState(RenderType.NO_CULL)
                .setWriteMaskState(RenderType.COLOR_DEPTH_WRITE)
                .createCompositeState(true)
        );
    }
}
