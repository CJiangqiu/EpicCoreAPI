package net.eca.util.entity_extension;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public abstract class BlenderModelExtension {

    public boolean enabled() {
        return true;
    }

    public abstract ResourceLocation modelId();

    public BlenderRenderMode renderMode() {
        return BlenderRenderMode.ADDITIVE;
    }

    public boolean shouldRender(LivingEntity entity) {
        return entity != null && !entity.isInvisible();
    }

    public String animation(LivingEntity entity) {
        return null;
    }

    public float animationSpeed(LivingEntity entity) {
        return 1.0f;
    }

    public float scale(LivingEntity entity) {
        return 1.0f;
    }

    public float offsetX(LivingEntity entity) {
        return 0.0f;
    }

    public float offsetY(LivingEntity entity) {
        return 0.0f;
    }

    public float offsetZ(LivingEntity entity) {
        return 0.0f;
    }
}
