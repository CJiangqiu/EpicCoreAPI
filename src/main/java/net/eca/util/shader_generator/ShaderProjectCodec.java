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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ShaderProjectCodec {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = Paths.get("config", "eca", "shadergenerator");
    private static final Pattern MOD_ID_PATTERN = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern SHADER_NAME_PATTERN = Pattern.compile("[a-z0-9_.-]+");

    public static String serialize(String modId, String shaderName, ShaderCompositionProject project) {
        JsonObject root = new JsonObject();
        root.addProperty("mod_id", modId);
        root.addProperty("shader_name", shaderName);
        root.addProperty("export_mode", project.exportMode().name().toLowerCase());

        JsonArray layersArray = new JsonArray();
        for (ShaderLayer layer : project.layers()) {
            JsonObject layerObj = new JsonObject();
            layerObj.addProperty("name", layer.name());
            layerObj.addProperty("visible", layer.visible());
            JsonArray baseColor = new JsonArray();
            baseColor.add(layer.baseRed());
            baseColor.add(layer.baseGreen());
            baseColor.add(layer.baseBlue());
            baseColor.add(layer.baseAlpha());
            layerObj.add("base_color", baseColor);
            layerObj.addProperty("blend_mode", layer.blendMode().name().toLowerCase());
            if (layer.backgroundImagePath() != null) {
                layerObj.addProperty("background_image", layer.backgroundImagePath());
            }

            JsonArray elementsArray = new JsonArray();
            for (ShaderModuleInstance element : layer.elements()) {
                JsonObject elementObj = new JsonObject();
                elementObj.addProperty("definition", element.definition().id());
                elementObj.addProperty("enabled", element.enabled());
                if (element.imagePath() != null) {
                    elementObj.addProperty("image", element.imagePath());
                }

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

        JsonArray outputEffectsArray = new JsonArray();
        for (ShaderOutputEffectInstance effect : project.outputEffects()) {
            JsonObject effectObj = new JsonObject();
            effectObj.addProperty("definition", effect.definition().id());
            effectObj.addProperty("enabled", effect.enabled());
            JsonObject valuesObj = new JsonObject();
            for (var entry : effect.values().entrySet()) {
                valuesObj.addProperty(entry.getKey(), entry.getValue());
            }
            effectObj.add("values", valuesObj);
            outputEffectsArray.add(effectObj);
        }
        root.add("output_effects", outputEffectsArray);
        return GSON.toJson(root) + "\n";
    }

    public static void deserializeInto(String json, ShaderCompositionProject target) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        ShaderCompositionProject loaded = new ShaderCompositionProject();
        loaded.getLayersInternal().clear();
        String modeName = root.get("export_mode").getAsString().toUpperCase();
        loaded.setExportMode(ShaderExportMode.valueOf(modeName));

        JsonArray layersArray = root.getAsJsonArray("layers");
        for (int li = 0; li < layersArray.size(); li++) {
            JsonObject layerObj = layersArray.get(li).getAsJsonObject();
            ShaderLayer layer = new ShaderLayer(
                layerObj.get("name").getAsString(),
                layerObj.get("visible").getAsBoolean()
            );
            if (layerObj.has("base_color")) {
                JsonArray baseColor = layerObj.getAsJsonArray("base_color");
                if (baseColor.size() >= 4) {
                    layer.setBaseColor(
                        baseColor.get(0).getAsFloat(),
                        baseColor.get(1).getAsFloat(),
                        baseColor.get(2).getAsFloat(),
                        baseColor.get(3).getAsFloat()
                    );
                }
            }
            if (layerObj.has("blend_mode")) {
                layer.setBlendMode(ShaderBlendMode.fromName(layerObj.get("blend_mode").getAsString()));
            }
            if (layerObj.has("background_image")) {
                layer.setBackgroundImagePath(layerObj.get("background_image").getAsString());
            }
            loaded.getLayersInternal().add(layer);

            JsonArray elementsArray = layerObj.getAsJsonArray("elements");
            for (int ei = 0; ei < elementsArray.size(); ei++) {
                JsonObject elementObj = elementsArray.get(ei).getAsJsonObject();
                ShaderModuleDefinition definition = ShaderModuleRegistry.get(
                    elementObj.get("definition").getAsString()
                );
                if (definition == null) {
                    EcaLogger.warn("Unknown shader module definition: {} — skipping",
                        elementObj.get("definition").getAsString());
                    continue;
                }
                ShaderModuleInstance element = definition.createInstance();
                element.setEnabled(elementObj.get("enabled").getAsBoolean());
                if (elementObj.has("image")) {
                    element.setImagePath(elementObj.get("image").getAsString());
                }

                JsonObject valuesObj = elementObj.getAsJsonObject("values");
                for (var entry : valuesObj.entrySet()) {
                    try {
                        element.setValue(entry.getKey(), entry.getValue().getAsFloat());
                    } catch (IllegalArgumentException ignored) {
                        // 新版本已移除的参数不应阻止旧工程打开。
                    }
                }
                layer.getElementsInternal().add(element);
            }
        }
        if (root.has("output_effects")) {
            JsonArray outputEffectsArray = root.getAsJsonArray("output_effects");
            for (int index = 0; index < outputEffectsArray.size(); index++) {
                JsonObject effectObj = outputEffectsArray.get(index).getAsJsonObject();
                ShaderOutputEffectDefinition definition = ShaderOutputEffectRegistry.get(
                    effectObj.get("definition").getAsString()
                );
                if (definition == null) {
                    EcaLogger.warn("Unknown shader output effect definition: {} - skipping",
                        effectObj.get("definition").getAsString());
                    continue;
                }
                ShaderOutputEffectInstance effect = definition.createInstance();
                if (effectObj.has("enabled")) {
                    effect.setEnabled(effectObj.get("enabled").getAsBoolean());
                }
                JsonObject valuesObj = effectObj.getAsJsonObject("values");
                for (var entry : valuesObj.entrySet()) {
                    try {
                        effect.setValue(entry.getKey(), entry.getValue().getAsFloat());
                    } catch (IllegalArgumentException ignored) {
                        // Removed parameters must not prevent older projects from loading.
                    }
                }
                loaded.getOutputEffectsInternal().add(effect);
            }
        }
        if (loaded.layers().isEmpty()) {
            loaded.getLayersInternal().add(ShaderLayer.createDefault());
        }
        target.copyStateFrom(loaded);
    }

    public static boolean save(
        String modId,
        String shaderName,
        ShaderCompositionProject project
    ) {
        if (!isValidModId(modId) || !isValidShaderName(shaderName) || project == null) {
            return false;
        }
        Path projectDirectory = projectDirectory(modId, shaderName);
        try {
            Files.createDirectories(projectDirectory);
            Files.writeString(
                projectDirectory.resolve("project.json"),
                serialize(modId, shaderName, project),
                StandardCharsets.UTF_8
            );
            writeGeneratedShaders(projectDirectory, modId, shaderName, project);
            return true;
        } catch (IOException | RuntimeException exception) {
            EcaLogger.error("Failed to save shader project {}:{}: {}",
                modId, shaderName, exception.getMessage());
            return false;
        }
    }

    public static boolean load(ProjectRef reference, ShaderCompositionProject target) {
        if (reference == null || target == null) {
            return false;
        }
        try {
            Path source = projectDirectory(reference.modId(), reference.shaderName())
                .resolve("project.json");
            if (!Files.isRegularFile(source)) {
                return false;
            }
            deserializeInto(Files.readString(source, StandardCharsets.UTF_8), target);
            return true;
        } catch (IOException | RuntimeException exception) {
            EcaLogger.error("Failed to load shader project {}: {}", reference.id(), exception.getMessage());
            return false;
        }
    }

    public static boolean rename(
        ProjectRef source,
        String targetModId,
        String targetShaderName,
        ShaderCompositionProject project
    ) {
        if (source == null || !isValidModId(targetModId) || !isValidShaderName(targetShaderName)) {
            return false;
        }
        ProjectRef target = new ProjectRef(targetModId, targetShaderName);
        if (!source.equals(target) && exists(target)) {
            return false;
        }
        if (!save(targetModId, targetShaderName, project)) {
            return false;
        }
        if (source.equals(target)) {
            return true;
        }
        try {
            deleteDirectory(projectDirectory(source.modId(), source.shaderName()));
            return true;
        } catch (IOException exception) {
            EcaLogger.error("Failed to remove renamed shader project {}: {}",
                source.id(), exception.getMessage());
            return false;
        }
    }

    public static boolean exists(ProjectRef reference) {
        return reference != null && Files.isRegularFile(
            projectDirectory(reference.modId(), reference.shaderName()).resolve("project.json")
        );
    }

    public static String importTexture(ProjectRef reference, Path source) {
        if (reference == null || source == null || !Files.isRegularFile(source)) {
            return null;
        }
        String fileName = source.getFileName().toString().toLowerCase();
        if (!fileName.endsWith(".png")) {
            return null;
        }
        String safeName = fileName.replaceAll("[^a-z0-9_.-]", "_");
        Path textureDirectory = projectDirectory(reference.modId(), reference.shaderName())
            .resolve("textures")
            .resolve("shader_generator");
        try {
            Files.createDirectories(textureDirectory);
            Path target = uniqueTarget(textureDirectory, safeName);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return "textures/shader_generator/" + target.getFileName();
        } catch (IOException exception) {
            EcaLogger.error("Failed to import shader texture {}: {}", source, exception.getMessage());
            return null;
        }
    }

    public static Path resolveProjectAsset(ProjectRef reference, String projectPath) {
        if (reference == null || projectPath == null || projectPath.isBlank()) {
            return null;
        }
        Path root = projectDirectory(reference.modId(), reference.shaderName()).toAbsolutePath().normalize();
        Path resolved = root.resolve(projectPath).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    public static List<ProjectRef> listSavedProjects() {
        List<ProjectRef> projects = new ArrayList<>();
        try {
            Files.createDirectories(CONFIG_DIR);
            try (DirectoryStream<Path> modDirectories = Files.newDirectoryStream(CONFIG_DIR, Files::isDirectory)) {
                for (Path modDirectory : modDirectories) {
                    String modId = modDirectory.getFileName().toString();
                    if (!isValidModId(modId)) {
                        continue;
                    }
                    try (DirectoryStream<Path> shaderDirectories =
                             Files.newDirectoryStream(modDirectory, Files::isDirectory)) {
                        for (Path shaderDirectory : shaderDirectories) {
                            String shaderName = shaderDirectory.getFileName().toString();
                            if (isValidShaderName(shaderName)
                                && Files.isRegularFile(shaderDirectory.resolve("project.json"))) {
                                projects.add(new ProjectRef(modId, shaderName));
                            }
                        }
                    }
                }
            }
        } catch (IOException exception) {
            EcaLogger.error("Failed to list shader projects: {}", exception.getMessage());
        }
        projects.sort(Comparator.comparing(ProjectRef::modId, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ProjectRef::shaderName, String.CASE_INSENSITIVE_ORDER));
        return projects;
    }

    public static boolean isValidModId(String value) {
        return value != null && MOD_ID_PATTERN.matcher(value).matches();
    }

    public static boolean isValidShaderName(String value) {
        return value != null && SHADER_NAME_PATTERN.matcher(value).matches();
    }

    private static void writeGeneratedShaders(
        Path projectDirectory,
        String modId,
        String shaderName,
        ShaderCompositionProject project
    ) throws IOException {
        ShaderProject shaderProject = project.toShaderProject(modId, shaderName);
        ShaderExportBundle bundle = ShaderGenerator.standard().generate(new ShaderGenerator.Request(
            shaderProject,
            project.exportMode(),
            EnumSet.allOf(ShaderTargetProfile.class)
        ));
        String assetPrefix = "assets/" + modId + "/";
        for (ShaderExportBundle.File file : bundle.files()) {
            if (!file.relativePath().startsWith(assetPrefix)) {
                continue;
            }
            Path target = projectDirectory.resolve(file.relativePath().substring(assetPrefix.length()));
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content(), StandardCharsets.UTF_8);
        }
    }

    private static Path projectDirectory(String modId, String shaderName) {
        return CONFIG_DIR.resolve(modId).resolve(shaderName);
    }

    private static Path uniqueTarget(Path directory, String fileName) {
        Path target = directory.resolve(fileName);
        if (!Files.exists(target)) {
            return target;
        }
        int extensionIndex = fileName.lastIndexOf('.');
        String base = extensionIndex < 0 ? fileName : fileName.substring(0, extensionIndex);
        String extension = extensionIndex < 0 ? "" : fileName.substring(extensionIndex);
        int suffix = 2;
        while (Files.exists(target)) {
            target = directory.resolve(base + "_" + suffix + extension);
            suffix++;
        }
        return target;
    }

    private static void deleteDirectory(Path directory) throws IOException {
        Path normalizedRoot = CONFIG_DIR.toAbsolutePath().normalize();
        Path normalizedTarget = directory.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot) || normalizedTarget.equals(normalizedRoot)) {
            throw new IOException("Refusing to delete outside shader generator directory");
        }
        if (!Files.exists(normalizedTarget)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(normalizedTarget)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record ProjectRef(String modId, String shaderName) {

        public ProjectRef {
            if (!isValidModId(modId) || !isValidShaderName(shaderName)) {
                throw new IllegalArgumentException("Invalid shader project id");
            }
        }

        public String id() {
            return modId + ":" + shaderName;
        }
    }

    private ShaderProjectCodec() {}
}
