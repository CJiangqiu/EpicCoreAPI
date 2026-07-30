package net.eca.util.block_extension;

import net.eca.client.render.ShaderMaskPass;
import net.eca.client.render.preset.ShaderPreset;
import net.eca.client.render.preset.ShaderPresetRegistry;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public abstract class BlockExtension {

    private final Block block;

    protected BlockExtension(Block block) {
        if (block == null) {
            throw new IllegalArgumentException("Block cannot be null");
        }
        this.block = block;
    }

    public final Block getBlock() {
        return block;
    }

    public boolean enabled() {
        return false;
    }

    public boolean shouldRender(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        return true;
    }

    public ResourceLocation getShaderPresetId() {
        return null;
    }

    public RenderType getBlockRenderType() {
        ResourceLocation presetId = getShaderPresetId();
        ShaderPreset preset = presetId == null ? null : ShaderPresetRegistry.getPreset(presetId);
        return preset == null ? null : preset.block();
    }

    public RenderType getGeoRenderType(ResourceLocation texture) {
        ResourceLocation presetId = getShaderPresetId();
        ShaderPreset preset = presetId == null ? null : ShaderPresetRegistry.getPreset(presetId);
        return preset == null ? null : preset.geoBlock(texture);
    }

    /**
     * Returns the legacy Color-Key target sampled from the block atlas.
     *
     * @return legacy RGB color key, or {@code null}
     * @deprecated Use {@link #getBlockShaderPasses()} and
     * {@link ShaderMaskPass#baseTexture(RenderType, int, float, float)}.
     */
    @Deprecated
    public float[] getColorKey() {
        return null;
    }

    /**
     * Returns the legacy Color-Key tolerance.
     *
     * @return normalized RGB distance tolerance
     * @deprecated Use {@link #getBlockShaderPasses()}.
     */
    @Deprecated
    public float getColorKeyTolerance() {
        return 0.1f;
    }

    public float getAlpha() {
        return 1.0f;
    }

    public boolean isGlow() {
        return false;
    }

    /**
     * Returns the legacy single mask texture for baked and falling blocks.
     *
     * @return mask texture, or {@code null}
     * @deprecated Return one or more masks from {@link #getBlockShaderPasses()}.
     */
    @Deprecated
    public ResourceLocation getMaskTexture() {
        return null;
    }

    /**
     * Returns the legacy single block mask target color.
     *
     * @return packed RGB color, black by default
     * @deprecated Return one or more masks from {@link #getBlockShaderPasses()}.
     */
    @Deprecated
    public int getMaskColor() {
        return 0x000000;
    }

    /**
     * Returns the legacy single block mask tolerance.
     *
     * @return normalized RGB distance tolerance
     * @deprecated Return one or more masks from {@link #getBlockShaderPasses()}.
     */
    @Deprecated
    public float getMaskTolerance() {
        return 0.05f;
    }

    /**
     * Returns the legacy single mask texture for GeckoLib block entities.
     *
     * @return mask texture, or {@code null}
     * @deprecated Return one or more masks from {@link #getGeoShaderPasses(ResourceLocation)}.
     */
    @Deprecated
    public ResourceLocation getGeoMaskTexture() {
        return null;
    }

    /**
     * Returns the legacy single GeckoLib block mask target color.
     *
     * @return packed RGB color, black by default
     * @deprecated Return one or more masks from {@link #getGeoShaderPasses(ResourceLocation)}.
     */
    @Deprecated
    public int getGeoMaskColor() {
        return 0x000000;
    }

    /**
     * Returns the legacy single GeckoLib block mask tolerance.
     *
     * @return normalized RGB distance tolerance
     * @deprecated Return one or more masks from {@link #getGeoShaderPasses(ResourceLocation)}.
     */
    @Deprecated
    public float getGeoMaskTolerance() {
        return 0.05f;
    }

    /**
     * Returns ordered BLOCK-profile shader mask passes for baked and falling blocks.
     * Baked quads are split by atlas sprite before external masks receive local UV coordinates.
     *
     * @return ordered block shader mask passes
     */
    public List<ShaderMaskPass> getBlockShaderPasses() {
        RenderType renderType = getBlockRenderType();
        if (renderType == null) {
            return List.of();
        }
        ResourceLocation maskTexture = getMaskTexture();
        if (maskTexture != null) {
            return List.of(ShaderMaskPass.masked(renderType, maskTexture,
                getMaskColor(), getMaskTolerance(), getAlpha()));
        }
        float[] colorKey = getColorKey();
        if (colorKey != null && colorKey.length >= 3) {
            return List.of(ShaderMaskPass.baseTexture(renderType, packColor(colorKey),
                getColorKeyTolerance(), getAlpha()));
        }
        return List.of(ShaderMaskPass.unmasked(renderType, getAlpha()));
    }

    /**
     * Returns ordered NEW_ENTITY-profile shader mask passes for a GeckoLib block entity.
     *
     * @param texture current GeckoLib model texture
     * @return ordered GeckoLib block shader mask passes
     */
    public List<ShaderMaskPass> getGeoShaderPasses(ResourceLocation texture) {
        RenderType renderType = getGeoRenderType(texture);
        if (renderType == null) {
            return List.of();
        }
        return List.of(ShaderMaskPass.masked(renderType, getGeoMaskTexture(),
            getGeoMaskColor(), getGeoMaskTolerance(), getAlpha()));
    }

    private static int packColor(float[] color) {
        int red = Math.round(Math.max(0.0f, Math.min(1.0f, color[0])) * 255.0f);
        int green = Math.round(Math.max(0.0f, Math.min(1.0f, color[1])) * 255.0f);
        int blue = Math.round(Math.max(0.0f, Math.min(1.0f, color[2])) * 255.0f);
        return red << 16 | green << 8 | blue;
    }

    public Set<String> hiddenGeoBones() {
        return Collections.emptySet();
    }

    public Set<String> overlayGeoBones() {
        return Collections.emptySet();
    }
}
