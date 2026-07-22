package net.eca.event;

import net.eca.api.EcaAPI;
import net.eca.config.EcaConfiguration;
import net.eca.network.FactionGlowSyncPacket;
import net.eca.network.NetworkHandler;
import net.eca.util.EntityLocationManager;
import net.eca.util.EntityUtil;
import net.eca.util.InvulnerableEntityManager;
import net.eca.util.ResurrectionManager;
import net.eca.util.bossshow.BossShowPlaybackTracker;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.entity_extension.ForceLoadingManager;
import net.eca.util.entity_extension.GlobalEffectOverrideManager;
import net.eca.util.faction.FactionManager;
import net.eca.util.faction.FactionRelation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Event handler for ECA mod events.
 */
public class EcaEventHandler {

    // 每位玩家下次发光扫描的 game time（服务端 tick 数）
    private static final Map<UUID, Long> NEXT_GLOW_SCAN = new HashMap<>();
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
            GlobalEffectOverrideManager.syncToPlayer(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BossShowPlaybackTracker.onPlayerLogout(player);
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EntityExtensionManager.onPlayerChangedDimension(player, event.getFrom());
            GlobalEffectOverrideManager.syncToPlayer(player);
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
        if (!(event.level instanceof ServerLevel serverLevel)) {
            return;
        }

        //START 相位：抢在 ServerLevel.tick() 的 entityManager.tick() 之前校正位置
        //防止字段脏写攻击导致实体被迁移到远方 section、进而触发 pending unload 失锁
        if (event.phase == TickEvent.Phase.START) {
            EntityLocationManager.checkLockedEntities(serverLevel);
            return;
        }

        if (event.phase == TickEvent.Phase.END) {
            EntityExtensionManager.tickDimension(serverLevel);
            ForceLoadingManager.tickDimension(serverLevel);
        }
    }

    //服务端全局 tick：推进 BossShow 会话 + 扫描 range 触发器（每 tick 一次，与维度数量无关）
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        BossShowPlaybackTracker.onServerTick(event.getServer());
    }

    // ==================== 阵营发光扫描 ====================

    // 每位玩家周期性的阵营发光扫描
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        // 观察者模式跳过
        if (player.isSpectator()) return;
        if (!EcaConfiguration.getFactionGlowEnabledSafely()) return;

        long now = player.level().getGameTime();
        long nextScan = NEXT_GLOW_SCAN.getOrDefault(player.getUUID(), 0L);
        if (now < nextScan) return;

        int interval = EcaConfiguration.getFactionGlowUpdateIntervalTicksSafely();
        NEXT_GLOW_SCAN.put(player.getUUID(), now + interval);

        scanAndSyncFactionGlow(player, interval);
    }

    // 扫描玩家周围实体，按阵营关系着色，发送到客户端
    private static void scanAndSyncFactionGlow(ServerPlayer player, int intervalTicks) {
        // 预计算：循环外一次性取值，避免每实体重复 safeGet + parseHexColor
        String playerFaction = FactionManager.getFactionId(player);
        int sameColor = EcaConfiguration.parseHexColor(
                EcaConfiguration.getFactionGlowSameFactionColorSafely(), 0xFF00FF00);
        int friendlyColor = EcaConfiguration.parseHexColor(
                EcaConfiguration.getFactionGlowFriendlyColorSafely(), 0xFF0000FF);
        int hostileColor = EcaConfiguration.parseHexColor(
                EcaConfiguration.getFactionGlowHostileColorSafely(), 0xFFFF0000);
        int neutralColor = EcaConfiguration.parseHexColor(
                EcaConfiguration.getFactionGlowNeutralColorSafely(), 0xFFFFFF00);

        int range = EcaConfiguration.getFactionGlowRangeSafely();
        AABB area = player.getBoundingBox().inflate(range);
        java.util.List<LivingEntity> nearby = player.level().getEntitiesOfClass(
                LivingEntity.class, area, e -> e != player && e.isAlive());

        Map<Integer, Integer> glowMap = new HashMap<>();
        for (LivingEntity entity : nearby) {
            // 只对属于某个阵营的实体发光，无阵营生物（猪、牛等）不发光
            String entityFaction = FactionManager.getFactionId(entity);
            if (entityFaction == null) {
                continue;
            }
            FactionRelation rel = FactionManager.getEffectiveRelation(player, entity);
            int color;
            switch (rel) {
                case SAME_FACTION:
                    color = sameColor;
                    break;
                case FRIENDLY:
                    color = friendlyColor;
                    break;
                case HOSTILE:
                    color = hostileColor;
                    break;
                case NEUTRAL:
                    color = neutralColor;
                    break;
                default:
                    continue;
            }
            glowMap.put(entity.getId(), color);
        }

        // 持续时间略长于扫描间隔，避免闪烁
        int duration = intervalTicks + 10;
        NetworkHandler.sendToPlayer(new FactionGlowSyncPacket(glowMap, duration), player);
    }

    //玩家登出时清理发光扫描计时器，防止内存泄漏
    @SubscribeEvent
    public void onPlayerLoggedOutGlowCleanup(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NEXT_GLOW_SCAN.remove(player.getUUID());
        }
    }

    //服务器停止时清空静态状态，防止单人模式下集成服务器跨存档残留
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        ResurrectionManager.stop();
        ResurrectionManager.clearAll();
        InvulnerableEntityManager.clearAll();
        GlobalEffectOverrideManager.clearAllDimensions();
        EntityExtensionManager.clearAll();
        NEXT_GLOW_SCAN.clear();
    }
}
