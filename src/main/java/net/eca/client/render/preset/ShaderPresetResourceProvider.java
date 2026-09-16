package net.eca.client.render.preset;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.util.Optional;

/*
 * 将 ECA 预设目录映射为 Minecraft core shader 路径。
 *
 * ShaderInstance 固定请求 shaders/core，适配层让新目录优先，同时保留旧资源包兼容。
 */
public final class ShaderPresetResourceProvider implements ResourceProvider {

    private static final String CORE_PREFIX = "shaders/core/";
    private static final String PRESET_PREFIX = "eca/shader_presets/";

    private final ResourceProvider delegate;

    private ShaderPresetResourceProvider(ResourceProvider delegate) {
        this.delegate = delegate;
    }

    public static ResourceProvider wrap(ResourceProvider provider) {
        if (provider == null || provider instanceof ShaderPresetResourceProvider) {
            return provider;
        }
        return new ShaderPresetResourceProvider(provider);
    }

    @Override
    public Optional<Resource> getResource(ResourceLocation location) {
        String path = location.getPath();
        if (path.startsWith(CORE_PREFIX)) {
            ResourceLocation canonical = ResourceLocation.tryBuild(location.getNamespace(),
                    PRESET_PREFIX + path.substring(CORE_PREFIX.length()));
            if (canonical != null) {
                Optional<Resource> resource = delegate.getResource(canonical);
                if (resource.isPresent()) {
                    return resource;
                }
            }
        }
        return delegate.getResource(location);
    }
}
