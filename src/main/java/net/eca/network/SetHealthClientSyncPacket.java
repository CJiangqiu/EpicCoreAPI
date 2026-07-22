package net.eca.network;

import net.eca.client.ClientEntityUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/*
 * 服务端改血成功后 → 追踪客户端对本地实体重跑 ECA 改血。
 * 自定义存储型实体(RuneBank/ICU/私有字段等)客户端也有独立一份存储，服务端改动不会自动同步；
 * 客户端重跑同一条逆向链打穿本地存储，使其血条/显示随之刷新。
 */
public final class SetHealthClientSyncPacket {

    private final int entityId;
    private final float health;

    public SetHealthClientSyncPacket(int entityId, float health) {
        this.entityId = entityId;
        this.health = health;
    }

    public static void encode(SetHealthClientSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeFloat(msg.health);
    }

    public static SetHealthClientSyncPacket decode(FriendlyByteBuf buf) {
        return new SetHealthClientSyncPacket(buf.readInt(), buf.readFloat());
    }

    public static void handle(SetHealthClientSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandlerRef.apply(msg.entityId, msg.health)));
        context.setPacketHandled(true);
    }

    // 客户端引用委托给 @OnlyIn(Dist.CLIENT) 的 ClientEntityUtil
    private static final class ClientHandlerRef {
        static void apply(int entityId, float health) {
            ClientEntityUtil.syncHealthFromServer(entityId, health);
        }
    }
}
