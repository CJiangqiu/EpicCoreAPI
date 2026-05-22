package net.eca.util.bossshow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.eca.util.EcaLogger;
import net.eca.util.bossshow.BossShowDefinition.Frame;
import net.eca.util.bossshow.BossShowDefinition.Keyframe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;

/* JSON 编解码，新帧模型：frames[] 每帧一个 JSON 对象，关键帧帧内含 keyframe 子对象。
 * 不兼容旧版 samples/markers 格式，旧字段被完全忽略。 */
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

            //target_type 可选：缺省/无效字符串/未注册类型 → null
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

            Trigger trigger = parseTrigger(root.has("trigger") ? root.getAsJsonObject("trigger") : null, id);
            boolean cinematic = !root.has("cinematic") || root.get("cinematic").getAsBoolean();
            boolean allowRepeat = root.has("allow_repeat") && root.get("allow_repeat").getAsBoolean();
            float anchorYawDeg = root.has("anchor_yaw") ? root.get("anchor_yaw").getAsFloat() : 0f;

            List<Frame> frames = new ArrayList<>();
            if (root.has("frames") && root.get("frames").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("frames")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject fObj = el.getAsJsonObject();
                    double dx = fObj.has("dx") ? fObj.get("dx").getAsDouble() : 0.0;
                    double dy = fObj.has("dy") ? fObj.get("dy").getAsDouble() : 0.0;
                    double dz = fObj.has("dz") ? fObj.get("dz").getAsDouble() : 0.0;
                    float yaw = fObj.has("yaw") ? fObj.get("yaw").getAsFloat() : 0f;
                    float pitch = fObj.has("pitch") ? fObj.get("pitch").getAsFloat() : 0f;
                    Keyframe kf = null;
                    if (fObj.has("keyframe") && fObj.get("keyframe").isJsonObject()) {
                        kf = parseKeyframe(fObj.getAsJsonObject("keyframe"));
                    }
                    frames.add(new Frame(dx, dy, dz, yaw, pitch, kf));
                }
            }

            return new BossShowDefinition(id, targetType, trigger, cinematic, allowRepeat, frames, source, anchorYawDeg);
        } catch (Throwable t) {
            EcaLogger.error("BossShow {} JSON parse failed: {}", id, t.getMessage());
            return null;
        }
    }

    private static Keyframe parseKeyframe(JsonObject obj) {
        String evt = obj.has("event_id") && !obj.get("event_id").isJsonNull()
            ? obj.get("event_id").getAsString() : null;
        String sub = obj.has("subtitle") && !obj.get("subtitle").isJsonNull()
            ? obj.get("subtitle").getAsString() : null;
        Curve curve = obj.has("curve") ? Curve.fromKey(obj.get("curve").getAsString()) : Curve.NONE;
        return new Keyframe(evt, sub, curve);
    }

    private static Trigger parseTrigger(JsonObject obj, ResourceLocation id) {
        if (obj == null) return new Trigger.Custom("");
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
        if (typeKey != null) root.addProperty("target_type", typeKey.toString());

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

        JsonArray fArr = new JsonArray();
        for (Frame f : def.frames()) {
            JsonObject fObj = new JsonObject();
            fObj.addProperty("dx", round(f.dx()));
            fObj.addProperty("dy", round(f.dy()));
            fObj.addProperty("dz", round(f.dz()));
            fObj.addProperty("yaw", f.yaw());
            fObj.addProperty("pitch", f.pitch());
            if (f.keyframe() != null) {
                Keyframe kf = f.keyframe();
                JsonObject kfObj = new JsonObject();
                if (kf.eventId() != null) kfObj.addProperty("event_id", kf.eventId());
                if (kf.subtitleText() != null) kfObj.addProperty("subtitle", kf.subtitleText());
                if (kf.curve() != Curve.NONE) kfObj.addProperty("curve", kf.curve().key());
                fObj.add("keyframe", kfObj);
            }
            fArr.add(fObj);
        }
        root.add("frames", fArr);

        return GSON.toJson(root);
    }

    //保留 4 位小数减小 JSON 体积
    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
