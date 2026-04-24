package net.eca.util.bossshow;

import net.eca.util.bossshow.BossShowDefinition.Marker;
import net.eca.util.bossshow.BossShowDefinition.Sample;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

//Sample / Marker / Trigger 在 FriendlyByteBuf 上的共享序列化逻辑
public final class BossShowNetCodec {

    private BossShowNetCodec() {}

    public static void writeNullableRL(FriendlyByteBuf buf, ResourceLocation rl) {
        buf.writeBoolean(rl != null);
        if (rl != null) buf.writeResourceLocation(rl);
    }

    public static ResourceLocation readNullableRL(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readResourceLocation() : null;
    }

    public static void writeSamples(FriendlyByteBuf buf, List<Sample> samples) {
        buf.writeVarInt(samples.size());
        for (Sample s : samples) {
            buf.writeDouble(s.dx());
            buf.writeDouble(s.dy());
            buf.writeDouble(s.dz());
            buf.writeFloat(s.yaw());
            buf.writeFloat(s.pitch());
        }
    }

    public static List<Sample> readSamples(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Sample> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double dx = buf.readDouble();
            double dy = buf.readDouble();
            double dz = buf.readDouble();
            float yaw = buf.readFloat();
            float pitch = buf.readFloat();
            out.add(new Sample(dx, dy, dz, yaw, pitch));
        }
        return out;
    }

    public static void writeMarkers(FriendlyByteBuf buf, List<Marker> markers) {
        buf.writeVarInt(markers.size());
        for (Marker m : markers) {
            buf.writeVarInt(m.tickOffset());
            buf.writeBoolean(m.eventId() != null);
            if (m.eventId() != null) buf.writeUtf(m.eventId());
            buf.writeBoolean(m.subtitleText() != null);
            if (m.subtitleText() != null) buf.writeUtf(m.subtitleText());
            buf.writeByte(m.curve().ordinal());
        }
    }

    public static List<Marker> readMarkers(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Marker> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int t = buf.readVarInt();
            boolean hasEvt = buf.readBoolean();
            String eid = hasEvt ? buf.readUtf(256) : null;
            boolean hasSub = buf.readBoolean();
            String sub = hasSub ? buf.readUtf(512) : null;
            int ci = buf.readByte();
            Curve[] cv = Curve.values();
            Curve curve = (ci >= 0 && ci < cv.length) ? cv[ci] : Curve.NONE;
            out.add(new Marker(t, eid, sub, curve));
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
