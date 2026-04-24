package net.eca.util.bossshow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.eca.util.EcaLogger;
import net.eca.util.bossshow.BossShowDefinition.Marker;
import net.eca.util.bossshow.BossShowDefinition.Sample;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;

//JSON 编解码（samples + markers 模型）
//samples 用紧凑数组形式 [[dx,dy,dz,yaw,pitch],...] 减小体积
public final class BossShowJsonCodec {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private BossShowJsonCodec() {}

    public static BossShowDefinition parse(String json, ResourceLocation id, BossShowDefinition.Source source) {
        if (json == null || json.isEmpty()) {
            EcaLogger.error("BossShow {} JSON is empty", id);
            return null;
        }

        try {
            JsonElement rootEl = JsonParser.parseString(json);
            if (!rootEl.isJsonObject()) {
                EcaLogger.error("BossShow {} root must be a JSON object", id);
                return null;
            }
            JsonObject root = rootEl.getAsJsonObject();

            //target_type 可选：缺省/无效字符串/未注册类型 → null（不绑定具体 entity 类型）
            EntityType<?> targetType = null;
            if (root.has("target_type") && !root.get("target_type").isJsonNull()) {
                String typeStr = root.get("target_type").getAsString();
                if (!typeStr.isEmpty()) {
                    ResourceLocation typeId = ResourceLocation.tryParse(typeStr);
                    if (typeId == null) {
                        EcaLogger.warn("BossShow {} target_type {} is not a valid ResourceLocation; treating as null", id, typeStr);
                    } else if (!BuiltInRegistries.ENTITY_TYPE.containsKey(typeId)) {
                        EcaLogger.warn("BossShow {} target_type {} not registered (mod missing?); treating as null", id, typeId);
                    } else {
                        targetType = BuiltInRegistries.ENTITY_TYPE.get(typeId);
                    }
                }
            }

            //trigger
            Trigger trigger = parseTrigger(root.has("trigger") ? root.getAsJsonObject("trigger") : null, id);

            //cinematic（per-def 单一开关，默认 true）
            boolean cinematic = !root.has("cinematic") || root.get("cinematic").getAsBoolean();

            //allow_repeat（默认 false：一个 viewer 终身只看一次）
            boolean allowRepeat = root.has("allow_repeat") && root.get("allow_repeat").getAsBoolean();

            //anchor_yaw：录制时烤入的参考 yaw（无字段 → 0，沿 Z+ 方向）
            float anchorYawDeg = root.has("anchor_yaw") ? root.get("anchor_yaw").getAsFloat() : 0f;

            //samples
            List<Sample> samples = new ArrayList<>();
            if (root.has("samples") && root.get("samples").isJsonArray()) {
                JsonArray arr = root.getAsJsonArray("samples");
                for (JsonElement el : arr) {
                    if (!el.isJsonArray()) continue;
                    JsonArray row = el.getAsJsonArray();
                    if (row.size() < 5) continue;
                    samples.add(new Sample(
                        row.get(0).getAsDouble(),
                        row.get(1).getAsDouble(),
                        row.get(2).getAsDouble(),
                        row.get(3).getAsFloat(),
                        row.get(4).getAsFloat()
                    ));
                }
            }

            //markers
            List<Marker> markers = new ArrayList<>();
            if (root.has("markers") && root.get("markers").isJsonArray()) {
                JsonArray arr = root.getAsJsonArray("markers");
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject mObj = el.getAsJsonObject();
                    int t = mObj.has("t") ? mObj.get("t").getAsInt() : 0;
                    String evt = mObj.has("event_id") && !mObj.get("event_id").isJsonNull()
                        ? mObj.get("event_id").getAsString() : null;
                    String sub = mObj.has("subtitle") && !mObj.get("subtitle").isJsonNull()
                        ? mObj.get("subtitle").getAsString() : null;
                    if (t < 0 || t >= samples.size()) {
                        EcaLogger.warn("BossShow {} marker tickOffset {} out of range [0,{}); dropping", id, t, samples.size());
                        continue;
                    }
                    Curve curve = mObj.has("curve") ? Curve.fromKey(mObj.get("curve").getAsString()) : Curve.NONE;
                    markers.add(new Marker(t, evt, sub, curve));
                }
            }

            return new BossShowDefinition(id, targetType, trigger, cinematic, allowRepeat, samples, markers, source, anchorYawDeg);
        } catch (Throwable t) {
            EcaLogger.error("BossShow {} JSON parse failed: {}", id, t.getMessage());
            return null;
        }
    }

    private static Trigger parseTrigger(JsonObject obj, ResourceLocation id) {
        if (obj == null) {
            return new Trigger.Custom("");
        }
        String type = obj.has("type") ? obj.get("type").getAsString() : "custom";
        if ("range".equalsIgnoreCase(type)) {
            double radius = obj.has("effect_radius") ? obj.get("effect_radius").getAsDouble() : 32.0;
            if (radius <= 0) {
                EcaLogger.warn("BossShow {} range.effect_radius <= 0, using 32", id);
                radius = 32.0;
            }
            return new Trigger.Range(radius);
        }
        String eventName = obj.has("event_name") && !obj.get("event_name").isJsonNull()
            ? obj.get("event_name").getAsString() : "";
        return new Trigger.Custom(eventName);
    }

    public static String serialize(BossShowDefinition def) {
        JsonObject root = new JsonObject();
        ResourceLocation typeKey = def.targetType() != null
            ? BuiltInRegistries.ENTITY_TYPE.getKey(def.targetType())
            : null;
        if (typeKey != null) {
            root.addProperty("target_type", typeKey.toString());
        }

        JsonObject trig = new JsonObject();
        trig.addProperty("type", def.trigger().type());
        if (def.trigger() instanceof Trigger.Range range) {
            trig.addProperty("effect_radius", range.effectRadius());
        }
        if (def.trigger() instanceof Trigger.Custom custom && !custom.eventName().isEmpty()) {
            trig.addProperty("event_name", custom.eventName());
        }
        root.add("trigger", trig);
        root.addProperty("cinematic", def.cinematic());
        root.addProperty("allow_repeat", def.allowRepeat());
        root.addProperty("anchor_yaw", def.anchorYawDeg());

        JsonArray sArr = new JsonArray();
        for (Sample s : def.samples()) {
            JsonArray row = new JsonArray();
            row.add(round(s.dx()));
            row.add(round(s.dy()));
            row.add(round(s.dz()));
            row.add(s.yaw());
            row.add(s.pitch());
            sArr.add(row);
        }
        root.add("samples", sArr);

        JsonArray mArr = new JsonArray();
        for (Marker m : def.markers()) {
            JsonObject mObj = new JsonObject();
            mObj.addProperty("t", m.tickOffset());
            if (m.eventId() != null) mObj.addProperty("event_id", m.eventId());
            if (m.subtitleText() != null) mObj.addProperty("subtitle", m.subtitleText());
            if (m.curve() != Curve.NONE) mObj.addProperty("curve", m.curve().key());
            mArr.add(mObj);
        }
        root.add("markers", mArr);

        return GSON.toJson(root);
    }

    //保留 4 位小数减小 JSON 体积
    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
