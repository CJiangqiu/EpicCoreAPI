package net.eca.util.entity_extension;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;

import java.util.UUID;

public class EcaBossEvent extends ServerBossEvent {

    private final UUID entityUuid;

    public EcaBossEvent(UUID entityUuid, Component name) {
        super(name, BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.PROGRESS);
        this.entityUuid = entityUuid;
        this.setVisible(true);
    }

    public UUID getEntityUuid() {
        return entityUuid;
    }

    public void update(Component name, float currentHealth, float maxHealth) {
        this.setName(name);
        float safeMaxHealth = maxHealth > 0.0f ? maxHealth : 1.0f;
        float safeCurrentHealth = Mth.clamp(currentHealth, 0.0f, safeMaxHealth);
        this.setProgress(safeCurrentHealth / safeMaxHealth);
    }
}
