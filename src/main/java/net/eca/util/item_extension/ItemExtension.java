package net.eca.util.item_extension;

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

    public abstract RenderType getRenderType();

    /**
     * Returns the Color-Key target color as {r, g, b} in 0.0~1.0 range.
     * Return null to disable Color-Key masking (shader covers entire item).
     */
    public float[] getColorKey() {
        return null;
    }

    public float getColorKeyTolerance() {
        return 0.1f;
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
