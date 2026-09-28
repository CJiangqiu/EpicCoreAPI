package net.eca.network;

import net.eca.util.EntityUtil.ServerTeleportConnectionBridge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record EntityTeleportAckPacket(int teleportId, double x, double y, double z) {

    public static void encode(EntityTeleportAckPacket message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.teleportId);
        buffer.writeDouble(message.x);
        buffer.writeDouble(message.y);
        buffer.writeDouble(message.z);
    }

    public static EntityTeleportAckPacket decode(FriendlyByteBuf buffer) {
        return new EntityTeleportAckPacket(
                buffer.readVarInt(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble()
        );
    }

    public static void handle(EntityTeleportAckPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null && sender.connection instanceof ServerTeleportConnectionBridge bridge) {
            bridge.eca$completeTeleport(message.teleportId, message.x, message.y, message.z);
        }
        context.setPacketHandled(true);
    }
}
