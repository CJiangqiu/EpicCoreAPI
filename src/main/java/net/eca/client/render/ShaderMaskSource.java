package net.eca.client.render;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Selects the texture source used by a {@link ShaderMaskPass}.
 */
@OnlyIn(Dist.CLIENT)
public enum ShaderMaskSource {
    /** No color mask; the shader covers the submitted geometry. */
    NONE,
    /** Sample an external UV-aligned mask texture. */
    TEXTURE,
    /** Sample the model's currently bound base texture for legacy Color-Key behavior. */
    BASE_TEXTURE
}
