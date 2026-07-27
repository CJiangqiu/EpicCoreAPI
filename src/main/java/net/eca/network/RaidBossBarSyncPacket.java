package net.eca.network;

import net.eca.util.raid.RaidBarState;
import net.eca.util.raid.RaidClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/*
 * 服务端 → 客户端：同步一条 Boss 血条所属的袭击状态。
 *
 * 客户端不持有袭击实例，此包是渲染层识别"这条血条属于哪场袭击"并驱动
 * RaidBossBarExtension 条件方法的唯一途径。state 为 null 表示解除映射
 * （袭击结束或玩家离开参与范围）。
 */
public final class RaidBossBarSyncPacket {

    private final UUID bossEventId;
    private final RaidBarState state;

    public RaidBossBarSyncPacket(UUID bossEventId, RaidBarState state) {
        this.bossEventId = bossEventId;
        this.state = state;
    }

    public static void encode(RaidBossBarSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.bossEventId);
        buf.writeBoolean(msg.state != null);
        if (msg.state != null) {
            RaidBarState.encode(msg.state, buf);
        }
    }

    public static RaidBossBarSyncPacket decode(FriendlyByteBuf buf) {
        UUID bossEventId = buf.readUUID();
        RaidBarState state = buf.readBoolean() ? RaidBarState.decode(buf) : null;
        return new RaidBossBarSyncPacket(bossEventId, state);
    }

    public static void handle(RaidBossBarSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT, () -> () -> RaidClientState.setBarState(msg.bossEventId, msg.state)));
        context.setPacketHandled(true);
    }
}
