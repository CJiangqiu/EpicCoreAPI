package net.eca.util.entity_extension;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class BossBarExtension {

    public boolean enabled() {
        return false;
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
}
