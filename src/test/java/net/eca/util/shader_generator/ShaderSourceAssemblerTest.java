package net.eca.util.shader_generator;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderSourceAssemblerTest {

    @Test
    void namespacesVisualFunctionsWhenManualSourceUsesEcaNames() {
        ShaderSourceWorkspace workspace = workspace("""
            #version 150
            uniform float GameTime;
            in vec2 texCoord0;
            out vec4 fragColor;
            float ecaHash(vec2 value) { return value.x; }
            vec4 renderEffect(vec2 uv, vec3 direction, float time) { return vec4(0.2); }
            void main() { fragColor = renderEffect(texCoord0, vec3(0.0), GameTime); }
            """);
        workspace.setVisualOverlayEnabled(true);
        ShaderProject visual = new ShaderProject(
            "test",
            "sample",
            """
                float ecaHash(vec2 value) { return value.y; }
                vec4 renderEffect(vec2 uv, vec3 direction, float time) {
                    return vec4(ecaHash(uv));
                }
                """,
            Set.of(),
            List.of(new ShaderProject.TextureBinding("EcaLayerImage0", "layer.png")),
            List.of()
        );

        ShaderExportBundle bundle = ShaderSourceAssembler.assemble(
            "test", "sample", workspace, visual
        );
        String fragment = bundle.file("assets/test/shaders/core/sample.fsh").content();
        String blockJson = bundle.file("assets/test/shaders/core/sample_block.json").content();

        assertEquals(1, occurrences(fragment, "float ecaHash("));
        assertTrue(fragment.contains("float ecaVisualOverlayHash("));
        assertTrue(fragment.contains("vec4 ecaVisualOverlayRenderEffect("));
        assertTrue(fragment.contains("void ecaManualMain()"));
        assertTrue(blockJson.contains("EcaVisualOverlay_EcaLayerImage0"));
    }

    @Test
    void recognizesUnmodifiedGeneratedWorkspace() {
        ShaderSourceWorkspace first = workspace("fragment");
        ShaderSourceWorkspace second = first.copy();

        assertTrue(ShaderSourceAssembler.matchesGeneratedSnapshot(first, second));
    }

    @Test
    void composesGeneratedEcaSourceWithItsVisualProjectWithoutDuplicateHelpers() {
        ShaderCompositionProject composition = new ShaderCompositionProject();
        composition.layers().get(0).setBaseColor(0.2F, 0.1F, 0.8F, 1.0F);
        ShaderProject visual = composition.toShaderProject("test", "generated");
        ShaderExportBundle generated = ShaderGenerator.standard().generate(
            new ShaderGenerator.Request(
                visual,
                ShaderExportMode.PORTABLE_WITH_ECA_HINTS,
                EnumSet.allOf(ShaderTargetProfile.class)
            )
        );
        ShaderSourceWorkspace workspace = ShaderSourceAssembler.fromGeneratedBundle(
            "test", "generated", generated
        );
        workspace.setVisualOverlayEnabled(true);

        ShaderExportBundle combined = ShaderSourceAssembler.assemble(
            "test", "generated", workspace, visual
        );
        String fragment = combined.file("assets/test/shaders/core/generated.fsh").content();

        assertEquals(1, occurrences(fragment, "float ecaHash("));
        assertEquals(1, occurrences(fragment, "float ecaVisualOverlayHash("));
        assertEquals(1, occurrences(fragment, "void main()"));
    }

    private static ShaderSourceWorkspace workspace(String fragment) {
        ShaderSourceWorkspace workspace = new ShaderSourceWorkspace();
        workspace.setSource(ShaderSourceFile.FRAGMENT, fragment);
        workspace.setSource(ShaderSourceFile.BLOCK_VERTEX, "block vertex");
        workspace.setSource(ShaderSourceFile.ENTITY_VERTEX, "entity vertex");
        String json = """
            {"samplers":[],"uniforms":[],"vertex":"old:v","fragment":"old:f"}
            """;
        workspace.setSource(ShaderSourceFile.BLOCK_JSON, json);
        workspace.setSource(ShaderSourceFile.ENTITY_JSON, json);
        return workspace;
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}
