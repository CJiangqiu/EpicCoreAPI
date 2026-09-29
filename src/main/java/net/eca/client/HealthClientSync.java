package net.eca.client;

import net.eca.network.HealthSyncResponsePacket;
import net.eca.network.NetworkHandler;
import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.eca.util.health.EcaSetHealthManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Bounded, latest-request-wins replay on the client thread, followed by a separate-tick readback. */
@OnlyIn(Dist.CLIENT)
public final class HealthClientSync {
    private static final Map<UUID, Pending> PENDING = new LinkedHashMap<>();
    private static long tick;
    private static ClientLevel currentLevel;

    private HealthClientSync() {}

    public static void enqueue(int id, UUID entityUuid, UUID request, float health) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !Float.isFinite(health)) return;
        if (level != currentLevel) {
            clear();
            currentLevel = level;
        }
        if (PENDING.size() >= 256 && !PENDING.containsKey(entityUuid)) {
            reply(request, entityUuid, false, Float.NaN);
            return;
        }
        Pending previous = PENDING.put(entityUuid, new Pending(id, entityUuid, request, health, tick + 60));
        if (previous != null) reply(previous.request, previous.entityUuid, false, Float.NaN);
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != currentLevel) {
            clear();
            currentLevel = minecraft.level;
        }
        if (currentLevel == null || minecraft.isPaused()) return;
        tick++;
        // At most one replay per tick: repeated packets must not multiply the per-operation stall budget.
        for (Iterator<Pending> iterator = PENDING.values().iterator(); iterator.hasNext(); ) {
            Pending pending = iterator.next();
            if (tick < pending.nextTick) continue;
            Entity entity = currentLevel.getEntity(pending.id);
            LivingEntity living = entity instanceof LivingEntity value && value.getUUID().equals(pending.entityUuid)
                    && !value.isRemoved() ? value : null;
            float actual = living == null ? Float.NaN : EcaSetHealthManager.readHealthAnchor(living);
            if (pending.verify && living != null && EcaSetHealthManager.verify(living, pending.health)) {
                iterator.remove();
                reply(pending.request, pending.entityUuid, true, actual);
                return;
            }
            if (tick >= pending.expires || pending.attempts >= 3) {
                iterator.remove();
                reply(pending.request, pending.entityUuid, false, actual);
                return;
            }
            pending.nextTick = tick + 5;
            if (living == null) continue;
            pending.attempts++;
            pending.verify = EntityUtil.setHealthFromSyncChecked(living, pending.health);
            if (pending.verify) pending.nextTick = tick + 1;
            return;
        }
    }

    public static void clear() {
        PENDING.clear();
        currentLevel = null;
        tick = 0;
    }

    private static void reply(UUID request, UUID entityUuid, boolean success, float actual) {
        try {
            NetworkHandler.sendToServer(new HealthSyncResponsePacket(request, entityUuid, success, actual));
        } catch (RuntimeException exception) {
            EcaLogger.info("[HealthSync] response failed: {}", exception.getClass().getSimpleName());
        }
    }

    private static final class Pending {
        final int id;
        final UUID entityUuid;
        final UUID request;
        final float health;
        final long expires;
        int attempts;
        long nextTick;
        boolean verify;

        Pending(int id, UUID entityUuid, UUID request, float health, long expires) {
            this.id = id;
            this.entityUuid = entityUuid;
            this.request = request;
            this.health = health;
            this.expires = expires;
        }
    }
}
