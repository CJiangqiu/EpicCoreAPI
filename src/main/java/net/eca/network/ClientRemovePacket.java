package net.eca.network;

import net.eca.client.ClientEntityUtil;
import net.eca.util.EcaLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
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
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT, () -> () -> ClientHandler.apply(msg)));
        context.setPacketHandled(true);
    }

    // 客户端引用隔离在独立内部类中，避免专用服务端在类校验阶段加载 ClientLevel
    private static final class ClientHandler {
        private static void apply(ClientRemovePacket msg) {
            Minecraft minecraft = Minecraft.getInstance();
            ClientLevel clientLevel = minecraft.level;
            if (clientLevel != null) {
                Entity entity = ClientEntityUtil.getEntityById(clientLevel, msg.entityId);
                if (entity != null) {
                    entity.onClientRemoval();
                    entity.invalidateCaps();
                    entity.setRemoved(Entity.RemovalReason.DISCARDED);
                    entity.stopRiding();
                    entity.onRemovedFromWorld();
                    entity.levelCallback = EntityInLevelCallback.NULL;
                    ClientEntityUtil.removeFromClientContainers(clientLevel, entity);
                } else {
                    EcaLogger.debug("[ClientRemovePacket] Client entity removal: entity not found (ID: {})", msg.entityId);
                }

                removeBossOverlayEntries(minecraft, msg.bossEventUUIDs);
            }
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
}
