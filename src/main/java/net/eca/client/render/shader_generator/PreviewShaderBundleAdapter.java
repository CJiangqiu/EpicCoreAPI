package net.eca.client.render.shader_generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.eca.util.shader_generator.ShaderExportBundle;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PreviewShaderBundleAdapter {

    private static final String MASK_SAMPLER_BASE = "EcaPreviewMask";

    static Result adapt(
        ShaderExportBundle bundle,
        GeneratedShaderPreview.Dependencies dependencies
    ) {
        String fragment = bundle.files().stream()
            .filter(file -> file.relativePath().endsWith(".fsh"))
            .map(ShaderExportBundle.File::content)
            .findFirst()
            .orElse("");
        for (GeneratedShaderPreview.ResolvedAtlasBinding atlas : dependencies.atlases()) {
            Pattern maskPattern = maskPattern(atlas.samplerName());
            if (!maskPattern.matcher(fragment).find()) continue;
            String maskSampler = availableSamplerName(fragment);
            List<ShaderExportBundle.File> files = new ArrayList<>();
            for (ShaderExportBundle.File file : bundle.files()) {
                String content = file.content();
                if (file.relativePath().endsWith(".fsh")) {
                    Matcher matcher = maskPattern.matcher(content);
                    if (matcher.find()) {
                        content = matcher.replaceAll("$1" + maskSampler + "$2");
                        content = insertAfterVersion(
                            content, "uniform sampler2D " + maskSampler + ";\n"
                        );
                    }
                } else if (file.relativePath().endsWith(".json")) {
                    content = addSampler(content, maskSampler);
                }
                files.add(new ShaderExportBundle.File(file.relativePath(), content));
            }
            return new Result(new ShaderExportBundle(files), maskSampler);
        }
        return new Result(bundle, null);
    }

    private static Pattern maskPattern(String samplerName) {
        return Pattern.compile(
            "(\\btexture(?:2D)?\\s*\\(\\s*)" + Pattern.quote(samplerName)
                + "(\\s*,\\s*texCoord0\\b)"
        );
    }

    private static String availableSamplerName(String source) {
        String candidate = MASK_SAMPLER_BASE;
        int suffix = 1;
        while (Pattern.compile("\\b" + Pattern.quote(candidate) + "\\b").matcher(source).find()) {
            candidate = MASK_SAMPLER_BASE + suffix++;
        }
        return candidate;
    }

    private static String insertAfterVersion(String source, String declaration) {
        int newline = source.indexOf('\n');
        if (source.stripLeading().startsWith("#version") && newline >= 0) {
            return source.substring(0, newline + 1) + declaration + source.substring(newline + 1);
        }
        return declaration + source;
    }

    private static String addSampler(String source, String samplerName) {
        JsonObject root = JsonParser.parseString(source).getAsJsonObject();
        JsonArray samplers = root.has("samplers") && root.get("samplers").isJsonArray()
            ? root.getAsJsonArray("samplers") : new JsonArray();
        root.add("samplers", samplers);
        for (var element : samplers) {
            if (element.isJsonObject()
                    && element.getAsJsonObject().has("name")
                    && samplerName.equals(element.getAsJsonObject().get("name").getAsString())) {
                return root.toString() + "\n";
            }
        }
        JsonObject sampler = new JsonObject();
        sampler.addProperty("name", samplerName);
        samplers.add(sampler);
        return root.toString() + "\n";
    }

    record Result(ShaderExportBundle bundle, String maskSamplerName) {

        boolean hasSeparateMask() {
            return maskSamplerName != null;
        }
    }

    private PreviewShaderBundleAdapter() {}
}
