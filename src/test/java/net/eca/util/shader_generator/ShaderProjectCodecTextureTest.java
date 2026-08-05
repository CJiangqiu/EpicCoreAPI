package net.eca.util.shader_generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderProjectCodecTextureTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesAnimationMetadataBesideImportedPng() throws IOException {
        Path source = temporaryDirectory.resolve("source/effect.png");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[] {1, 2, 3});
        Files.writeString(Path.of(source + ".mcmeta"), "{\"animation\":{\"frametime\":2}}");

        Path target = ShaderProjectCodec.copyTextureAsset(
            source,
            temporaryDirectory.resolve("project/textures/shader_generator"),
            "effect.png"
        );

        assertTrue(Files.isRegularFile(target));
        Path metadata = Path.of(target + ".mcmeta");
        assertTrue(Files.isRegularFile(metadata));
        assertEquals("{\"animation\":{\"frametime\":2}}", Files.readString(metadata));
    }

    @Test
    void givesMetadataTheSameUniqueStemAsItsTexture() throws IOException {
        Path source = temporaryDirectory.resolve("source/effect.png");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[] {1});
        Files.writeString(Path.of(source + ".mcmeta"), "{}");
        Path targetDirectory = temporaryDirectory.resolve("project/textures/shader_generator");

        ShaderProjectCodec.copyTextureAsset(source, targetDirectory, "effect.png");
        Path second = ShaderProjectCodec.copyTextureAsset(source, targetDirectory, "effect.png");

        assertEquals("effect_2.png", second.getFileName().toString());
        assertTrue(Files.isRegularFile(Path.of(second + ".mcmeta")));
    }
}
