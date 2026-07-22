package net.eca.network;

import net.eca.client.ClientEntityUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class EntityContainerCheckRequestPacket {

    private final UUID requestId;
    private final UUID entityUuid;

    public EntityContainerCheckRequestPacket(UUID requestId, UUID entityUuid) {
        this.requestId = requestId;
        this.entityUuid = entityUuid;
    }

    public static void encode(EntityContainerCheckRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.requestId);
        buf.writeUUID(msg.entityUuid);
    }

    public static EntityContainerCheckRequestPacket decode(FriendlyByteBuf buf) {
        return new EntityContainerCheckRequestPacket(buf.readUUID(), buf.readUUID());
    }

    public static void handle(EntityContainerCheckRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandlerRef.apply(msg)));
        context.setPacketHandled(true);
    }

    // 客户端引用隔离在独立内部类中，实际逻辑委托给 @OnlyIn(Dist.CLIENT) 的 ClientEntityUtil
    private static final class ClientHandlerRef {
        static void apply(EntityContainerCheckRequestPacket msg) {
            ClientEntityUtil.handleContainerCheckRequest(msg.requestId, msg.entityUuid);
        }
    }
}
