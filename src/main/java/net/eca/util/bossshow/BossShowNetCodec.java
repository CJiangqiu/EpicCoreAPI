package net.eca.util.bossshow;

import net.eca.util.bossshow.BossShowDefinition.Frame;
import net.eca.util.bossshow.BossShowDefinition.Keyframe;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

//Frame / Keyframe / Trigger 在 FriendlyByteBuf 上的共享序列化逻辑
public final class BossShowNetCodec {

    private BossShowNetCodec() {}

    public static void writeNullableRL(FriendlyByteBuf buf, ResourceLocation rl) {
        buf.writeBoolean(rl != null);
        if (rl != null) buf.writeResourceLocation(rl);
    }

    public static ResourceLocation readNullableRL(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readResourceLocation() : null;
    }

    public static void writeFrames(FriendlyByteBuf buf, List<Frame> frames) {
        buf.writeVarInt(frames.size());
        for (Frame f : frames) {
            buf.writeDouble(f.dx());
            buf.writeDouble(f.dy());
            buf.writeDouble(f.dz());
            buf.writeFloat(f.yaw());
            buf.writeFloat(f.pitch());
            Keyframe kf = f.keyframe();
            buf.writeBoolean(kf != null);
            if (kf != null) {
                buf.writeBoolean(kf.eventId() != null);
                if (kf.eventId() != null) buf.writeUtf(kf.eventId());
                buf.writeBoolean(kf.subtitleText() != null);
                if (kf.subtitleText() != null) buf.writeUtf(kf.subtitleText());
                buf.writeByte(kf.curve().ordinal());
            }
        }
    }

    public static List<Frame> readFrames(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Frame> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double dx = buf.readDouble();
            double dy = buf.readDouble();
            double dz = buf.readDouble();
            float yaw = buf.readFloat();
            float pitch = buf.readFloat();
            boolean hasKf = buf.readBoolean();
            Keyframe kf = null;
            if (hasKf) {
                boolean hasEvt = buf.readBoolean();
                String eid = hasEvt ? buf.readUtf(256) : null;
                boolean hasSub = buf.readBoolean();
                String sub = hasSub ? buf.readUtf(512) : null;
                int ci = buf.readByte() & 0xFF;
                Curve[] cv = Curve.values();
                Curve curve = (ci < cv.length) ? cv[ci] : Curve.NONE;
                kf = new Keyframe(eid, sub, curve);
            }
            out.add(new Frame(dx, dy, dz, yaw, pitch, kf));
        }
        return out;
    }

    public static void writeTrigger(FriendlyByteBuf buf, Trigger trigger) {
        buf.writeUtf(trigger.type());
        buf.writeDouble(trigger instanceof Trigger.Range r ? r.effectRadius() : 0.0);
        buf.writeUtf(trigger instanceof Trigger.Custom c ? c.eventName() : "");
    }

    public static Trigger readTrigger(FriendlyByteBuf buf) {
        String type = buf.readUtf(64);
        double radius = buf.readDouble();
        String eventName = buf.readUtf(256);
        return "range".equals(type) ? new Trigger.Range(radius) : new Trigger.Custom(eventName);
    }
}
