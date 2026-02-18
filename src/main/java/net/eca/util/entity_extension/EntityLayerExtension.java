package net.eca.util.entity_extension;

import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

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
}
