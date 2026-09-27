package net.eca.network;

import net.eca.client.ClientEntityUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record EntityTeleportSyncPacket(int entityId, double x, double y, double z,
                                       float yRot, float xRot, boolean onGround, int teleportId) {

    public static void encode(EntityTeleportSyncPacket message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.entityId);
        buffer.writeDouble(message.x);
        buffer.writeDouble(message.y);
        buffer.writeDouble(message.z);
        buffer.writeFloat(message.yRot);
        buffer.writeFloat(message.xRot);
        buffer.writeBoolean(message.onGround);
        buffer.writeVarInt(message.teleportId + 1);
    }

    public static EntityTeleportSyncPacket decode(FriendlyByteBuf buffer) {
        return new EntityTeleportSyncPacket(
                buffer.readVarInt(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readBoolean(),
                buffer.readVarInt() - 1
        );
    }

    public static void handle(EntityTeleportSyncPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                ClientHandler.apply(message)));
        context.setPacketHandled(true);
    }

    private static final class ClientHandler {
        private static void apply(EntityTeleportSyncPacket message) {
            ClientEntityUtil.syncTeleportFromServer(
                    message.entityId,
                    message.x,
                    message.y,
                    message.z,
                    message.yRot,
                    message.xRot,
                    message.onGround,
                    message.teleportId
            );
        }
    }
}
