package net.eca.network;

import net.eca.client.FactionGlowData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/*
 * 服务端 → 客户端：同步玩家周围实体的阵营发光颜色映射。
 *
 * 服务端定期扫描玩家附近实体，解析阵营关系，将 entityId → ARGB 颜色映射
 * 通过此包发送到客户端。客户端存储在 FactionGlowData 中，由 EntityMixin
 * 注入 isCurrentlyGlowing / getTeamColor 消费。
 */
public final class FactionGlowSyncPacket {

    private final Map<Integer, Integer> glowMap;
    private final int durationTicks;

    public FactionGlowSyncPacket(Map<Integer, Integer> glowMap, int durationTicks) {
        this.glowMap = glowMap;
        this.durationTicks = durationTicks;
    }

    public static void encode(FactionGlowSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.durationTicks);
        Map<Integer, Integer> map = msg.glowMap;
        buf.writeInt(map.size());
        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
            buf.writeInt(entry.getKey());
            buf.writeInt(entry.getValue());
        }
    }

    public static FactionGlowSyncPacket decode(FriendlyByteBuf buf) {
        int durationTicks = buf.readInt();
        int size = buf.readInt();
        Map<Integer, Integer> map = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            map.put(buf.readInt(), buf.readInt());
        }
        return new FactionGlowSyncPacket(map, durationTicks);
    }

    public static void handle(FactionGlowSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT, () -> () -> FactionGlowData.update(msg.glowMap, msg.durationTicks)));
        context.setPacketHandled(true);
    }
}
