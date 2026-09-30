package net.eca.blender.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.eca.blender.animation.BlenderNodeClock;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

public record BlenderModelDefinition(
    String modelFile,
    float scale,
    Vector3f translation,
    Vector3f rotation,
    String defaultAnimation,
    boolean loop,
    Set<String> hiddenNodes,
    String collection,
    String object,
    Map<String, Float> parameters,
    BlenderNodeClock nodeTimeSource
) {
    public static BlenderModelDefinition parse(JsonObject json) {
        String model = json.has("model") ? json.get("model").getAsString() : "model.glb";
        if (model.isBlank() || model.contains("..") || model.startsWith("/") || model.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid model path: " + model);
        }
        float scale = json.has("scale") ? json.get("scale").getAsFloat() : 1.0f;
        Vector3f translation = readVector(json.getAsJsonArray("translation"), new Vector3f());
        Vector3f rotation = readVector(json.getAsJsonArray("rotation"), new Vector3f());
        String animation = json.has("default_animation") ? json.get("default_animation").getAsString() : null;
        boolean loop = !json.has("loop") || json.get("loop").getAsBoolean();
        String clock = json.has("node_time_source") ? json.get("node_time_source").getAsString() : "entity";
        BlenderNodeClock nodeTimeSource = switch (clock) {
            case "entity" -> BlenderNodeClock.ENTITY;
            case "animation" -> BlenderNodeClock.ANIMATION;
            default -> throw new IllegalArgumentException("Invalid node_time_source: " + clock);
        };
        Set<String> hiddenNodes = new HashSet<>();
        JsonArray hidden = json.getAsJsonArray("hidden_nodes");
        if (hidden != null) {
            hidden.forEach(element -> hiddenNodes.add(element.getAsString()));
        }
        String collection = json.has("collection") ? json.get("collection").getAsString() : null;
        String object = json.has("object") ? json.get("object").getAsString() : null;
        if (collection != null && object != null) throw new IllegalArgumentException("Select collection or object, not both");
        Map<String, Float> parameters = new HashMap<>();
        if (json.has("parameters")) json.getAsJsonObject("parameters").entrySet().forEach(entry -> {
            float value = entry.getValue().getAsFloat();
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite node parameter " + entry.getKey());
            parameters.put(entry.getKey(), value);
        });
        return new BlenderModelDefinition(model, scale, translation, rotation, animation, loop,
            Set.copyOf(hiddenNodes), collection, object, Map.copyOf(parameters), nodeTimeSource);
    }

    private static Vector3f readVector(JsonArray array, Vector3f fallback) {
        if (array == null) {
            return fallback;
        }
        if (array.size() != 3) {
            throw new IllegalArgumentException("Expected a three-component vector");
        }
        return new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
    }
}
