package net.eca.network;

import net.eca.client.ClientEntityUtil;
import net.minecraft.network.FriendlyByteBuf;
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
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandlerRef.apply(msg)));
        context.setPacketHandled(true);
    }

    // 客户端引用隔离在独立内部类中，实际逻辑委托给 @OnlyIn(Dist.CLIENT) 的 ClientEntityUtil
    private static final class ClientHandlerRef {
        static void apply(ClientRemovePacket msg) {
            ClientEntityUtil.handleClientRemove(msg.entityId, msg.bossEventUUIDs);
        }
    }
}
