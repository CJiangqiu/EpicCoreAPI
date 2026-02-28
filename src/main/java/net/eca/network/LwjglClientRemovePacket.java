package net.eca.network;

import net.eca.util.EcaLogger;
import net.eca.util.reflect.LwjglUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * LWJGL-based client entity removal packet.
 * Uses LWJGL low-level memory channel to bypass bytecode interception.
 */
public class LwjglClientRemovePacket {

    private final int entityId;
    private final List<UUID> bossEventUUIDs;

    public LwjglClientRemovePacket(int entityId, List<UUID> bossEventUUIDs) {
        this.entityId = entityId;
        this.bossEventUUIDs = bossEventUUIDs;
    }

    public static void encode(LwjglClientRemovePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeInt(msg.bossEventUUIDs.size());
        for (UUID uuid : msg.bossEventUUIDs) {
            buf.writeUUID(uuid);
        }
    }

    public static LwjglClientRemovePacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readInt();
        int count = buf.readInt();
        List<UUID> uuids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            uuids.add(buf.readUUID());
        }
        return new LwjglClientRemovePacket(entityId, uuids);
    }

    public static void handle(LwjglClientRemovePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            ClientLevel clientLevel = minecraft.level;
            if (clientLevel != null) {
                Entity entity = clientLevel.getEntity(msg.entityId);
                if (entity != null) {
                    entity.onClientRemoval();
                    LwjglUtil.lwjglClientRemove(clientLevel, entity);
                } else {
                    EcaLogger.info("[LwjglClientRemovePacket] Entity not found (ID: {})", msg.entityId);
                }

                //精确清理目标实体对应的 boss 血条
                removeBossOverlayEntries(minecraft, msg.bossEventUUIDs);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    //从 BossHealthOverlay.events 移除指定 UUID 的 boss 血条
    private static void removeBossOverlayEntries(Minecraft minecraft, List<UUID> bossEventUUIDs) {
        if (bossEventUUIDs.isEmpty()) return;
        try {
            BossHealthOverlay bossOverlay = minecraft.gui.getBossOverlay();
            for (UUID uuid : bossEventUUIDs) {
                bossOverlay.events.remove(uuid);
            }
        } catch (Exception e) {
            EcaLogger.info("[LwjglClientRemovePacket] Failed to remove boss overlay entries: {}", e.getMessage());
        }
    }
}
