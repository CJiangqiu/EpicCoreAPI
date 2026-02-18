package net.eca.util.entity_extension;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@SuppressWarnings("removal")
public abstract class EntityExtension {
    private final EntityType<?> entityType;
    private final int priority;

    protected EntityExtension(EntityType<?> entityType, int priority) {
        if (entityType == null) {
            throw new IllegalArgumentException("EntityType cannot be null");
        }
        this.entityType = entityType;
        this.priority = priority;
    }

    public EntityType<?> getEntityType() {
        return entityType;
    }

    public int getPriority() {
        return priority;
    }

    public boolean enableForceLoading() {
        return false;
    }

    public boolean shouldShowBossBar(LivingEntity entity) {
        return entity != null && entity.isAlive();
    }

    public boolean enableCustomHealthOverride() {
        return false;
    }

    public Number getCustomHealthValue(LivingEntity entity) {
        return null;
    }

    public boolean enableCustomMaxHealthOverride() {
        return false;
    }

    public Number getCustomMaxHealthValue(LivingEntity entity) {
        return null;
    }

    @OnlyIn(Dist.CLIENT)
    public abstract BossBarExtension bossBarExtension();

    @OnlyIn(Dist.CLIENT)
    public abstract EntityLayerExtension entityLayerExtension();

    @OnlyIn(Dist.CLIENT)
    public GlobalFogExtension globalFogExtension() {
        return null;
    }

    @OnlyIn(Dist.CLIENT)
    public GlobalSkyboxExtension globalSkyboxExtension() {
        return null;
    }

    @OnlyIn(Dist.CLIENT)
    public CombatMusicExtension combatMusicExtension() {
        return null;
    }

    @OnlyIn(Dist.CLIENT)
    protected abstract String getModId();

    @OnlyIn(Dist.CLIENT)
    protected ResourceLocation texture(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.startsWith("textures/") ? path : "textures/" + path;
        return new ResourceLocation(getModId(), normalized);
    }

    @OnlyIn(Dist.CLIENT)
    protected ResourceLocation sound(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return new ResourceLocation(getModId(), path);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof EntityExtension)) return false;
        EntityExtension other = (EntityExtension) obj;
        return this.entityType.equals(other.entityType);
    }

    @Override
    public int hashCode() {
        return entityType.hashCode();
    }
}
