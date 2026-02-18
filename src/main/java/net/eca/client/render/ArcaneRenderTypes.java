package net.eca.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.eca.client.render.shader.ArcaneShader;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ArcaneRenderTypes {

    private static final RenderStateShard.ShaderStateShard SHADER_STATE = new RenderStateShard.ShaderStateShard(ArcaneShader::getShader) {
        @Override
        public void setupRenderState() {
            super.setupRenderState();
            ArcaneShader.applyUniforms();
        }
    };

    public static final RenderType BOSS_BAR_FRAME = RenderType.create("arcane_boss_bar_frame",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(SHADER_STATE)
            .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderType.NO_DEPTH_TEST)
            .setWriteMaskState(RenderType.COLOR_WRITE)
            .createCompositeState(false)
    );

    public static final RenderType BOSS_BAR_FILL = RenderType.create("arcane_boss_bar_fill",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(SHADER_STATE)
            .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderType.NO_DEPTH_TEST)
            .setWriteMaskState(RenderType.COLOR_WRITE)
            .createCompositeState(false)
    );

    public static final RenderType BOSS_LAYER = RenderType.create("arcane_boss_layer",
        DefaultVertexFormat.NEW_ENTITY,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(SHADER_STATE)
            .setTransparencyState(RenderType.ADDITIVE_TRANSPARENCY)
            .setCullState(RenderType.NO_CULL)
            .setWriteMaskState(RenderType.COLOR_WRITE)
            .createCompositeState(false)
    );

    public static final RenderType SKYBOX = RenderType.create("arcane_skybox",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.QUADS,
        256,
        true,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(SHADER_STATE)
            .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderType.NO_DEPTH_TEST)
            .setWriteMaskState(RenderType.COLOR_WRITE)
            .createCompositeState(false)
    );

    public static RenderType createEntityEffect(ResourceLocation texture) {
        return RenderType.create("arcane_entity_effect",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            256,
            true,
            true,
            RenderType.CompositeState.builder()
                .setShaderState(SHADER_STATE)
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

    private ArcaneRenderTypes() {}
}
