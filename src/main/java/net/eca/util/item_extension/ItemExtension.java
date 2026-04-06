package net.eca.util.item_extension;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

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
