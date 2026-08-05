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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        ShaderSourceWorkspace workspace = project.sourceWorkspace();
        if (workspace.isInitialized()) {
            JsonObject sourceWorkspace = new JsonObject();
            sourceWorkspace.addProperty("source_active", project.sourceActive());
            sourceWorkspace.addProperty("visual_overlay", workspace.visualOverlayEnabled());
            JsonObject files = new JsonObject();
            for (ShaderSourceFile file : ShaderSourceFile.values()) {
                files.addProperty(file.serializedName(), workspace.source(file));
            }
            sourceWorkspace.add("files", files);
            ShaderPreviewBindings bindings = workspace.previewBindings();
            if (!bindings.resources().isEmpty() || !bindings.atlases().isEmpty()
                    || !bindings.warnings().isEmpty()) {
                JsonObject previewBindings = new JsonObject();
                JsonObject resources = new JsonObject();
                bindings.resources().forEach(resources::addProperty);
                previewBindings.add("resources", resources);
                JsonArray atlases = new JsonArray();
                for (ShaderPreviewBindings.AtlasBinding binding : bindings.atlases()) {
                    JsonObject atlas = new JsonObject();
                    atlas.addProperty("sampler", binding.samplerName());
                    atlas.addProperty("uniform", binding.uniformName());
                    JsonArray sprites = new JsonArray();
                    binding.spritePaths().forEach(sprites::add);
                    atlas.add("sprites", sprites);
                    atlases.add(atlas);
                }
                previewBindings.add("atlases", atlases);
                JsonArray warnings = new JsonArray();
                bindings.warnings().forEach(warnings::add);
                previewBindings.add("warnings", warnings);
                sourceWorkspace.add("preview_bindings", previewBindings);
            }
            root.add("source_workspace", sourceWorkspace);
        }
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
        if (root.has("source_workspace")) {
            JsonObject sourceWorkspace = root.getAsJsonObject("source_workspace");
            loaded.setSourceActive(
                sourceWorkspace.has("source_active")
                    && sourceWorkspace.get("source_active").getAsBoolean()
            );
            loaded.sourceWorkspace().setVisualOverlayEnabled(
                sourceWorkspace.has("visual_overlay") && sourceWorkspace.get("visual_overlay").getAsBoolean()
            );
            if (sourceWorkspace.has("files")) {
                JsonObject files = sourceWorkspace.getAsJsonObject("files");
                for (var entry : files.entrySet()) {
                    ShaderSourceFile file = ShaderSourceFile.fromSerializedName(entry.getKey());
                    if (file != null) {
                        loaded.sourceWorkspace().setSource(file, entry.getValue().getAsString());
                    }
                }
            }
            if (sourceWorkspace.has("preview_bindings")) {
                readPreviewBindings(
                    sourceWorkspace.getAsJsonObject("preview_bindings"),
                    loaded.sourceWorkspace().previewBindings()
                );
            }
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
            copyDependencies(source, target);
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
            Path target = copyTextureAsset(source, textureDirectory, safeName);
            return "textures/shader_generator/" + target.getFileName();
        } catch (IOException exception) {
            EcaLogger.error("Failed to import shader texture {}: {}", source, exception.getMessage());
            return null;
        }
    }

    static Path copyTextureAsset(Path source, Path textureDirectory, String safeName)
        throws IOException {
        Path target = null;
        Path metadataTarget = null;
        try {
            Files.createDirectories(textureDirectory);
            target = uniqueTarget(textureDirectory, safeName);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            Path metadataSource = Path.of(source.toString() + ".mcmeta").toAbsolutePath().normalize();
            if (Files.isRegularFile(metadataSource)) {
                metadataTarget = Path.of(target.toString() + ".mcmeta").normalize();
                Files.copy(metadataSource, metadataTarget, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException exception) {
            deleteImportedTexture(target, metadataTarget);
            throw exception;
        }
    }

    public static boolean importDependencies(
        ProjectRef reference,
        ShaderFolderImporter.DependencyPlan plan,
        ShaderSourceWorkspace workspace
    ) {
        if (reference == null || plan == null || workspace == null) return false;
        Path projectRoot = projectDirectory(reference.modId(), reference.shaderName());
        Path dependencyRoot = projectRoot.resolve("dependencies").toAbsolutePath().normalize();
        try {
            Map<String, String> installed = new LinkedHashMap<>();
            for (ShaderFolderImporter.ResourceSource resource : plan.resources()) {
                ResourceLocationParts parts = parseResourceId(resource.resourceId());
                if (parts == null || !Files.isRegularFile(resource.sourcePath())) continue;
                Path target = dependencyRoot.resolve(parts.namespace()).resolve(parts.path()).normalize();
                if (!target.startsWith(dependencyRoot)) {
                    throw new IOException("Dependency path escapes the shader project");
                }
                Files.createDirectories(target.getParent());
                Files.copy(resource.sourcePath(), target, StandardCopyOption.REPLACE_EXISTING);
                String projectPath = projectRoot.toAbsolutePath().normalize().relativize(target)
                    .toString().replace('\\', '/');
                workspace.previewBindings().putResource(resource.resourceId(), projectPath);
                installed.put(resource.resourceId(), projectPath);
            }
            for (ShaderFolderImporter.AtlasSourceBinding atlas : plan.atlases()) {
                List<String> spritePaths = atlas.sprites().stream()
                    .map(sprite -> installed.get(sprite.resourceId()))
                    .filter(Objects::nonNull)
                    .toList();
                if (spritePaths.size() == atlas.sprites().size()) {
                    workspace.previewBindings().addAtlas(new ShaderPreviewBindings.AtlasBinding(
                        atlas.samplerName(), atlas.uniformName(), spritePaths
                    ));
                }
            }
            plan.warnings().forEach(workspace.previewBindings()::addWarning);
            EcaLogger.info(
                "[ShaderImport] dependencies installed project={} resources={} atlases={} warnings={}",
                reference.id(), installed.size(), workspace.previewBindings().atlases().size(),
                workspace.previewBindings().warnings().size()
            );
            for (String warning : workspace.previewBindings().warnings()) {
                EcaLogger.warn("[ShaderImport] unresolved preview dependency project={} detail={}",
                    reference.id(), warning);
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            EcaLogger.error("Failed to import shader dependencies for {}: {}",
                reference.id(), exception.getMessage());
            return false;
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

    public static boolean delete(ProjectRef reference) {
        if (reference == null) return false;
        Path directory = projectDirectory(reference.modId(), reference.shaderName());
        try {
            if (!Files.isDirectory(directory)) return false;
            deleteDirectory(directory);
            EcaLogger.info("[ShaderProject] deleted project={} directory={}",
                reference.id(), directory.toAbsolutePath().normalize());
            return true;
        } catch (IOException exception) {
            EcaLogger.error("[ShaderProject] failed to delete project={} directory={} reason={}",
                reference.id(), directory.toAbsolutePath().normalize(), exception.getMessage());
            return false;
        }
    }

    public static Path projectPath(ProjectRef reference) {
        return reference == null ? null
            : projectDirectory(reference.modId(), reference.shaderName()).toAbsolutePath().normalize();
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
        boolean useSource = project.sourceWorkspace().isInitialized()
            && (project.sourceActive() || project.sourceWorkspace().visualOverlayEnabled());
        ShaderExportBundle bundle = useSource
            ? ShaderSourceAssembler.assemble(modId, shaderName, project.sourceWorkspace(), shaderProject)
            : ShaderGenerator.standard().generate(new ShaderGenerator.Request(
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

    private static void copyDependencies(ProjectRef source, ProjectRef target) throws IOException {
        copyProjectDirectory(source, target, "dependencies");
        copyProjectDirectory(source, target, "textures");
    }

    private static void copyProjectDirectory(
        ProjectRef source,
        ProjectRef target,
        String childDirectory
    ) throws IOException {
        Path sourceDirectory = projectDirectory(source.modId(), source.shaderName())
            .resolve(childDirectory);
        if (!Files.isDirectory(sourceDirectory)) return;
        Path targetDirectory = projectDirectory(target.modId(), target.shaderName())
            .resolve(childDirectory);
        try (Stream<Path> paths = Files.walk(sourceDirectory)) {
            for (Path path : paths.toList()) {
                Path destination = targetDirectory.resolve(sourceDirectory.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteImportedTexture(Path texture, Path metadata) {
        try {
            if (metadata != null) Files.deleteIfExists(metadata);
            if (texture != null) Files.deleteIfExists(texture);
        } catch (IOException cleanupException) {
            EcaLogger.warn("Failed to clean incomplete shader texture import: {}",
                cleanupException.getMessage());
        }
    }

    private static void readPreviewBindings(JsonObject source, ShaderPreviewBindings target) {
        target.clear();
        if (source.has("resources")) {
            for (var entry : source.getAsJsonObject("resources").entrySet()) {
                target.putResource(entry.getKey(), entry.getValue().getAsString());
            }
        }
        if (source.has("atlases")) {
            for (var element : source.getAsJsonArray("atlases")) {
                JsonObject atlas = element.getAsJsonObject();
                List<String> sprites = new ArrayList<>();
                for (var sprite : atlas.getAsJsonArray("sprites")) sprites.add(sprite.getAsString());
                target.addAtlas(new ShaderPreviewBindings.AtlasBinding(
                    atlas.get("sampler").getAsString(),
                    atlas.get("uniform").getAsString(),
                    sprites
                ));
            }
        }
        if (source.has("warnings")) {
            for (var warning : source.getAsJsonArray("warnings")) target.addWarning(warning.getAsString());
        }
    }

    private static ResourceLocationParts parseResourceId(String resourceId) {
        if (resourceId == null) return null;
        int separator = resourceId.indexOf(':');
        if (separator <= 0 || separator == resourceId.length() - 1) return null;
        String namespace = resourceId.substring(0, separator);
        String path = resourceId.substring(separator + 1);
        if (!isValidModId(namespace) || path.contains("..") || path.startsWith("/")) return null;
        return new ResourceLocationParts(namespace, path);
    }

    private record ResourceLocationParts(String namespace, String path) {}

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
