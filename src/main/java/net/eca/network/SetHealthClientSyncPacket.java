package net.eca.network;

import net.eca.client.HealthClientSync;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;
import java.util.UUID;

/*
 * 服务端改血成功后 → 追踪客户端对本地实体重跑 ECA 改血。
 * 自定义存储型实体客户端也有独立一份存储，服务端改动不会自动同步；
 * 客户端重跑同一条逆向链打穿本地存储，使其血条/显示随之刷新。
 */
public final class SetHealthClientSyncPacket {

    private final int entityId;
    private final float health;
    private final UUID entityUuid;
    private final UUID request;

    public SetHealthClientSyncPacket(int entityId, UUID entityUuid, UUID request, float health) {
        this.entityId = entityId;
        this.entityUuid = entityUuid;
        this.request = request;
        this.health = health;
    }

    public static void encode(SetHealthClientSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeFloat(msg.health);
        buf.writeUUID(msg.entityUuid);
        buf.writeUUID(msg.request);
    }

    public static SetHealthClientSyncPacket decode(FriendlyByteBuf buf) {
        int id = buf.readInt();
        float health = buf.readFloat();
        return new SetHealthClientSyncPacket(id, buf.readUUID(), buf.readUUID(), health);
    }

    public static void handle(SetHealthClientSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandlerRef.apply(msg)));
        context.setPacketHandled(true);
    }

    // 公共包处理器不直接加载客户端队列。
    private static final class ClientHandlerRef {
        static void apply(SetHealthClientSyncPacket msg) {
            HealthClientSync.enqueue(msg.entityId, msg.entityUuid, msg.request, msg.health);
        }
    }
}
