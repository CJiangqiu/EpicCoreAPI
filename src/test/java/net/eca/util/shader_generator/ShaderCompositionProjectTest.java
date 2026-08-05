package net.eca.util.shader_generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderCompositionProjectTest {

    @Test
    void preservesAProjectWithNoLayers() {
        ShaderCompositionProject project = new ShaderCompositionProject();
        project.removeLayer(0);

        ShaderCompositionProject restored = new ShaderCompositionProject();
        ShaderProjectCodec.deserializeInto(
            ShaderProjectCodec.serialize("eca", "empty_layers", project), restored
        );

        assertTrue(project.layers().isEmpty());
        assertTrue(restored.layers().isEmpty());
    }

    @Test
    void preservesHybridEditingState() {
        ShaderCompositionProject project = new ShaderCompositionProject();
        for (ShaderSourceFile file : ShaderSourceFile.values()) {
            project.sourceWorkspace().setSource(file, file.serializedName());
        }
        project.setSourceActive(true);
        project.sourceWorkspace().setVisualOverlayEnabled(true);

        ShaderCompositionProject restored = new ShaderCompositionProject();
        ShaderProjectCodec.deserializeInto(
            ShaderProjectCodec.serialize("eca", "hybrid", project), restored
        );

        assertTrue(restored.sourceActive());
        assertTrue(restored.sourceWorkspace().visualOverlayEnabled());
    }
}
