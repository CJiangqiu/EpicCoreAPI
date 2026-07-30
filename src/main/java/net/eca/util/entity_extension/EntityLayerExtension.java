package net.eca.util.entity_extension;

import net.eca.client.render.ShaderMaskPass;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public class EntityLayerExtension {

    public boolean enabled() {
        return false;
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

    /**
     * Optional UV-aligned mask texture for restricting the shader pass.
     * The mask must use the same UV layout as the rendered entity model.
     *
     * @return mask texture resource location, or {@code null} to shade the complete overlay
     * @deprecated Return the mask texture from {@link #getShaderPasses()} instead.
     */
    @Deprecated
    public ResourceLocation getMaskTexture() {
        return null;
    }

    /**
     * Target RGB color in the mask texture. Alpha bits are ignored.
     *
     * @return packed RGB color, black by default
     * @deprecated Return the target color from {@link #getShaderPasses()} instead.
     */
    @Deprecated
    public int getMaskColor() {
        return 0x000000;
    }

    /**
     * Maximum normalized RGB distance from {@link #getMaskColor()} that remains visible.
     * A small tolerance keeps filtered mask edges stable without selecting unrelated colors.
     *
     * @return non-negative RGB distance tolerance
     * @deprecated Return the color tolerance from {@link #getShaderPasses()} instead.
     */
    @Deprecated
    public float getMaskTolerance() {
        return 0.05f;
    }

    /**
     * Returns the ordered shader mask passes for this entity layer.
     * Each pass redraws the model with its own RenderType, mask texture, target color, tolerance, and opacity.
     * Later passes render over earlier passes when selected regions overlap.
     *
     * @return ordered shader mask passes, or an empty list when no shader overlay is required
     */
    public List<ShaderMaskPass> getShaderPasses() {
        RenderType renderType = getRenderType();
        if (renderType == null) {
            return List.of();
        }
        return List.of(ShaderMaskPass.masked(renderType, getMaskTexture(),
            getMaskColor(), getMaskTolerance(), getAlpha()));
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
