package net.eca.util.entity_extension;

import net.eca.blender.animation.BlenderNodeClock;
import net.eca.blender.entity.BlenderEntityBinding;
import net.eca.blender.entity.BlenderRenderPolicy;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;

@OnlyIn(Dist.CLIENT)
public abstract class BlenderModelExtension implements BlenderEntityBinding {

    @Override
    public final BlenderRenderPolicy renderPolicy() {
        return renderMode() == BlenderRenderMode.REPLACE ? BlenderRenderPolicy.REPLACE : BlenderRenderPolicy.OVERLAY;
    }

    @Override
    public final BlenderNodeClock nodeClock(LivingEntity entity, BlenderNodeClock resourceDefault) {
        BlenderNodeTimeSource selected = nodeTimeSource(entity,
            resourceDefault == BlenderNodeClock.ENTITY ? BlenderNodeTimeSource.ENTITY : BlenderNodeTimeSource.ANIMATION);
        if (selected == null) throw new IllegalArgumentException("Missing node time source");
        return selected == BlenderNodeTimeSource.ENTITY ? BlenderNodeClock.ENTITY : BlenderNodeClock.ANIMATION;
    }

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

    // 默认保留资源选择，允许按实体指定节点时间来源。
    /**
     * Selects the clock for geometry nodes and material time drivers independently of action playback.
     * @param entity the entity being rendered
     * @param resourceDefault the clock selected by the model definition
     * @return the non-null node clock to use for this entity
     */
    public BlenderNodeTimeSource nodeTimeSource(LivingEntity entity, BlenderNodeTimeSource resourceDefault) {
        return resourceDefault;
    }

    /**
     * Supplies client-side numeric geometry node group inputs by socket identifier.
     * @param entity the entity being rendered
     * @return input overrides, or an empty map to retain resource defaults
     */
    public Map<String, Float> nodeParameters(LivingEntity entity) {
        return Map.of();
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
