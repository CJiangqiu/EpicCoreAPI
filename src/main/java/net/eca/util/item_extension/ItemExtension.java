package net.eca.util.item_extension;

import net.eca.client.render.ShaderMaskPass;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Collections;
import java.util.List;

@SuppressWarnings("removal")
@OnlyIn(Dist.CLIENT)
public abstract class ItemExtension {
    private final Item item;

    protected ItemExtension(Item item) {
        if (item == null) {
            throw new IllegalArgumentException("Item cannot be null");
        }
        this.item = item;
    }

    public Item getItem() {
        return item;
    }

    public boolean enabled() {
        return false;
    }

    public boolean shouldRender(ItemStack stack) {
        return true;
    }

    /**
     * Returns the legacy single shader RenderType.
     *
     * @return shader RenderType, or {@code null}
     * @deprecated Override {@link #getShaderPasses()} to support one or more shader masks.
     */
    @Deprecated
    public RenderType getRenderType() {
        return null;
    }

    /**
     * Returns the Color-Key target color as {r, g, b} in 0.0~1.0 range.
     * Return null to disable Color-Key masking (shader covers the visible item texture).
     *
     * @return legacy RGB color key, or {@code null}
     * @deprecated Use a {@link ShaderMaskPass} returned by {@link #getShaderPasses()}.
     */
    @Deprecated
    public float[] getColorKey() {
        return null;
    }

    /**
     * Returns the legacy Color-Key tolerance.
     *
     * @return normalized RGB distance tolerance
     * @deprecated Use {@link ShaderMaskPass#baseTexture(RenderType, int, float, float)}.
     */
    @Deprecated
    public float getColorKeyTolerance() {
        return 0.1f;
    }

    /**
     * Returns the legacy single mask texture.
     *
     * @return mask texture, or {@code null}
     * @deprecated Return one or more masks from {@link #getShaderPasses()}.
     */
    @Deprecated
    public ResourceLocation getMaskTexture() {
        return null;
    }

    /**
     * Returns the legacy single mask target color.
     *
     * @return packed RGB color, black by default
     * @deprecated Return one or more masks from {@link #getShaderPasses()}.
     */
    @Deprecated
    public int getMaskColor() {
        return 0x000000;
    }

    /**
     * Returns the legacy single mask color tolerance.
     *
     * @return normalized RGB distance tolerance
     * @deprecated Return one or more masks from {@link #getShaderPasses()}.
     */
    @Deprecated
    public float getMaskTolerance() {
        return 0.05f;
    }

    /**
     * Returns the ordered shader mask passes for this item.
     * Baked quads are split by atlas sprite so external mask textures receive sprite-local UV coordinates.
     * Later passes render over earlier passes when selected regions overlap.
     *
     * @return ordered shader mask passes, or an empty list when no shader overlay is required
     */
    public List<ShaderMaskPass> getShaderPasses() {
        RenderType renderType = getRenderType();
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
        return List.of(ShaderMaskPass.baseTexture(renderType, 0x000000, 2.0f, getAlpha()));
    }

    private static int packColor(float[] color) {
        int red = Math.round(Math.max(0.0f, Math.min(1.0f, color[0])) * 255.0f);
        int green = Math.round(Math.max(0.0f, Math.min(1.0f, color[1])) * 255.0f);
        int blue = Math.round(Math.max(0.0f, Math.min(1.0f, color[2])) * 255.0f);
        return red << 16 | green << 8 | blue;
    }

    /**
     * Returns the opacity of the item extension shader layer.
     * 1.0 is fully opaque (default), 0.0 is fully transparent.
     * @return alpha value in range [0, 1]
     */
    public float getAlpha() {
        return 1.0f;
    }

    /**
     * Override the item's display name. Return null to keep the vanilla name.
     * Called client-side; player-set custom names (anvil) always take priority.
     */
    public MutableComponent getItemName(ItemStack stack) {
        return null;
    }

    /**
     * Return tooltip lines with explicit insertion positions.
     * Each line can carry its own rich Component styling and animation effects.
     */
    public List<EcaTooltipLine> getTooltipLines(ItemStack stack, TooltipFlag flag) {
        return Collections.emptyList();
    }

    /**
     * Append or modify tooltip lines in place. Called client-side.
     * Index 0 is the item name line.
     */
    public void appendTooltip(ItemStack stack, TooltipFlag flag, List<Component> lines) {
    }

    protected abstract String getModId();

    protected ResourceLocation texture(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.startsWith("textures/") ? path : "textures/" + path;
        return new ResourceLocation(getModId(), normalized);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ItemExtension other)) return false;
        return this.item.equals(other.item);
    }

    @Override
    public int hashCode() {
        return item.hashCode();
    }
}
