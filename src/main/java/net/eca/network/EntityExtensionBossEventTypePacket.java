package net.eca.network;

import net.eca.util.entity_extension.EntityExtensionClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class EntityExtensionBossEventTypePacket {

    private final UUID bossEventId;
    private final ResourceLocation typeId;
    private final UUID entityUuid;

    public EntityExtensionBossEventTypePacket(UUID bossEventId, ResourceLocation typeId, UUID entityUuid) {
        this.bossEventId = bossEventId;
        this.typeId = typeId;
        this.entityUuid = entityUuid;
    }

    public static void encode(EntityExtensionBossEventTypePacket message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.bossEventId);
        buffer.writeBoolean(message.typeId != null);
        if (message.typeId != null) {
            buffer.writeResourceLocation(message.typeId);
        }
        buffer.writeBoolean(message.entityUuid != null);
        if (message.entityUuid != null) {
            buffer.writeUUID(message.entityUuid);
        }
    }

    public static EntityExtensionBossEventTypePacket decode(FriendlyByteBuf buffer) {
        UUID bossEventId = buffer.readUUID();
        boolean hasType = buffer.readBoolean();
        ResourceLocation typeId = hasType ? buffer.readResourceLocation() : null;
        boolean hasEntityUuid = buffer.readBoolean();
        UUID entityUuid = hasEntityUuid ? buffer.readUUID() : null;
        return new EntityExtensionBossEventTypePacket(bossEventId, typeId, entityUuid);
    }

    public static void handle(EntityExtensionBossEventTypePacket message, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            EntityExtensionClientState.setBossEventType(message.bossEventId, message.typeId, message.entityUuid);
        });
        ctx.setPacketHandled(true);
    }
}
