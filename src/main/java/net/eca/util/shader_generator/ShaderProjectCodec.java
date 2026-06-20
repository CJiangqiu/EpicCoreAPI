package net.eca.util.shader_generator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.eca.util.EcaLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class ShaderProjectCodec {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = Paths.get("config", "eca", "shadergenerator");

    public static String serialize(ShaderCompositionProject project) {
        JsonObject root = new JsonObject();
        root.addProperty("export_mode", project.exportMode().name().toLowerCase());

        JsonArray layersArray = new JsonArray();
        for (ShaderLayer layer : project.layers()) {
            JsonObject layerObj = new JsonObject();
            layerObj.addProperty("name", layer.name());
            layerObj.addProperty("visible", layer.visible());
            layerObj.addProperty("blend_mode", layer.blendMode().name().toLowerCase());
            layerObj.addProperty("opacity", layer.opacity());

            JsonArray elementsArray = new JsonArray();
            for (ShaderModuleInstance element : layer.elements()) {
                JsonObject elementObj = new JsonObject();
                elementObj.addProperty("definition", element.definition().id());
                elementObj.addProperty("enabled", element.enabled());

                JsonObject valuesObj = new JsonObject();
                for (var entry : element.values().entrySet()) {
                    valuesObj.addProperty(entry.getKey(), entry.getValue());
                }
                elementObj.add("values", valuesObj);
                elementsArray.add(elementObj);
            }
            layerObj.add("elements", elementsArray);
            layersArray.add(layerObj);
        }
        root.add("layers", layersArray);
        return GSON.toJson(root) + "\n";
    }

    public static void deserializeInto(String json, ShaderCompositionProject target) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        target.getLayersInternal().clear();
        String modeName = root.get("export_mode").getAsString().toUpperCase();
        target.setExportMode(ShaderExportMode.valueOf(modeName));

        JsonArray layersArray = root.getAsJsonArray("layers");
        for (int li = 0; li < layersArray.size(); li++) {
            JsonObject layerObj = layersArray.get(li).getAsJsonObject();
            ShaderLayer layer = new ShaderLayer(
                layerObj.get("name").getAsString(),
                layerObj.get("visible").getAsBoolean(),
                ShaderLayerBlendMode.valueOf(layerObj.get("blend_mode").getAsString().toUpperCase()),
                layerObj.get("opacity").getAsFloat()
            );
            target.getLayersInternal().add(layer);

            JsonArray elementsArray = layerObj.getAsJsonArray("elements");
            for (int ei = 0; ei < elementsArray.size(); ei++) {
                JsonObject elementObj = elementsArray.get(ei).getAsJsonObject();
                ShaderModuleDefinition def = ShaderModuleRegistry.get(elementObj.get("definition").getAsString());
                if (def == null) {
                    EcaLogger.warn("Unknown shader module definition: {} — skipping",
                        elementObj.get("definition").getAsString());
                    continue;
                }
                ShaderModuleInstance element = def.createInstance();
                element.setEnabled(elementObj.get("enabled").getAsBoolean());

                JsonObject valuesObj = elementObj.getAsJsonObject("values");
                for (var entry : valuesObj.entrySet()) {
                    try {
                        element.setValue(entry.getKey(), entry.getValue().getAsFloat());
                    } catch (IllegalArgumentException ignored) {
                        /* 未知参数 — 使用默认值 */
                    }
                }
                layer.getElementsInternal().add(element);
            }
        }
    }

    public static boolean save(String name, ShaderCompositionProject project) {
        try {
            Path target = CONFIG_DIR.resolve(name + ".json");
            Files.createDirectories(target.getParent());
            Files.writeString(target, serialize(project), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            EcaLogger.error("Failed to save shader project {}: {}", name, e.getMessage());
            return false;
        }
    }

    public static boolean load(String name, ShaderCompositionProject target) {
        try {
            Path source = CONFIG_DIR.resolve(name + ".json");
            if (!Files.exists(source)) {
                return false;
            }
            String json = Files.readString(source);
            deserializeInto(json, target);
            return true;
        } catch (IOException e) {
            EcaLogger.error("Failed to load shader project {}: {}", name, e.getMessage());
            return false;
        }
    }

    public static boolean delete(String name) {
        try {
            Path target = CONFIG_DIR.resolve(name + ".json");
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            EcaLogger.error("Failed to delete shader project {}: {}", name, e.getMessage());
            return false;
        }
    }

    public static List<String> listSavedProjects() {
        List<String> names = new ArrayList<>();
        try {
            Files.createDirectories(CONFIG_DIR);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(CONFIG_DIR, "*.json")) {
                for (Path path : stream) {
                    String fileName = path.getFileName().toString();
                    names.add(fileName.substring(0, fileName.length() - ".json".length()));
                }
            }
        } catch (IOException e) {
            EcaLogger.error("Failed to list shader projects: {}", e.getMessage());
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    private ShaderProjectCodec() {}
}
