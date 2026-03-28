package net.eca.network;

import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client-side entity removal packet.
 * Sent from server to client to notify entity removal.
 */
public class ClientRemovePacket {

    private final int entityId;
    private final List<UUID> bossEventUUIDs;

    public ClientRemovePacket(int entityId, List<UUID> bossEventUUIDs) {
        this.entityId = entityId;
        this.bossEventUUIDs = bossEventUUIDs;
    }

    /**
     * Encode the packet to buffer.
     * @param msg the packet to encode
     * @param buf the buffer to write to
     */
    public static void encode(ClientRemovePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeInt(msg.bossEventUUIDs.size());
        for (UUID uuid : msg.bossEventUUIDs) {
            buf.writeUUID(uuid);
        }
    }

    /**
     * Decode the packet from buffer.
     * @param buf the buffer to read from
     * @return the decoded packet
     */
    public static ClientRemovePacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readInt();
        int count = buf.readInt();
        List<UUID> uuids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            uuids.add(buf.readUUID());
        }
        return new ClientRemovePacket(entityId, uuids);
    }

    /**
     * Handle the packet on client side.
     * @param msg the packet to handle
     * @param ctx the network context
     */
    public static void handle(ClientRemovePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            ClientLevel clientLevel = minecraft.level;
            if (clientLevel != null) {
                Entity entity = clientLevel.getEntity(msg.entityId);
                if (entity != null) {
                    entity.onClientRemoval();
                    entity.invalidateCaps();
                    entity.setRemoved(Entity.RemovalReason.DISCARDED);
                    entity.stopRiding();
                    entity.onRemovedFromWorld();
                    entity.levelCallback = EntityInLevelCallback.NULL;
                    EntityUtil.removeFromClientContainers(clientLevel, entity);
                } else {
                    EcaLogger.debug("[ClientRemovePacket] Client entity removal: entity not found (ID: {})", msg.entityId);
                }

                removeBossOverlayEntries(minecraft, msg.bossEventUUIDs);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void removeBossOverlayEntries(Minecraft minecraft, List<UUID> bossEventUUIDs) {
        if (bossEventUUIDs.isEmpty()) return;
        try {
            BossHealthOverlay bossOverlay = minecraft.gui.getBossOverlay();
            for (UUID uuid : bossEventUUIDs) {
                bossOverlay.events.remove(uuid);
            }
        } catch (Exception e) {
            EcaLogger.info("[ClientRemovePacket] Failed to remove boss overlay entries: {}", e.getMessage());
        }
    }
}
