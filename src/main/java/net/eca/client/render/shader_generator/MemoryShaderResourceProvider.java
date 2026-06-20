package net.eca.client.render.shader_generator;

import net.eca.util.shader_generator.ShaderExportBundle;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class MemoryShaderResourceProvider implements ResourceProvider {

    private final Map<ResourceLocation, byte[]> resources = new HashMap<>();
    private final ResourceProvider fallback;
    private final PackResources pack = new MemoryPackResources();

    MemoryShaderResourceProvider(
        String namespace,
        ShaderExportBundle bundle,
        ResourceProvider fallback
    ) {
        this.fallback = fallback;
        String prefix = "assets/" + namespace + "/";
        for (ShaderExportBundle.File file : bundle.files()) {
            if (!file.relativePath().startsWith(prefix)) {
                continue;
            }
            String path = file.relativePath().substring(prefix.length());
            resources.put(
                ResourceLocation.fromNamespaceAndPath(namespace, path),
                file.content().getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    @Override
    public Optional<Resource> getResource(ResourceLocation location) {
        byte[] bytes = resources.get(location);
        if (bytes != null) {
            return Optional.of(new Resource(pack, () -> new ByteArrayInputStream(bytes)));
        }
        return fallback.getResource(location);
    }

    private static final class MemoryPackResources implements PackResources {

        @Override
        public IoSupplier<InputStream> getRootResource(String... path) {
            return null;
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
            return null;
        }

        @Override
        public void listResources(
            PackType type,
            String namespace,
            String path,
            ResourceOutput output
        ) {
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return Set.of("eca_preview");
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
            return null;
        }

        @Override
        public String packId() {
            return "eca_shader_generator_memory";
        }

        @Override
        public void close() {
        }
    }
}
