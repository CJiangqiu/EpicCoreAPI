package net.eca.network;

import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client-side entity removal packet.
 * Sent from server to client to notify entity removal.
 */
public class EcaClientRemovePacket {

    private final int entityId;

    public EcaClientRemovePacket(int entityId) {
        this.entityId = entityId;
    }

    /**
     * Encode the packet to buffer.
     * @param msg the packet to encode
     * @param buf the buffer to write to
     */
    public static void encode(EcaClientRemovePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
    }

    /**
     * Decode the packet from buffer.
     * @param buf the buffer to read from
     * @return the decoded packet
     */
    public static EcaClientRemovePacket decode(FriendlyByteBuf buf) {
        return new EcaClientRemovePacket(buf.readInt());
    }

    /**
     * Handle the packet on client side.
     * @param msg the packet to handle
     * @param ctx the network context
     */
    public static void handle(EcaClientRemovePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            ClientLevel clientLevel = minecraft.level;
            if (clientLevel != null) {
                Entity entity = clientLevel.getEntity(msg.entityId);
                if (entity != null) {
                    // Call client removal callback
                    entity.onClientRemoval();

                    // Execute client container cleanup
                    EntityUtil.removeFromClientContainers(clientLevel, entity);
                    EcaLogger.info("[EcaClientRemovePacket] Client entity removal executed for entity ID: {}", msg.entityId);
                } else {
                    EcaLogger.info("[EcaClientRemovePacket] Client entity removal: entity not found (ID: {})", msg.entityId);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
