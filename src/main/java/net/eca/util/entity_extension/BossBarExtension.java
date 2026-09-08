package net.eca.util.entity_extension;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class BossBarExtension {

    public boolean enabled() {
        return false;
    }

    public boolean shouldRender(LivingEntity entity) {
        return true;
    }

    /**
     * Returns whether the current and maximum values should be drawn over the custom bar.
     *
     * @return true when the value text should be rendered
     */
    public boolean showValueText() {
        return false;
    }

    /**
     * Returns the current value used by the optional value text.
     *
     * @param entity the entity represented by this bar
     * @return the display current value, or the entity health when not overridden
     */
    public Number getDisplayCurrentValue(LivingEntity entity) {
        return entity == null ? null : entity.getHealth();
    }

    /**
     * Returns the maximum value used by the optional value text.
     *
     * @param entity the entity represented by this bar
     * @return the display maximum value, or the entity maximum health when not overridden
     */
    public Number getDisplayMaxValue(LivingEntity entity) {
        return entity == null ? null : entity.getMaxHealth();
    }

    public ResourceLocation getFrameTexture() {
        return null;
    }

    public ResourceLocation getFillTexture() {
        return null;
    }

    public RenderType getFrameRenderType() {
        return null;
    }

    public RenderType getFillRenderType() {
        return null;
    }

    public int getFrameWidth() {
        return 0;
    }

    public int getFrameHeight() {
        return 0;
    }

    public int getFillWidth() {
        return 0;
    }

    public int getFillHeight() {
        return 0;
    }

    public int getFrameOffsetX() {
        return 0;
    }

    public int getFrameOffsetY() {
        return 0;
    }

    public int getFillOffsetX() {
        return 0;
    }

    public int getFillOffsetY() {
        return 0;
    }

    /**
     * Returns the opacity of the boss bar frame (border).
     * 1.0 is fully opaque (default), 0.0 is fully transparent.
     * @return alpha value in range [0, 1]
     */
    public float getFrameAlpha() {
        return 1.0f;
    }

    /**
     * Returns the opacity of the boss bar fill (health bar).
     * 1.0 is fully opaque (default), 0.0 is fully transparent.
     * @return alpha value in range [0, 1]
     */
    public float getFillAlpha() {
        return 1.0f;
    }
}
