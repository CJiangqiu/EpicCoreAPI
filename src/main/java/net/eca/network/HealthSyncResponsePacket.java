package net.eca.network;

import net.eca.util.health.HealthReportManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Client observations affect diagnostics only, never server health or mutation success. */
public record HealthSyncResponsePacket(UUID request, UUID entityUuid, boolean verified, float actual) {
    public static void encode(HealthSyncResponsePacket message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.request());
        buffer.writeUUID(message.entityUuid());
        buffer.writeBoolean(message.verified());
        buffer.writeFloat(message.actual());
    }

    public static HealthSyncResponsePacket decode(FriendlyByteBuf buffer) {
        return new HealthSyncResponsePacket(buffer.readUUID(), buffer.readUUID(), buffer.readBoolean(), buffer.readFloat());
    }

    public static void handle(HealthSyncResponsePacket message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender != null) HealthReportManager.completeClientSync(
                    message.request(), message.entityUuid(), sender, message.verified(), message.actual());
        });
        context.setPacketHandled(true);
    }
}
