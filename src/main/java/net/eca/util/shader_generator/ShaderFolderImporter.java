package net.eca.util.shader_generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.eca.util.EcaLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ShaderFolderImporter {

    private static final int MAX_SCAN_DEPTH = 16;
    private static final int MAX_FILES = 10000;
    private static final long MAX_SOURCE_BYTES = 4L * 1024L * 1024L;
    private static final Pattern IMPORT_PATTERN = Pattern.compile("#moj_import\\s+[<\\\"]([^>\\\"]+)[>\\\"]");
    private static final Pattern NUMBERED_RESOURCE_PATTERN = Pattern.compile(
        "\\\"([a-z0-9_./-]+_)\\\"\\s*\\+", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NUMBERED_FILE_PATTERN = Pattern.compile("(.+_)(\\d+)\\.png", Pattern.CASE_INSENSITIVE);

    public static List<Candidate> scan(Path selectedDirectory) throws IOException {
        Path selectedRoot = requireDirectory(selectedDirectory);
        Path root = discoverProjectRoot(selectedRoot);
        EcaLogger.info("[ShaderImport] scanning selected={} analysisRoot={}", selectedRoot, root);
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root, MAX_SCAN_DEPTH)) {
            files = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .limit(MAX_FILES + 1L)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .toList();
        }
        if (files.size() > MAX_FILES) {
            throw new IOException("Selected folder contains too many files");
        }

        List<Path> jsonFiles = files.stream()
            .filter(path -> hasExtension(path, ".json"))
            .sorted(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER))
            .toList();
        List<Candidate> candidates = new ArrayList<>();
        Set<Path> consumedJson = new HashSet<>();

        for (Path blockJson : jsonFiles) {
            String fileName = blockJson.getFileName().toString();
            if (!fileName.toLowerCase(Locale.ROOT).endsWith("_block.json")) continue;
            String baseName = fileName.substring(0, fileName.length() - "_block.json".length());
            Path entityJson = blockJson.resolveSibling(baseName + "_entity.json").normalize();
            if (!Files.isRegularFile(entityJson, LinkOption.NOFOLLOW_LINKS)
                    || !entityJson.startsWith(root)) continue;
            Candidate candidate = readFiveFileCandidate(root, files, baseName, blockJson, entityJson);
            if (candidate != null) {
                candidates.add(candidate);
                consumedJson.add(blockJson);
                consumedJson.add(entityJson);
            }
        }

        for (Path jsonFile : jsonFiles) {
            if (consumedJson.contains(jsonFile)) continue;
            Candidate candidate = readThreeFileCandidate(root, files, jsonFile);
            if (candidate != null) candidates.add(candidate);
        }
        EcaLogger.info("[ShaderImport] scan complete analysisRoot={} candidates={}", root, candidates.size());
        return List.copyOf(candidates);
    }

    private static Candidate readFiveFileCandidate(
        Path root,
        List<Path> files,
        String baseName,
        Path blockJson,
        Path entityJson
    ) {
        try {
            String blockJsonSource = readSource(blockJson);
            String entityJsonSource = readSource(entityJson);
            JsonObject blockRoot = JsonParser.parseString(blockJsonSource).getAsJsonObject();
            JsonObject entityRoot = JsonParser.parseString(entityJsonSource).getAsJsonObject();
            Path blockVertex = resolveProgram(root, files, blockJson, program(blockRoot, "vertex"), ".vsh");
            Path entityVertex = resolveProgram(root, files, entityJson, program(entityRoot, "vertex"), ".vsh");
            Path blockFragment = resolveProgram(root, files, blockJson, program(blockRoot, "fragment"), ".fsh");
            Path entityFragment = resolveProgram(root, files, entityJson, program(entityRoot, "fragment"), ".fsh");
            if (!blockFragment.equals(entityFragment)) return null;

            ShaderSourceWorkspace workspace = new ShaderSourceWorkspace();
            workspace.setSource(ShaderSourceFile.FRAGMENT, readSource(blockFragment));
            workspace.setSource(ShaderSourceFile.BLOCK_VERTEX, readSource(blockVertex));
            workspace.setSource(ShaderSourceFile.BLOCK_JSON, blockJsonSource);
            workspace.setSource(ShaderSourceFile.ENTITY_VERTEX, readSource(entityVertex));
            workspace.setSource(ShaderSourceFile.ENTITY_JSON, entityJsonSource);
            workspace.setVisualOverlayEnabled(false);
            DependencyPlan dependencyPlan = analyzeDependencies(
                root, files, baseName,
                List.of(blockVertex, entityVertex, blockFragment),
                List.of(blockRoot, entityRoot)
            );
            return new Candidate(
                displayName(root, blockJson.getParent(), baseName),
                inferModId(blockJson),
                sanitizeName(baseName),
                5,
                workspace,
                dependencyPlan
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Candidate readThreeFileCandidate(Path root, List<Path> files, Path jsonFile) {
        try {
            String jsonSource = readSource(jsonFile);
            JsonObject json = JsonParser.parseString(jsonSource).getAsJsonObject();
            Path vertex = resolveProgram(root, files, jsonFile, program(json, "vertex"), ".vsh");
            Path fragment = resolveProgram(root, files, jsonFile, program(json, "fragment"), ".fsh");

            ShaderSourceWorkspace workspace = new ShaderSourceWorkspace();
            workspace.setSource(ShaderSourceFile.FRAGMENT, readSource(fragment));
            workspace.setSource(ShaderSourceFile.BLOCK_VERTEX, readSource(vertex));
            workspace.setSource(ShaderSourceFile.BLOCK_JSON, jsonSource);
            workspace.setSource(ShaderSourceFile.ENTITY_VERTEX, readSource(vertex));
            workspace.setSource(ShaderSourceFile.ENTITY_JSON, jsonSource);
            workspace.setVisualOverlayEnabled(false);
            String fileName = jsonFile.getFileName().toString();
            String baseName = fileName.substring(0, fileName.length() - ".json".length());
            DependencyPlan dependencyPlan = analyzeDependencies(
                root, files, baseName, List.of(vertex, fragment), List.of(json)
            );
            return new Candidate(
                displayName(root, jsonFile.getParent(), baseName),
                inferModId(jsonFile),
                sanitizeName(baseName),
                3,
                workspace,
                dependencyPlan
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path resolveProgram(
        Path root,
        List<Path> files,
        Path jsonFile,
        String programId,
        String extension
    ) throws IOException {
        String programPath = programId.substring(programId.indexOf(':') + 1).replace('\\', '/');
        String fileName = programPath.substring(programPath.lastIndexOf('/') + 1) + extension;
        Path sibling = jsonFile.resolveSibling(fileName).normalize();
        if (sibling.startsWith(root)
                && Files.isRegularFile(sibling, LinkOption.NOFOLLOW_LINKS)) return sibling;

        String relativeSuffix = programPath + extension;
        List<Path> suffixMatches = files.stream()
            .filter(path -> hasExtension(path, extension))
            .filter(path -> normalizedRelative(root, path).endsWith(relativeSuffix))
            .toList();
        if (suffixMatches.size() == 1) return suffixMatches.get(0);

        List<Path> nameMatches = files.stream()
            .filter(path -> path.getFileName().toString().equalsIgnoreCase(fileName))
            .toList();
        if (nameMatches.size() == 1) return nameMatches.get(0);
        throw new IOException("Cannot resolve " + programId + extension + " from " + jsonFile);
    }

    private static String program(JsonObject root, String key) throws IOException {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) {
            throw new IOException("Shader JSON is missing " + key);
        }
        String value = root.get(key).getAsString().trim();
        if (value.isEmpty() || value.contains("..")) {
            throw new IOException("Invalid shader program path " + value);
        }
        return value;
    }

    private static String readSource(Path path) throws IOException {
        if (Files.size(path) > MAX_SOURCE_BYTES) {
            throw new IOException("Shader source is too large: " + path);
        }
        return Files.readString(path);
    }

    private static Path requireDirectory(Path selectedDirectory) throws IOException {
        if (selectedDirectory == null) throw new IOException("No shader folder selected");
        Path root = selectedDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Shader folder does not exist: " + root);
        return root;
    }

    private static boolean hasExtension(Path path, String extension) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension);
    }

    private static String normalizedRelative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String displayName(Path root, Path parent, String baseName) {
        String relative = normalizedRelative(root, parent);
        return relative.isEmpty() ? baseName : relative + "/" + baseName;
    }

    private static String sanitizeName(String value) {
        String sanitized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
        return sanitized.isBlank() ? "imported_shader" : sanitized;
    }

    private static String inferModId(Path jsonFile) {
        Path absolute = jsonFile.toAbsolutePath().normalize();
        for (int index = 0; index + 3 < absolute.getNameCount(); index++) {
            if (!absolute.getName(index).toString().equalsIgnoreCase("assets")
                    || !absolute.getName(index + 2).toString().equalsIgnoreCase("shaders")
                    || !absolute.getName(index + 3).toString().equalsIgnoreCase("core")) {
                continue;
            }
            String modId = absolute.getName(index + 1).toString();
            if (ShaderProjectCodec.isValidModId(modId)) return modId;
        }
        return "";
    }

    private static DependencyPlan analyzeDependencies(
        Path root,
        List<Path> files,
        String shaderName,
        List<Path> shaderSources,
        List<JsonObject> shaderJson
    ) {
        try {
            Map<String, ResourceSource> resources = new LinkedHashMap<>();
            collectIncludes(root, files, shaderSources, resources, new HashSet<>());

            List<String> samplers = samplerNames(shaderJson);
            List<MatrixUniform> matrices = matrixUniforms(shaderJson);
            List<AtlasSourceBinding> atlases = detectAtlases(
                root, files, shaderName, samplers, matrices, resources
            );
            List<String> warnings = new ArrayList<>();
            List<String> customSamplers = samplers.stream()
                .filter(name -> !"Sampler0".equals(name) && !"Sampler2".equals(name))
                .toList();
            if (atlases.isEmpty() && (!matrices.isEmpty() || !customSamplers.isEmpty())) {
                String uniforms = matrices.isEmpty() ? "none" : matrices.stream()
                    .map(matrix -> matrix.name() + "(" + matrix.spriteCount() + ")")
                    .collect(Collectors.joining(","));
                List<String> unresolvedSamplers = !matrices.isEmpty() && !samplers.isEmpty()
                    ? List.of(samplers.get(0)) : customSamplers;
                warnings.add("unresolved_sampler_bindings|samplers=" + String.join(",", unresolvedSamplers)
                    + "|uniforms=" + uniforms + "|scan_root=" + root);
            }
            return new DependencyPlan(List.copyOf(resources.values()), atlases, warnings);
        } catch (IOException | RuntimeException exception) {
            return new DependencyPlan(
                List.of(), List.of(),
                List.of("dependency_analysis_incomplete|reason=" + exception.getMessage()
                    + "|scan_root=" + root)
            );
        }
    }

    private static Path discoverProjectRoot(Path selectedRoot) {
        Path current = selectedRoot;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (Files.isDirectory(current.resolve("src/main/resources/assets"))) return current;
            current = current.getParent();
        }
        return selectedRoot;
    }

    private static void collectIncludes(
        Path root,
        List<Path> files,
        List<Path> sources,
        Map<String, ResourceSource> resources,
        Set<Path> visited
    ) throws IOException {
        for (Path source : sources) {
            if (!visited.add(source)) continue;
            Matcher matcher = IMPORT_PATTERN.matcher(readSource(source));
            while (matcher.find()) {
                String reference = matcher.group(1).replace('\\', '/');
                Path include = resolveInclude(root, files, reference);
                if (include == null) continue;
                ResourceSource resource = resourceSource(include);
                if (resource != null) resources.putIfAbsent(resource.resourceId(), resource);
                collectIncludes(root, files, List.of(include), resources, visited);
            }
        }
    }

    private static Path resolveInclude(Path root, List<Path> files, String reference) {
        String path = reference.substring(reference.indexOf(':') + 1);
        String suffix = path.startsWith("shaders/") ? path : "shaders/include/" + path;
        List<Path> matches = files.stream()
            .filter(file -> normalizedRelative(root, file).endsWith(suffix))
            .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static List<String> samplerNames(List<JsonObject> roots) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonObject root : roots) {
            if (!root.has("samplers") || !root.get("samplers").isJsonArray()) continue;
            for (var element : root.getAsJsonArray("samplers")) {
                if (element.isJsonObject() && element.getAsJsonObject().has("name")) {
                    names.add(element.getAsJsonObject().get("name").getAsString());
                }
            }
        }
        return List.copyOf(names);
    }

    private static List<MatrixUniform> matrixUniforms(List<JsonObject> roots) {
        Map<String, MatrixUniform> uniforms = new LinkedHashMap<>();
        for (JsonObject root : roots) {
            if (!root.has("uniforms") || !root.get("uniforms").isJsonArray()) continue;
            JsonArray array = root.getAsJsonArray("uniforms");
            for (var element : array) {
                if (!element.isJsonObject()) continue;
                JsonObject uniform = element.getAsJsonObject();
                if (!uniform.has("name") || !uniform.has("type") || !uniform.has("count")
                        || !"matrix2x2".equalsIgnoreCase(uniform.get("type").getAsString())) continue;
                int count = uniform.get("count").getAsInt();
                if (count >= 4 && count % 4 == 0) {
                    String name = uniform.get("name").getAsString();
                    uniforms.putIfAbsent(name, new MatrixUniform(name, count / 4));
                }
            }
        }
        return List.copyOf(uniforms.values());
    }

    private static List<AtlasSourceBinding> detectAtlases(
        Path root,
        List<Path> files,
        String shaderName,
        List<String> samplers,
        List<MatrixUniform> matrices,
        Map<String, ResourceSource> resources
    ) throws IOException {
        if (samplers.isEmpty() || matrices.isEmpty()) return List.of();
        String shaderNeedle = shaderName.toLowerCase(Locale.ROOT);
        Set<String> prefixes = new LinkedHashSet<>();
        for (Path file : files) {
            if (!hasExtension(file, ".java") || Files.size(file) > MAX_SOURCE_BYTES) continue;
            String source = Files.readString(file);
            String lower = source.toLowerCase(Locale.ROOT);
            boolean relevant = lower.contains(shaderNeedle)
                || matrices.stream().anyMatch(uniform -> lower.contains(uniform.name().toLowerCase(Locale.ROOT)));
            if (!relevant) continue;
            Matcher matcher = NUMBERED_RESOURCE_PATTERN.matcher(source);
            while (matcher.find()) {
                prefixes.add(matcher.group(1).replace('\\', '/').toLowerCase(Locale.ROOT));
            }
        }

        List<AtlasSourceBinding> bindings = new ArrayList<>();
        for (MatrixUniform matrix : matrices) {
            for (String prefix : prefixes) {
                List<NumberedResource> matches = numberedTextures(files, prefix);
                if (matches.size() < matrix.spriteCount()) continue;
                List<ResourceSource> sprites = matches.stream()
                    .limit(matrix.spriteCount())
                    .map(NumberedResource::resource)
                    .toList();
                for (ResourceSource sprite : sprites) {
                    resources.putIfAbsent(sprite.resourceId(), sprite);
                    addCompanionMetadata(sprite, resources);
                }
                bindings.add(new AtlasSourceBinding(samplers.get(0), matrix.name(), sprites));
                break;
            }
        }
        return List.copyOf(bindings);
    }

    private static void addCompanionMetadata(
        ResourceSource texture,
        Map<String, ResourceSource> resources
    ) {
        Path metadataPath = Path.of(texture.sourcePath().toString() + ".mcmeta").normalize();
        if (!Files.isRegularFile(metadataPath, LinkOption.NOFOLLOW_LINKS)) return;
        ResourceSource metadata = resourceSource(metadataPath);
        if (metadata != null) resources.putIfAbsent(metadata.resourceId(), metadata);
    }

    private static List<NumberedResource> numberedTextures(List<Path> files, String prefix) {
        List<NumberedResource> matches = new ArrayList<>();
        for (Path file : files) {
            if (!hasExtension(file, ".png")) continue;
            ResourceSource resource = resourceSource(file);
            if (resource == null) continue;
            String resourcePath = resource.resourceId().substring(resource.resourceId().indexOf(':') + 1);
            if (!resourcePath.startsWith("textures/") || !resourcePath.endsWith(".png")) continue;
            String textureId = resourcePath.substring("textures/".length(), resourcePath.length() - 4);
            Matcher matcher = NUMBERED_FILE_PATTERN.matcher(textureId + ".png");
            if (matcher.matches() && matcher.group(1).equalsIgnoreCase(prefix)) {
                matches.add(new NumberedResource(Integer.parseInt(matcher.group(2)), resource));
            }
        }
        matches.sort(Comparator.comparingInt(NumberedResource::index));
        return matches;
    }

    private static ResourceSource resourceSource(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        for (int index = 0; index + 2 < absolute.getNameCount(); index++) {
            if (!absolute.getName(index).toString().equalsIgnoreCase("assets")) continue;
            String namespace = absolute.getName(index + 1).toString();
            if (!ShaderProjectCodec.isValidModId(namespace)) return null;
            StringBuilder resourcePath = new StringBuilder();
            for (int part = index + 2; part < absolute.getNameCount(); part++) {
                if (!resourcePath.isEmpty()) resourcePath.append('/');
                resourcePath.append(absolute.getName(part));
            }
            return new ResourceSource(namespace + ":" + resourcePath, absolute);
        }
        return null;
    }

    public record Candidate(
        String displayName,
        String suggestedModId,
        String suggestedName,
        int fileCount,
        ShaderSourceWorkspace workspace,
        DependencyPlan dependencyPlan
    ) {
        public Candidate {
            if (displayName == null || displayName.isBlank() || suggestedModId == null
                    || !suggestedModId.isEmpty() && !ShaderProjectCodec.isValidModId(suggestedModId)
                    || suggestedName == null
                    || suggestedName.isBlank() || fileCount <= 0 || workspace == null
                    || !workspace.isInitialized()) {
                throw new IllegalArgumentException("Invalid shader folder candidate");
            }
            workspace = workspace.copy();
            dependencyPlan = dependencyPlan == null ? DependencyPlan.EMPTY : dependencyPlan;
        }

        @Override
        public ShaderSourceWorkspace workspace() {
            return workspace.copy();
        }
    }

    public record DependencyPlan(
        List<ResourceSource> resources,
        List<AtlasSourceBinding> atlases,
        List<String> warnings
    ) {
        private static final DependencyPlan EMPTY = new DependencyPlan(List.of(), List.of(), List.of());

        public DependencyPlan {
            resources = resources == null ? List.of() : List.copyOf(resources);
            atlases = atlases == null ? List.of() : List.copyOf(atlases);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record ResourceSource(String resourceId, Path sourcePath) {}

    public record AtlasSourceBinding(
        String samplerName,
        String uniformName,
        List<ResourceSource> sprites
    ) {
        public AtlasSourceBinding {
            sprites = List.copyOf(sprites);
        }
    }

    private record MatrixUniform(String name, int spriteCount) {}

    private record NumberedResource(int index, ResourceSource resource) {}

    private ShaderFolderImporter() {}
}
