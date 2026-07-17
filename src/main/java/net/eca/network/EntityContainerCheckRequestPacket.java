package net.eca.network;

import net.eca.client.ClientEntityUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.Map;
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
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT, () -> () -> ClientHandler.apply(msg)));
        context.setPacketHandled(true);
    }

    // 客户端引用隔离在独立内部类中，避免专用服务端在类校验阶段加载 ClientLevel
    private static final class ClientHandler {
        private static void apply(EntityContainerCheckRequestPacket msg) {
            Map<String, Boolean> result = new LinkedHashMap<>();
            Minecraft minecraft = Minecraft.getInstance();
            ClientLevel clientLevel = minecraft.level;
            if (clientLevel != null) {
                result.putAll(ClientEntityUtil.checkEntityInClientContainers(clientLevel, msg.entityUuid));
            } else {
                result.put("ClientLevel.getEntity(uuid)", false);
                result.put("ClientEntityStorage.entityLookup.byUuid", false);
                result.put("ClientEntityStorage.entityLookup.byId", false);
                result.put("ClientLevel.tickingEntities", false);
                result.put("ClientEntityStorage.sectionStorage", false);
                result.put("ClientEntity.levelCallback", false);
            }
            NetworkHandler.sendToServer(new EntityContainerCheckResponsePacket(msg.requestId, msg.entityUuid, result));
        }
    }
}
