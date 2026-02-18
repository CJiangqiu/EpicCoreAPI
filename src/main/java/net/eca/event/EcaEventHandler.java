package net.eca.event;

import net.eca.api.EcaAPI;
import net.eca.util.EntityLocationManager;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.entity_extension.ForceLoadingManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Event handler for ECA mod events.
 */
public class EcaEventHandler {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingDeath(LivingDeathEvent event) {
        if (EcaAPI.isInvulnerable(event.getEntity()) || EcaAPI.isHealthLocked(event.getEntity()) ||
            (EcaAPI.isHealingBanned(event.getEntity()) && EcaAPI.getHealBanValue(event.getEntity()) > 0.0f)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel serverLevel &&
            event.getEntity() instanceof LivingEntity living) {
            EntityExtensionManager.onEntityJoin(living, serverLevel);
            ForceLoadingManager.onEntityJoin(living, serverLevel);
        }
    }

    @SubscribeEvent
    public void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel serverLevel &&
            event.getEntity() instanceof LivingEntity living) {
            ForceLoadingManager.onEntityLeave(living, serverLevel);
            EntityExtensionManager.onEntityLeave(living, serverLevel);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EntityExtensionManager.syncActiveType(player);
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EntityExtensionManager.syncActiveType(player);
        }
    }

    @SubscribeEvent
    public void onPlayerStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player &&
            event.getTarget() instanceof LivingEntity living) {
            EntityExtensionManager.onStartTracking(player, living);
        }
    }

    @SubscribeEvent
    public void onPlayerStopTracking(PlayerEvent.StopTracking event) {
        if (event.getEntity() instanceof ServerPlayer player &&
            event.getTarget() instanceof LivingEntity living) {
            EntityExtensionManager.onStopTracking(player, living);
        }
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END &&
            event.level instanceof ServerLevel serverLevel) {
            EntityExtensionManager.tickDimension(serverLevel);
            EntityLocationManager.checkLockedEntities(serverLevel);
            ForceLoadingManager.tickDimension(serverLevel);
        }
    }
}
