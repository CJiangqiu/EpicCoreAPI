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

    // 是否由 ECA 接管此实体的 boss 血条（清除原生血条 + 改用 ECA BossEvent）。默认 false：不接管时完全不碰实体自带血条
    public boolean enableBossBar() {
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

    /*
     * 该实体类型应自动加入的阵营 ID。
     * 返回 null（默认）表示不自动加入阵营。
     * 返回的阵营 ID 必须已由 @RegisterFaction 注册，否则实体仍会绑定但记录警告。
     */
    /**
     * @return the faction id that entities of this type should automatically join,
     *         or {@code null} to opt out. The faction must be registered via
     *         {@link net.eca.api.RegisterFaction} or {@code EcaAPI.createFaction()}
     *         before entities of this type spawn.
     */
    public String getFactionId() {
        return null;
    }

    @OnlyIn(Dist.CLIENT)
    public abstract BossBarExtension bossBarExtension();

    // 带参重载用于按实体状态条件切换；entity 可能为 null，重写时需自行判空
    @OnlyIn(Dist.CLIENT)
    public BossBarExtension bossBarExtension(LivingEntity entity) {
        return bossBarExtension();
    }

    @OnlyIn(Dist.CLIENT)
    public abstract EntityLayerExtension entityLayerExtension();

    @OnlyIn(Dist.CLIENT)
    public EntityLayerExtension entityLayerExtension(LivingEntity entity) {
        return entityLayerExtension();
    }

    @OnlyIn(Dist.CLIENT)
    public GlobalFogExtension globalFogExtension() {
        return null;
    }

    @OnlyIn(Dist.CLIENT)
    public GlobalFogExtension globalFogExtension(LivingEntity entity) {
        return globalFogExtension();
    }

    @OnlyIn(Dist.CLIENT)
    public GlobalSkyboxExtension globalSkyboxExtension() {
        return null;
    }

    @OnlyIn(Dist.CLIENT)
    public GlobalSkyboxExtension globalSkyboxExtension(LivingEntity entity) {
        return globalSkyboxExtension();
    }

    @OnlyIn(Dist.CLIENT)
    public CombatMusicExtension combatMusicExtension() {
        return null;
    }

    @OnlyIn(Dist.CLIENT)
    public CombatMusicExtension combatMusicExtension(LivingEntity entity) {
        return combatMusicExtension();
    }

    @OnlyIn(Dist.CLIENT)
    public boolean shouldEnableFog(LivingEntity entity) {
        return true;
    }

    @OnlyIn(Dist.CLIENT)
    public boolean shouldEnableSkybox(LivingEntity entity) {
        return true;
    }

    @OnlyIn(Dist.CLIENT)
    public boolean shouldEnableMusic(LivingEntity entity) {
        return true;
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
