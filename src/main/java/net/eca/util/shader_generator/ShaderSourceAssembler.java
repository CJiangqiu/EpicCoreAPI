package net.eca.util.shader_generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShaderSourceAssembler {

    private static final Pattern MAIN_PATTERN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
    private static final Pattern OUTPUT_PATTERN = Pattern.compile("\\bout\\s+vec4\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;");
    private static final Pattern UV_PATTERN = Pattern.compile("\\bin\\s+vec2\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;");
    private static final Pattern LEGACY_NAVIGATION = Pattern.compile(
        "(?m)^\\s*//\\s*layer\\s+\\d+\\s*:.*(?:\\R|$)"
    );
    private static final Pattern ECA_NAVIGATION = Pattern.compile(
        "(?m)^\\s*//\\s*@eca-nav\\s+.*(?:\\R|$)"
    );
    private static final String OVERLAY_SAMPLER_PREFIX = "EcaVisualOverlay_";
    private static final Map<String, String> OVERLAY_SYMBOL_SUFFIXES = overlaySymbolSuffixes();

    public static ShaderExportBundle assemble(
        String namespace,
        String path,
        ShaderSourceWorkspace workspace,
        ShaderProject visualProject
    ) {
        if (workspace == null || !workspace.isInitialized()) {
            throw new IllegalArgumentException("Shader source workspace is incomplete");
        }
        String fragment = workspace.source(ShaderSourceFile.FRAGMENT);
        String blockJson = normalizeJson(workspace.source(ShaderSourceFile.BLOCK_JSON),
            namespace, path + "_block", path, visualProject, workspace.visualOverlayEnabled());
        String entityJson = normalizeJson(workspace.source(ShaderSourceFile.ENTITY_JSON),
            namespace, path + "_entity", path, visualProject, workspace.visualOverlayEnabled());
        if (workspace.visualOverlayEnabled()) {
            fragment = addVisualOverlay(fragment, visualProject);
        }

        String core = "assets/" + namespace + "/shaders/core/";
        List<ShaderExportBundle.File> files = new ArrayList<>();
        files.add(new ShaderExportBundle.File(core + path + ".fsh", fragment));
        files.add(new ShaderExportBundle.File(core + path + "_block.vsh",
            workspace.source(ShaderSourceFile.BLOCK_VERTEX)));
        files.add(new ShaderExportBundle.File(core + path + "_block.json", blockJson));
        files.add(new ShaderExportBundle.File(core + path + "_entity.vsh",
            workspace.source(ShaderSourceFile.ENTITY_VERTEX)));
        files.add(new ShaderExportBundle.File(core + path + "_entity.json", entityJson));
        return new ShaderExportBundle(files);
    }

    public static ShaderSourceWorkspace fromGeneratedBundle(
        String namespace,
        String path,
        ShaderExportBundle bundle
    ) {
        ShaderSourceWorkspace workspace = new ShaderSourceWorkspace();
        String core = "assets/" + namespace + "/shaders/core/" + path;
        put(workspace, ShaderSourceFile.FRAGMENT, bundle.file(core + ".fsh"));
        put(workspace, ShaderSourceFile.BLOCK_VERTEX, bundle.file(core + "_block.vsh"));
        put(workspace, ShaderSourceFile.BLOCK_JSON, bundle.file(core + "_block.json"));
        put(workspace, ShaderSourceFile.ENTITY_VERTEX, bundle.file(core + "_entity.vsh"));
        put(workspace, ShaderSourceFile.ENTITY_JSON, bundle.file(core + "_entity.json"));
        return workspace;
    }

    public static boolean hasLegacyNavigation(ShaderSourceWorkspace workspace) {
        if (workspace == null || !workspace.isInitialized()) return false;
        String fragment = workspace.source(ShaderSourceFile.FRAGMENT);
        return LEGACY_NAVIGATION.matcher(fragment).find()
            && !ECA_NAVIGATION.matcher(fragment).find();
    }

    public static boolean hasGeneratedNavigation(ShaderSourceWorkspace workspace) {
        return workspace != null && workspace.isInitialized()
            && ECA_NAVIGATION.matcher(workspace.source(ShaderSourceFile.FRAGMENT)).find();
    }

    public static boolean matchesLegacyGeneratedSnapshot(
        ShaderSourceWorkspace workspace,
        ShaderSourceWorkspace generated
    ) {
        if (!hasLegacyNavigation(workspace) || generated == null || !generated.isInitialized()) {
            return false;
        }
        for (ShaderSourceFile file : ShaderSourceFile.values()) {
            String currentSource = LEGACY_NAVIGATION.matcher(workspace.source(file)).replaceAll("");
            String generatedSource = ECA_NAVIGATION.matcher(generated.source(file)).replaceAll("");
            if (!currentSource.equals(generatedSource)) return false;
        }
        return true;
    }

    public static boolean matchesGeneratedSnapshot(
        ShaderSourceWorkspace workspace,
        ShaderSourceWorkspace generated
    ) {
        if (workspace == null || generated == null
                || !workspace.isInitialized() || !generated.isInitialized()) return false;
        for (ShaderSourceFile file : ShaderSourceFile.values()) {
            if (!workspace.source(file).equals(generated.source(file))) return false;
        }
        return true;
    }

    public static String overlaySamplerName(String originalName) {
        return OVERLAY_SAMPLER_PREFIX + originalName;
    }

    private static void put(
        ShaderSourceWorkspace workspace,
        ShaderSourceFile slot,
        ShaderExportBundle.File file
    ) {
        if (file == null) throw new IllegalArgumentException("Generated shader bundle is incomplete");
        workspace.setSource(slot, file.content());
    }

    private static String addVisualOverlay(String source, ShaderProject visualProject) {
        if (visualProject == null) return source;
        Matcher main = MAIN_PATTERN.matcher(source);
        Matcher output = OUTPUT_PATTERN.matcher(source);
        Matcher uv = UV_PATTERN.matcher(source);
        if (!main.find() || !output.find() || !uv.find()) {
            throw new IllegalArgumentException(
                "Visual overlay requires a fragment shader with void main(), out vec4 and in vec2 declarations"
            );
        }
        String outputName = output.group(1);
        String uvName = uv.group(1);
        String symbolPrefix = uniqueOverlaySymbolPrefix(source);
        String importedMain = uniqueIdentifier(source, "ecaManualMain");
        String renamed = main.replaceFirst("void " + importedMain + "()");
        StringBuilder declarations = new StringBuilder();
        if (!source.matches("(?s).*\\buniform\\s+float\\s+GameTime\\s*;.*")) {
            declarations.append("uniform float GameTime;\n");
        }
        for (ShaderProject.TextureBinding texture : visualProject.textures()) {
            String sampler = overlaySamplerName(texture.samplerName());
            if (!source.matches("(?s).*\\buniform\\s+sampler2D\\s+"
                    + Pattern.quote(sampler) + "\\s*;.*")) {
                declarations.append("uniform sampler2D ").append(sampler).append(";\n");
            }
        }
        renamed = insertAfterVersion(renamed, declarations.toString());
        String overlayBody = namespaceOverlaySource(
            StandardShaderSourceAssembler.assembleOverlayBody(visualProject),
            visualProject,
            symbolPrefix
        );
        String overlayEntry = symbolPrefix
            + OVERLAY_SYMBOL_SUFFIXES.get("ecaApplyVisualOverlay");
        return renamed + "\n\n" + overlayBody + "\n\n"
            + "void main() {\n"
            + "    " + importedMain + "();\n"
            + "    vec2 ecaEffectUv = " + uvName + ";\n"
            + "    vec3 ecaEffectDirection = normalize(vec3(ecaEffectUv - vec2(0.5), 1.0));\n"
            + "    vec4 ecaOverlayColor = " + overlayEntry
            + "(ecaEffectUv, ecaEffectDirection);\n"
            + "    float ecaOverlayAlpha = clamp(ecaOverlayColor.a, 0.0, 1.0);\n"
            + "    float ecaBaseAlpha = clamp(" + outputName + ".a, 0.0, 1.0);\n"
            + "    float ecaCombinedAlpha = ecaOverlayAlpha"
            + " + ecaBaseAlpha * (1.0 - ecaOverlayAlpha);\n"
            + "    vec3 ecaCombinedRgb = ecaCombinedAlpha > 0.000001\n"
            + "        ? (ecaOverlayColor.rgb * ecaOverlayAlpha + " + outputName
            + ".rgb * ecaBaseAlpha * (1.0 - ecaOverlayAlpha)) / ecaCombinedAlpha\n"
            + "        : vec3(0.0);\n"
            + "    " + outputName + " = vec4(ecaCombinedRgb, ecaCombinedAlpha);\n"
            + "}\n";
    }

    private static String namespaceOverlaySource(
        String source,
        ShaderProject visualProject,
        String symbolPrefix
    ) {
        String namespaced = source;
        for (Map.Entry<String, String> entry : OVERLAY_SYMBOL_SUFFIXES.entrySet()) {
            namespaced = replaceIdentifier(
                namespaced, entry.getKey(), symbolPrefix + entry.getValue()
            );
        }
        for (ShaderProject.TextureBinding texture : visualProject.textures()) {
            namespaced = replaceIdentifier(
                namespaced,
                texture.samplerName(),
                overlaySamplerName(texture.samplerName())
            );
        }
        return namespaced;
    }

    private static String replaceIdentifier(String source, String identifier, String replacement) {
        return Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b")
            .matcher(source)
            .replaceAll(Matcher.quoteReplacement(replacement));
    }

    private static String uniqueOverlaySymbolPrefix(String source) {
        String candidate = "ecaVisualOverlay";
        int suffix = 2;
        while (containsOverlaySymbol(source, candidate)) {
            candidate = "ecaVisualOverlay" + suffix++;
        }
        return candidate;
    }

    private static boolean containsOverlaySymbol(String source, String prefix) {
        for (String suffix : OVERLAY_SYMBOL_SUFFIXES.values()) {
            if (Pattern.compile("\\b" + Pattern.quote(prefix + suffix) + "\\b")
                    .matcher(source).find()) return true;
        }
        return false;
    }

    private static String uniqueIdentifier(String source, String preferred) {
        String candidate = preferred;
        int suffix = 2;
        while (Pattern.compile("\\b" + Pattern.quote(candidate) + "\\b")
                .matcher(source).find()) {
            candidate = preferred + suffix++;
        }
        return candidate;
    }

    private static Map<String, String> overlaySymbolSuffixes() {
        Map<String, String> symbols = new LinkedHashMap<>();
        symbols.put("ecaOverlay", "Blend");
        symbols.put("ecaHash", "Hash");
        symbols.put("ecaNoise", "Noise");
        symbols.put("ecaFbm", "Fbm");
        symbols.put("ecaArcDistance", "ArcDistance");
        symbols.put("ecaRotate", "Rotate");
        symbols.put("ecaSegmentDistance", "SegmentDistance");
        symbols.put("ecaBoxDistance", "BoxDistance");
        symbols.put("ecaPolygonDistance", "PolygonDistance");
        symbols.put("ecaStarDistance", "StarDistance");
        symbols.put("ecaEffectProgress", "EffectProgress");
        symbols.put("ecaHueRotate", "HueRotate");
        symbols.put("ecaApplyVisualOverlay", "Apply");
        symbols.put("renderEffect", "RenderEffect");
        return Map.copyOf(symbols);
    }

    private static String insertAfterVersion(String source, String declarations) {
        if (declarations.isEmpty()) return source;
        int newline = source.indexOf('\n');
        if (source.stripLeading().startsWith("#version") && newline >= 0) {
            return source.substring(0, newline + 1) + declarations + source.substring(newline + 1);
        }
        return declarations + source;
    }

    private static String normalizeJson(
        String source,
        String namespace,
        String vertexPath,
        String fragmentPath,
        ShaderProject visualProject,
        boolean overlay
    ) {
        JsonObject root = JsonParser.parseString(source).getAsJsonObject();
        root.addProperty("vertex", namespace + ":" + vertexPath);
        root.addProperty("fragment", namespace + ":" + fragmentPath);
        if (overlay) {
            JsonArray uniforms = root.has("uniforms") ? root.getAsJsonArray("uniforms") : new JsonArray();
            root.add("uniforms", uniforms);
            ensureFloatUniform(uniforms, "GameTime", 0.0F);
            JsonArray samplers = root.has("samplers") ? root.getAsJsonArray("samplers") : new JsonArray();
            root.add("samplers", samplers);
            if (visualProject != null) {
                for (ShaderProject.TextureBinding texture : visualProject.textures()) {
                    ensureSampler(samplers, overlaySamplerName(texture.samplerName()));
                }
            }
        }
        return root.toString() + "\n";
    }

    private static void ensureFloatUniform(JsonArray uniforms, String name, float value) {
        for (var element : uniforms) {
            if (element.getAsJsonObject().get("name").getAsString().equals(name)) return;
        }
        JsonObject uniform = new JsonObject();
        uniform.addProperty("name", name);
        uniform.addProperty("type", "float");
        uniform.addProperty("count", 1);
        JsonArray values = new JsonArray();
        values.add(value);
        uniform.add("values", values);
        uniforms.add(uniform);
    }

    private static void ensureSampler(JsonArray samplers, String name) {
        for (var element : samplers) {
            if (element.getAsJsonObject().get("name").getAsString().equals(name)) return;
        }
        JsonObject sampler = new JsonObject();
        sampler.addProperty("name", name);
        samplers.add(sampler);
    }

    private ShaderSourceAssembler() {}
}
