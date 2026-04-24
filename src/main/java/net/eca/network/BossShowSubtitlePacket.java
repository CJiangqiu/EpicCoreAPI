package net.eca.network;

import net.eca.util.bossshow.BossShowClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//S→C：更新当前 BossShow 演出的字幕文本
public class BossShowSubtitlePacket {

    private final String text;

    public BossShowSubtitlePacket(String text) {
        this.text = text != null ? text : "";
    }

    public static void encode(BossShowSubtitlePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.text, 512);
    }

    public static BossShowSubtitlePacket decode(FriendlyByteBuf buf) {
        return new BossShowSubtitlePacket(buf.readUtf(512));
    }

    public static void handle(BossShowSubtitlePacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandlerRef.onSubtitle(msg)));
        ctx.setPacketHandled(true);
    }

    public String text() { return text; }

    private static final class ClientHandlerRef {
        static void onSubtitle(BossShowSubtitlePacket msg) {
            BossShowClientState.onSubtitle(msg);
        }
    }
}
