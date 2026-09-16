package net.eca.client.render.blender;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;

record BlenderModelDefinition(
    String modelFile,
    float scale,
    Vector3f translation,
    Vector3f rotation,
    String defaultAnimation,
    boolean loop,
    Set<String> hiddenNodes
) {
    static BlenderModelDefinition parse(JsonObject json) {
        String model = json.has("model") ? json.get("model").getAsString() : "model.glb";
        if (model.isBlank() || model.contains("..") || model.startsWith("/") || model.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid model path: " + model);
        }
        float scale = json.has("scale") ? json.get("scale").getAsFloat() : 1.0f;
        Vector3f translation = readVector(json.getAsJsonArray("translation"), new Vector3f());
        Vector3f rotation = readVector(json.getAsJsonArray("rotation"), new Vector3f());
        String animation = json.has("default_animation") ? json.get("default_animation").getAsString() : null;
        boolean loop = !json.has("loop") || json.get("loop").getAsBoolean();
        Set<String> hiddenNodes = new HashSet<>();
        JsonArray hidden = json.getAsJsonArray("hidden_nodes");
        if (hidden != null) {
            hidden.forEach(element -> hiddenNodes.add(element.getAsString()));
        }
        return new BlenderModelDefinition(model, scale, translation, rotation, animation, loop,
            Set.copyOf(hiddenNodes));
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
