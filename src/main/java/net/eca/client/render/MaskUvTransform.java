package net.eca.client.render;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public record MaskUvTransform(float minU, float minV, float scaleU, float scaleV) {

    public static final MaskUvTransform IDENTITY = new MaskUvTransform(0.0f, 0.0f, 1.0f, 1.0f);

    public static MaskUvTransform fromSprite(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return IDENTITY;
        }
        float rangeU = Math.max(sprite.getU1() - sprite.getU0(), 1.0e-6f);
        float rangeV = Math.max(sprite.getV1() - sprite.getV0(), 1.0e-6f);
        return new MaskUvTransform(sprite.getU0(), sprite.getV0(), 1.0f / rangeU, 1.0f / rangeV);
    }
}
