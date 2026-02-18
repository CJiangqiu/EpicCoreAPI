package net.eca.util.entity_extension;

import com.mojang.blaze3d.shaders.FogShape;
import net.eca.api.RegisterEntityExtension;
import net.eca.client.render.BlackHoleRenderTypes;
import net.eca.client.render.CosmosRenderTypes;
import net.eca.client.render.TheLastEndRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@RegisterEntityExtension
public class WitherExtension extends EntityExtension {

    static {
        EntityExtensionManager.register(new WitherExtension());
    }

    public WitherExtension() {
        super(EntityType.WITHER, 8);
    }

    @Override
    public boolean enableForceLoading() {
        return true;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    protected String getModId() {
        return "eca";
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public BossBarExtension bossBarExtension() {
        return new BossBarExtension() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public ResourceLocation getFrameTexture() {
                return texture("screen/test_bar_frame.png");
            }

            @Override
            public RenderType getFrameRenderType() {
                return TheLastEndRenderTypes.BOSS_BAR_FRAME;
            }

            @Override
            public RenderType getFillRenderType() {
                return CosmosRenderTypes.BOSS_BAR_FILL;
            }

            @Override
            public int getFillWidth() {
                return 420;
            }

            @Override
            public int getFillHeight() {
                return 40;
            }

            @Override
            public int getFillOffsetY() {
                return -10;
            }
        };
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public EntityLayerExtension entityLayerExtension() {
        return new EntityLayerExtension() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public RenderType getRenderType() {
                return BlackHoleRenderTypes.BOSS_LAYER;
            }

            @Override
            public boolean isGlow() {
                return true;
            }

            @Override
            public float getAlpha() {
                return 0.8f;
            }
        };
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public GlobalFogExtension globalFogExtension() {
        return new GlobalFogExtension() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public float radius() {
                return 8.0f;
            }

            @Override
            public float fogRed() {
                return 0.0f;
            }

            @Override
            public float fogGreen() {
                return 0.0f;
            }

            @Override
            public float fogBlue() {
                return 0.0f;
            }

            @Override
            public float terrainFogStart(float renderDistance) {
                return renderDistance * 0.02f;
            }

            @Override
            public float terrainFogEnd(float renderDistance) {
                return renderDistance * 0.25f;
            }

            @Override
            public FogShape fogShape() {
                return FogShape.SPHERE;
            }
        };
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public GlobalSkyboxExtension globalSkyboxExtension() {
        return new GlobalSkyboxExtension() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public boolean enableShader() {
                return true;
            }

            @Override
            public RenderType shaderRenderType() {
                return BlackHoleRenderTypes.SKYBOX;
            }

            @Override
            public float alpha() {
                return 0.9f;
            }

            @Override
            public float size() {
                return 100.0f;
            }
        };
    }
}
