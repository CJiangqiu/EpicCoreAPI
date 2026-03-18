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

    public ResourceLocation frameTexture() {
        return null;
    }

    public ResourceLocation fillTexture() {
        return null;
    }

    public RenderType frameRenderType() {
        return null;
    }

    public RenderType fillRenderType() {
        return null;
    }

    public int width() {
        return 0;
    }

    public int height() {
        return 0;
    }

    public int offsetX() {
        return 0;
    }

    public int offsetY() {
        return 0;
    }
}
