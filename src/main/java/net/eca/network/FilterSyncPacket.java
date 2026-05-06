package net.eca.network;

import net.eca.client.render.shader.FilterRenderer;
import net.eca.util.filter.FilterType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkDirection;

import java.util.function.Supplier;

public class FilterSyncPacket {

    private final int filterOrdinal;
    private final boolean enable;

    public FilterSyncPacket(FilterType filter, boolean enable) {
        this.filterOrdinal = filter.ordinal();
        this.enable = enable;
    }

    private FilterSyncPacket(int filterOrdinal, boolean enable) {
        this.filterOrdinal = filterOrdinal;
        this.enable = enable;
    }

    public static void encode(FilterSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.filterOrdinal);
        buf.writeBoolean(msg.enable);
    }

    public static FilterSyncPacket decode(FriendlyByteBuf buf) {
        return new FilterSyncPacket(buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(FilterSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
                return;
            }
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandlerRef.onSync(msg));
        });
        context.setPacketHandled(true);
    }

    private static final class ClientHandlerRef {
        static void onSync(FilterSyncPacket msg) {
            FilterType[] types = FilterType.values();
            if (msg.filterOrdinal < 0 || msg.filterOrdinal >= types.length) {
                return;
            }
            FilterType filter = types[msg.filterOrdinal];
            if (msg.enable) {
                FilterRenderer.enable(filter);
            } else {
                FilterRenderer.disable(filter);
            }
        }
    }
}
