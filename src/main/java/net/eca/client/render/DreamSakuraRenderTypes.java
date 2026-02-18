package net.eca.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.eca.client.render.shader.DreamSakuraShader;

@SuppressWarnings("removal")
@OnlyIn(Dist.CLIENT)
public class DreamSakuraRenderTypes {

    private static final ResourceLocation DREAM_SAKURA_TEXTURE = new ResourceLocation("eca", "textures/shader/dream_sakura.png");

    private static final RenderStateShard.ShaderStateShard SHADER_STATE = new RenderStateShard.ShaderStateShard(DreamSakuraShader::getShader) {
        @Override
        public void setupRenderState() {
            super.setupRenderState();
            DreamSakuraShader.applyUniforms();
        }
    };

    public static final RenderType BOSS_BAR_FRAME = RenderType.create("dream_sakura_boss_bar_frame",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(SHADER_STATE)
            .setTextureState(new RenderStateShard.TextureStateShard(DREAM_SAKURA_TEXTURE, false, false))
            .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderType.NO_DEPTH_TEST)
            .setWriteMaskState(RenderType.COLOR_WRITE)
            .createCompositeState(false)
    );

    public static final RenderType BOSS_BAR_FILL = RenderType.create("dream_sakura_boss_bar_fill",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(SHADER_STATE)
            .setTextureState(new RenderStateShard.TextureStateShard(DREAM_SAKURA_TEXTURE, false, false))
            .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderType.NO_DEPTH_TEST)
            .setWriteMaskState(RenderType.COLOR_WRITE)
            .createCompositeState(false)
    );

    public static final RenderType BOSS_LAYER = RenderType.create("dream_sakura_boss_layer",
        DefaultVertexFormat.NEW_ENTITY,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(SHADER_STATE)
            .setTextureState(new RenderStateShard.TextureStateShard(DREAM_SAKURA_TEXTURE, false, false))
            .setTransparencyState(RenderType.ADDITIVE_TRANSPARENCY)
            .setCullState(RenderType.NO_CULL)
            .setWriteMaskState(RenderType.COLOR_WRITE)
            .createCompositeState(false)
    );

    public static final RenderType SKYBOX = RenderType.create("dream_sakura_skybox",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.QUADS,
        256,
        true,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(SHADER_STATE)
            .setTextureState(new RenderStateShard.TextureStateShard(DREAM_SAKURA_TEXTURE, false, false))
            .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderType.NO_DEPTH_TEST)
            .setWriteMaskState(RenderType.COLOR_WRITE)
            .createCompositeState(false)
    );

    public static RenderType createEntityEffect(ResourceLocation texture) {
        return RenderType.create("dream_sakura_entity_effect",
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

    private DreamSakuraRenderTypes() {}
}
