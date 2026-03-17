package net.eca.network;

import net.eca.util.EntityUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public class EntityContainerCheckResponsePacket {

    private final UUID requestId;
    private final UUID entityUuid;
    private final Map<String, Boolean> result;

    public EntityContainerCheckResponsePacket(UUID requestId, UUID entityUuid, Map<String, Boolean> result) {
        this.requestId = requestId;
        this.entityUuid = entityUuid;
        this.result = result;
    }

    public static void encode(EntityContainerCheckResponsePacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.requestId);
        buf.writeUUID(msg.entityUuid);
        buf.writeVarInt(msg.result.size());
        for (Map.Entry<String, Boolean> entry : msg.result.entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeBoolean(entry.getValue());
        }
    }

    public static EntityContainerCheckResponsePacket decode(FriendlyByteBuf buf) {
        UUID requestId = buf.readUUID();
        UUID entityUuid = buf.readUUID();
        int size = buf.readVarInt();
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            result.put(buf.readUtf(), buf.readBoolean());
        }
        return new EntityContainerCheckResponsePacket(requestId, entityUuid, result);
    }

    public static void handle(EntityContainerCheckResponsePacket msg, Supplier<NetworkEvent.Context> ctx) {
        EntityUtil.completeClientContainerCheck(msg.requestId, msg.entityUuid, msg.result);
        ctx.get().setPacketHandled(true);
    }
}
