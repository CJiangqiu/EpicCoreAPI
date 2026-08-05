package net.eca.client.render.shader_generator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.eca.util.shader_generator.ShaderExportBundle;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewShaderBundleAdapterTest {

    @Test
    void separatesModelMaskFromAtlasSamplingOnlyInPreviewBundle() {
        String fragment = """
            #version 150
            uniform sampler2D Sampler0;
            in vec2 texCoord0;
            void main() {
                vec4 mask = texture(Sampler0, texCoord0.xy);
                vec4 symbol = texture(Sampler0, vec2(0.25));
            }
            """;
        String json = """
            {"samplers":[{"name":"Sampler0"}]}
            """;
        ShaderExportBundle original = new ShaderExportBundle(List.of(
            new ShaderExportBundle.File("assets/example/shaders/core/demo.fsh", fragment),
            new ShaderExportBundle.File("assets/example/shaders/core/demo_block.json", json),
            new ShaderExportBundle.File("assets/example/shaders/core/demo_entity.json", json)
        ));
        GeneratedShaderPreview.Dependencies dependencies = new GeneratedShaderPreview.Dependencies(
            Map.of(),
            Set.of("Sampler0"),
            List.of(new GeneratedShaderPreview.ResolvedAtlasBinding(
                "Sampler0", "symbolUvs", List.of(Path.of("symbol_0.png"))
            ))
        );

        PreviewShaderBundleAdapter.Result result = PreviewShaderBundleAdapter.adapt(
            original, dependencies
        );

        assertTrue(result.hasSeparateMask());
        assertNotNull(result.maskSamplerName());
        String adaptedFragment = result.bundle()
            .file("assets/example/shaders/core/demo.fsh").content();
        assertTrue(adaptedFragment.contains(
            "texture(" + result.maskSamplerName() + ", texCoord0.xy)"
        ));
        assertTrue(adaptedFragment.contains("texture(Sampler0, vec2(0.25))"));
        assertEquals(fragment, original.file("assets/example/shaders/core/demo.fsh").content());
        JsonObject adaptedJson = JsonParser.parseString(result.bundle()
            .file("assets/example/shaders/core/demo_block.json").content()).getAsJsonObject();
        assertTrue(adaptedJson.getAsJsonArray("samplers").asList().stream()
            .anyMatch(element -> result.maskSamplerName().equals(
                element.getAsJsonObject().get("name").getAsString()
            )));
    }

    @Test
    void leavesShaderWithoutDirectModelMaskUntouched() {
        ShaderExportBundle original = new ShaderExportBundle(List.of(
            new ShaderExportBundle.File(
                "assets/example/shaders/core/demo.fsh",
                "#version 150\nvoid main(){ texture(Sampler0, vec2(0.5)); }\n"
            )
        ));
        GeneratedShaderPreview.Dependencies dependencies = new GeneratedShaderPreview.Dependencies(
            Map.of(),
            Set.of("Sampler0"),
            List.of(new GeneratedShaderPreview.ResolvedAtlasBinding(
                "Sampler0", "symbolUvs", List.of(Path.of("symbol_0.png"))
            ))
        );

        PreviewShaderBundleAdapter.Result result = PreviewShaderBundleAdapter.adapt(
            original, dependencies
        );

        assertFalse(result.hasSeparateMask());
        assertEquals(original, result.bundle());
    }
}
