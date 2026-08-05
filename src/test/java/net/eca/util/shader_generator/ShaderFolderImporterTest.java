package net.eca.util.shader_generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderFolderImporterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void importsStandardThreeFileShaderIntoBothProfiles() throws IOException {
        write("cosmic.json", json("cosmic", "cosmic"));
        write("cosmic.vsh", "three-file vertex");
        write("cosmic.fsh", "three-file fragment");

        List<ShaderFolderImporter.Candidate> candidates = ShaderFolderImporter.scan(temporaryDirectory);

        assertEquals(1, candidates.size());
        ShaderFolderImporter.Candidate candidate = candidates.get(0);
        assertEquals(3, candidate.fileCount());
        assertEquals("", candidate.suggestedModId());
        assertEquals("cosmic", candidate.suggestedName());
        ShaderSourceWorkspace workspace = candidate.workspace();
        assertEquals("three-file vertex", workspace.source(ShaderSourceFile.BLOCK_VERTEX));
        assertEquals("three-file vertex", workspace.source(ShaderSourceFile.ENTITY_VERTEX));
        assertEquals("three-file fragment", workspace.source(ShaderSourceFile.FRAGMENT));
        assertFalse(workspace.visualOverlayEnabled());
    }

    @Test
    void infersModIdFromForgeAssetPath() throws IOException {
        write("workspace/src/main/resources/assets/example_mod/shaders/core/cosmic.json",
            json("cosmic", "cosmic"));
        write("workspace/src/main/resources/assets/example_mod/shaders/core/cosmic.vsh", "vertex");
        write("workspace/src/main/resources/assets/example_mod/shaders/core/cosmic.fsh", "fragment");

        List<ShaderFolderImporter.Candidate> candidates = ShaderFolderImporter.scan(temporaryDirectory);

        assertEquals(1, candidates.size());
        assertEquals("example_mod", candidates.get(0).suggestedModId());
    }

    @Test
    void groupsEcaFiveFilesAsOneCandidate() throws IOException {
        write("nebula_block.json", json("nebula_block", "nebula"));
        write("nebula_entity.json", json("nebula_entity", "nebula"));
        write("nebula_block.vsh", "block vertex");
        write("nebula_entity.vsh", "entity vertex");
        write("nebula.fsh", "shared fragment");

        List<ShaderFolderImporter.Candidate> candidates = ShaderFolderImporter.scan(temporaryDirectory);

        assertEquals(1, candidates.size());
        ShaderFolderImporter.Candidate candidate = candidates.get(0);
        assertEquals(5, candidate.fileCount());
        ShaderSourceWorkspace workspace = candidate.workspace();
        assertEquals("block vertex", workspace.source(ShaderSourceFile.BLOCK_VERTEX));
        assertEquals("entity vertex", workspace.source(ShaderSourceFile.ENTITY_VERTEX));
        assertEquals("shared fragment", workspace.source(ShaderSourceFile.FRAGMENT));
    }

    @Test
    void returnsEveryCompleteShaderInTheSelectedFolder() throws IOException {
        write("first.json", json("first", "first"));
        write("first.vsh", "first vertex");
        write("first.fsh", "first fragment");
        write("nested/second.json", json("second", "second"));
        write("nested/second.vsh", "second vertex");
        write("nested/second.fsh", "second fragment");
        write("unrelated.json", "{\"name\":\"not a shader\"}");

        List<ShaderFolderImporter.Candidate> candidates = ShaderFolderImporter.scan(temporaryDirectory);

        assertEquals(List.of("first", "nested/second"),
            candidates.stream().map(ShaderFolderImporter.Candidate::displayName).toList());
    }

    @Test
    void discoversNumberedAtlasFromGenericSourceReferences() throws IOException {
        write("src/main/resources/assets/example/shaders/core/nebula.json", """
            {"vertex":"example:nebula","fragment":"example:nebula",
             "samplers":[{"name":"Sampler0"}],
             "uniforms":[{"name":"symbolUvs","type":"matrix2x2","count":8,"values":[0]}]}
            """);
        write("src/main/resources/assets/example/shaders/core/nebula.vsh", "vertex");
        write("src/main/resources/assets/example/shaders/core/nebula.fsh", "fragment");
        write("src/main/java/example/PreviewSetup.java",
            "String path = \"effects/nebula/symbol_\" + index;");
        write("src/main/resources/assets/example/textures/effects/nebula/symbol_0.png", "png");
        write("src/main/resources/assets/example/textures/effects/nebula/symbol_0.png.mcmeta", """
            {"animation":{"frametime":2}}
            """);
        write("src/main/resources/assets/example/textures/effects/nebula/symbol_1.png", "png");

        ShaderFolderImporter.Candidate candidate = ShaderFolderImporter.scan(temporaryDirectory).get(0);

        assertEquals(1, candidate.dependencyPlan().atlases().size());
        assertEquals("Sampler0", candidate.dependencyPlan().atlases().get(0).samplerName());
        assertEquals("symbolUvs", candidate.dependencyPlan().atlases().get(0).uniformName());
        assertEquals(3, candidate.dependencyPlan().resources().size());
        assertTrue(candidate.dependencyPlan().resources().stream()
            .anyMatch(resource -> resource.resourceId().endsWith("symbol_0.png.mcmeta")));
        assertTrue(candidate.dependencyPlan().warnings().isEmpty());
    }

    @Test
    void expandsCoreFolderSelectionToSourceProjectRoot() throws IOException {
        String core = "src/main/resources/assets/example/shaders/core/";
        write(core + "nebula.json", """
            {"vertex":"example:nebula","fragment":"example:nebula",
             "samplers":[{"name":"Sampler0"}],
             "uniforms":[{"name":"symbolUvs","type":"matrix2x2","count":4,"values":[0]}]}
            """);
        write(core + "nebula.vsh", "vertex");
        write(core + "nebula.fsh", "fragment");
        write("src/main/java/example/PreviewSetup.java",
            "String path = \"effects/nebula/symbol_\" + index;");
        write("src/main/resources/assets/example/textures/effects/nebula/symbol_0.png", "png");

        List<ShaderFolderImporter.Candidate> candidates = ShaderFolderImporter.scan(
            temporaryDirectory.resolve(core)
        );

        assertEquals(1, candidates.size());
        assertEquals(1, candidates.get(0).dependencyPlan().atlases().size());
    }

    private String json(String vertex, String fragment) {
        return "{\"vertex\":\"" + vertex + "\",\"fragment\":\"" + fragment + "\"}";
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
