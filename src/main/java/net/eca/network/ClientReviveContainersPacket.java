package net.eca.network;

import net.eca.client.ClientEntityUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client-side container repair packet.
 * Sent from server to client when a tracked entity still exists on the client but has
 * been dropped from part of the client containers, telling the client to re-register
 * that same instance instead of waiting for a spawn packet it will never receive.
 */
public class ClientReviveContainersPacket {

    private final UUID entityUuid;

    public ClientReviveContainersPacket(UUID entityUuid) {
        this.entityUuid = entityUuid;
    }

    /**
     * Encode the packet to buffer.
     * @param msg the packet to encode
     * @param buf the buffer to write to
     */
    public static void encode(ClientReviveContainersPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.entityUuid);
    }

    /**
     * Decode the packet from buffer.
     * @param buf the buffer to read from
     * @return the decoded packet
     */
    public static ClientReviveContainersPacket decode(FriendlyByteBuf buf) {
        return new ClientReviveContainersPacket(buf.readUUID());
    }

    /**
     * Handle the packet on client side.
     * @param msg the packet to handle
     * @param ctx the network context
     */
    public static void handle(ClientReviveContainersPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandlerRef.apply(msg)));
        context.setPacketHandled(true);
    }

    // 客户端引用隔离在独立内部类中，实际逻辑委托给 @OnlyIn(Dist.CLIENT) 的 ClientEntityUtil
    private static final class ClientHandlerRef {
        static void apply(ClientReviveContainersPacket msg) {
            ClientEntityUtil.handleReviveContainers(msg.entityUuid);
        }
    }
}
