package net.eca.network;

import net.eca.util.entity_extension.EntityExtensionClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class EntityExtensionActiveTypePacket {

    private final ResourceLocation dimensionId;
    private final ResourceLocation typeId;

    public EntityExtensionActiveTypePacket(ResourceLocation dimensionId, ResourceLocation typeId) {
        this.dimensionId = dimensionId;
        this.typeId = typeId;
    }

    public static void encode(EntityExtensionActiveTypePacket message, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(message.dimensionId);
        buffer.writeBoolean(message.typeId != null);
        if (message.typeId != null) {
            buffer.writeResourceLocation(message.typeId);
        }
    }

    public static EntityExtensionActiveTypePacket decode(FriendlyByteBuf buffer) {
        ResourceLocation dimensionId = buffer.readResourceLocation();
        boolean hasType = buffer.readBoolean();
        ResourceLocation typeId = hasType ? buffer.readResourceLocation() : null;
        return new EntityExtensionActiveTypePacket(dimensionId, typeId);
    }

    public static void handle(EntityExtensionActiveTypePacket message, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> EntityExtensionClientState.setActiveType(message.dimensionId, message.typeId));
        ctx.setPacketHandled(true);
    }
}
