package net.eca.util.entity_extension;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Collections;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public class EntityLayerExtension {

    public boolean enabled() {
        return false;
    }

    public RenderType getRenderType() {
        return null;
    }

    public boolean isGlow() {
        return false;
    }

    public boolean isHurtOverlay() {
        return false;
    }

    public float getAlpha() {
        return 0.5f;
    }

    /**
     * Optional custom texture for the entity overlay layer.
     * <ul>
     * <li>{@code getRenderType() != null, getTexture() == null} → shader‑only overlay (one pass)</li>
     * <li>{@code getRenderType() == null, getTexture() != null} → texture‑only overlay (one pass, vanilla translucent)</li>
     * <li>{@code getRenderType() != null, getTexture() != null} → combined overlay (two passes: texture base + shader on top,
     *     matching the boss‑bar texture‑plus‑shader technique)</li>
     * </ul>
     * @return texture resource location, or {@code null} for shader‑only / no‑overlay
     */
    public ResourceLocation getTexture() {
        return null;
    }

    public boolean shouldRender(LivingEntity entity) {
        return true;
    }

    /**
     * Geo 模型中需要连同子骨骼一起隐藏的骨骼名称。
     *
     * @return 要隐藏的 Geo 骨骼名称集合
     */
    public Set<String> hiddenGeoBones() {
        return Collections.emptySet();
    }

    /**
     * Geo 覆盖层允许重绘的根骨骼名称；空集合表示重绘完整模型。
     *
     * @return 要包含在 Geo 覆盖层中的根骨骼名称集合
     */
    public Set<String> overlayGeoBones() {
        return Collections.emptySet();
    }
}
