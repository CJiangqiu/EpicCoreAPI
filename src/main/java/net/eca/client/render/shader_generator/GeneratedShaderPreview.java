package net.eca.client.render.shader_generator;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.eca.client.render.shader.EcaShaderInstance;
import net.eca.util.shader_generator.ShaderExportBundle;
import net.eca.util.shader_generator.ShaderExportMode;
import net.eca.util.shader_generator.ShaderGenerator;
import net.eca.util.shader_generator.ShaderProject;
import net.eca.util.shader_generator.ShaderSourceAssembler;
import net.eca.util.shader_generator.ShaderSourceWorkspace;
import net.eca.util.shader_generator.ShaderTargetProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
@SuppressWarnings("removal")
public final class GeneratedShaderPreview implements ShaderPreviewSource, AutoCloseable {

    private static final AtomicLong TEXTURE_REVISION = new AtomicLong();
    private final Component displayName;
    private final ShaderInstance blockShader;
    private final ShaderInstance entityShader;
    private final UniformSet blockUniforms;
    private final UniformSet entityUniforms;
    private final RenderType bossBar;
    private final RenderType skybox;
    private final RenderType item;
    private final Map<ResourceLocation, RenderType> entityTypes = new ConcurrentHashMap<>();
    private final List<ResourceLocation> importedTextures;
    private final List<PreviewAnimation> previewAnimations;
    private final ResourceLocation blockPreviewTexture;
    private final ResourceLocation whitePreviewTexture;
    private final String maskSamplerName;

    private GeneratedShaderPreview(
        Component displayName,
        ShaderInstance blockShader,
        ShaderInstance entityShader,
        List<ResourceLocation> importedTextures,
        ResourceLocation blockPreviewTexture,
        Map<String, float[]> uniformArrays,
        List<PreviewAnimation> previewAnimations,
        ResourceLocation whitePreviewTexture,
        String maskSamplerName
    ) {
        this.displayName = displayName;
        this.blockShader = blockShader;
        this.entityShader = entityShader;
        this.blockUniforms = new UniformSet(blockShader, uniformArrays, previewAnimations);
        this.entityUniforms = new UniformSet(entityShader, uniformArrays, previewAnimations);
        this.importedTextures = List.copyOf(importedTextures);
        this.previewAnimations = List.copyOf(previewAnimations);
        this.blockPreviewTexture = blockPreviewTexture;
        this.whitePreviewTexture = whitePreviewTexture;
        this.maskSamplerName = maskSamplerName;

        RenderStateShard.ShaderStateShard blockState = shaderState(
            blockShader,
            blockUniforms,
            maskBinding(blockShader, maskSamplerName, whitePreviewTexture)
        );
        RenderStateShard.ShaderStateShard itemState = shaderState(
            entityShader,
            entityUniforms,
            maskBinding(entityShader, maskSamplerName, InventoryMenu.BLOCK_ATLAS)
        );
        String name = "eca_shader_generator_" + Integer.toHexString(System.identityHashCode(this));
        this.bossBar = createBossBar(name, blockState, blockPreviewTexture);
        this.skybox = createSkybox(name, blockState, blockPreviewTexture);
        this.item = createItem(name, itemState);
    }

    public static GeneratedShaderPreview compile(
        ShaderProject project,
        ShaderExportMode exportMode,
        Map<String, Path> texturePaths
    ) throws IOException {
        ShaderExportBundle bundle = ShaderGenerator.standard().generate(
            new ShaderGenerator.Request(
                project,
                exportMode,
                EnumSet.allOf(ShaderTargetProfile.class)
            )
        );
        return compileBundle(project.namespace(), project.path(), bundle, texturePaths, Dependencies.EMPTY);
    }

    public static GeneratedShaderPreview compileSource(
        ShaderProject visualProject,
        ShaderSourceWorkspace workspace,
        Map<String, Path> texturePaths,
        Dependencies dependencies
    ) throws IOException {
        ShaderExportBundle bundle = ShaderSourceAssembler.assemble(
            visualProject.namespace(), visualProject.path(), workspace, visualProject
        );
        return compileBundle(
            visualProject.namespace(), visualProject.path(), bundle, texturePaths, dependencies
        );
    }

    private static GeneratedShaderPreview compileBundle(
        String namespace,
        String path,
        ShaderExportBundle bundle,
        Map<String, Path> texturePaths,
        Dependencies dependencies
    ) throws IOException {
        PreviewShaderBundleAdapter.Result previewBundle = PreviewShaderBundleAdapter.adapt(
            bundle, dependencies
        );
        MemoryShaderResourceProvider provider = new MemoryShaderResourceProvider(
            namespace,
            previewBundle.bundle(),
            Minecraft.getInstance().getResourceManager(),
            dependencies.resources()
        );
        ShaderInstance blockShader = null;
        ShaderInstance entityShader = null;
        List<ResourceLocation> textures = new ArrayList<>();
        List<PreviewAnimation> animations = new ArrayList<>();
        try {
            blockShader = EcaShaderInstance.create(
                provider,
                new ResourceLocation(namespace, path + "_block"),
                DefaultVertexFormat.BLOCK
            );
            entityShader = EcaShaderInstance.create(
                provider,
                new ResourceLocation(namespace, path + "_entity"),
                DefaultVertexFormat.NEW_ENTITY
            );
            ImportedTextureBindings importedBindings = bindImportedTextures(
                blockShader,
                entityShader,
                texturePaths
            );
            textures.addAll(importedBindings.locations());
            animations.addAll(importedBindings.animations());
            PreviewTextureBindings previewBindings = bindPreviewTextures(
                blockShader, entityShader, dependencies, textures
            );
            animations.addAll(previewBindings.animatedAtlases());
            return new GeneratedShaderPreview(
                Component.translatable("gui.eca.shader_generator.source.current"),
                blockShader,
                entityShader,
                textures,
                previewBindings.blockTexture(),
                previewBindings.uniformArrays(),
                animations,
                previewBindings.whiteTexture(),
                previewBundle.maskSamplerName()
            );
        } catch (IOException | RuntimeException exception) {
            if (blockShader != null) {
                blockShader.close();
            }
            if (entityShader != null) entityShader.close();
            animations.forEach(PreviewAnimation::close);
            TextureManager textureManager = Minecraft.getInstance().getTextureManager();
            for (ResourceLocation texture : textures) textureManager.release(texture);
            throw exception;
        }
    }

    @Override
    public Component displayName() {
        return displayName;
    }

    @Override
    public RenderType bossBar() {
        return bossBar;
    }

    @Override
    public RenderType skybox() {
        return skybox;
    }

    @Override
    public RenderType item() {
        return item;
    }

    @Override
    public RenderType entity(ResourceLocation texture) {
        return entityTypes.computeIfAbsent(texture, value -> createEntity(
            "eca_shader_generator_" + Integer.toHexString(System.identityHashCode(this)),
            shaderState(
                entityShader,
                entityUniforms,
                maskBinding(entityShader, maskSamplerName, value)
            ),
            value
        ));
    }

    @Override
    public void close() {
        blockShader.close();
        entityShader.close();
        previewAnimations.forEach(PreviewAnimation::close);
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation texture : importedTextures) {
            textureManager.release(texture);
        }
    }

    private static ImportedTextureBindings bindImportedTextures(
        ShaderInstance blockShader,
        ShaderInstance entityShader,
        Map<String, Path> texturePaths
    ) throws IOException {
        List<ResourceLocation> registered = new ArrayList<>();
        List<AnimatedPreviewTexture> animations = new ArrayList<>();
        if (texturePaths == null || texturePaths.isEmpty()) {
            return new ImportedTextureBindings(registered, animations);
        }
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        long revision = TEXTURE_REVISION.incrementAndGet();
        try {
            for (Map.Entry<String, Path> entry : texturePaths.entrySet()) {
                AnimatedPreviewTexture animation = AnimatedPreviewTexture.create(entry.getValue());
                animations.add(animation);
                DynamicTexture texture = animation.texture();
                ResourceLocation location = textureManager.register(
                    "eca_shader_generator/" + revision + "/" + entry.getKey().toLowerCase(),
                    texture
                );
                registered.add(location);
                blockShader.setSampler(entry.getKey(), texture);
                entityShader.setSampler(entry.getKey(), texture);
            }
            return new ImportedTextureBindings(registered, animations);
        } catch (IOException | RuntimeException exception) {
            animations.forEach(AnimatedPreviewTexture::close);
            for (ResourceLocation location : registered) {
                textureManager.release(location);
            }
            throw exception;
        }
    }

    private static RenderStateShard.ShaderStateShard shaderState(
        ShaderInstance shader,
        UniformSet uniforms,
        Runnable beforeShaderSetup
    ) {
        return new RenderStateShard.ShaderStateShard(() -> shader) {
            @Override
            public void setupRenderState() {
                beforeShaderSetup.run();
                super.setupRenderState();
                uniforms.apply();
            }
        };
    }

    private static Runnable maskBinding(
        ShaderInstance shader,
        String samplerName,
        ResourceLocation texture
    ) {
        if (samplerName == null) return () -> {};
        return () -> shader.setSampler(
            samplerName, Minecraft.getInstance().getTextureManager().getTexture(texture)
        );
    }

    private static RenderType createBossBar(
        String name,
        RenderStateShard.ShaderStateShard shader,
        ResourceLocation texture
    ) {
        return RenderType.create(
            name + "_boss_bar",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            RenderType.CompositeState.builder()
                .setShaderState(shader)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(RenderType.NO_DEPTH_TEST)
                .setWriteMaskState(RenderType.COLOR_WRITE)
                .createCompositeState(false)
        );
    }

    private static RenderType createSkybox(
        String name,
        RenderStateShard.ShaderStateShard shader,
        ResourceLocation texture
    ) {
        return RenderType.create(
            name + "_skybox",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            256,
            true,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(shader)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(RenderType.NO_DEPTH_TEST)
                .setWriteMaskState(RenderType.COLOR_WRITE)
                .createCompositeState(false)
        );
    }

    private static RenderType createItem(
        String name,
        RenderStateShard.ShaderStateShard shader
    ) {
        return RenderType.create(
            name + "_item",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            256,
            true,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(shader)
                .setTextureState(RenderType.BLOCK_SHEET_MIPPED)
                .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(RenderType.LEQUAL_DEPTH_TEST)
                .setCullState(RenderType.NO_CULL)
                .setOverlayState(RenderType.OVERLAY)
                .setWriteMaskState(RenderType.COLOR_WRITE)
                .createCompositeState(true)
        );
    }

    private static RenderType createEntity(
        String name,
        RenderStateShard.ShaderStateShard shader,
        ResourceLocation texture
    ) {
        return RenderType.create(
            name + "_entity",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            256,
            true,
            true,
            RenderType.CompositeState.builder()
                .setShaderState(shader)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(RenderType.LEQUAL_DEPTH_TEST)
                .setLightmapState(RenderType.LIGHTMAP)
                .setOverlayState(RenderType.OVERLAY)
                .setCullState(RenderType.NO_CULL)
                .setWriteMaskState(RenderType.COLOR_DEPTH_WRITE)
                .createCompositeState(true)
        );
    }

    private static final class UniformSet {

        private final Uniform time;
        private final Uniform legacyTime;
        private final Uniform cameraYaw;
        private final Uniform cameraPitch;
        private final Uniform colorKeyColor;
        private final Uniform colorKeyTolerance;
        private final Uniform localUvMin;
        private final Uniform localUvScale;
        private final Uniform externalScale;
        private final Uniform opacity;
        private final Map<Uniform, float[]> importedArrays;
        private final List<PreviewAnimation> previewAnimations;

        private UniformSet(
            ShaderInstance shader,
            Map<String, float[]> uniformArrays,
            List<PreviewAnimation> previewAnimations
        ) {
            this.time = shader.getUniform("GameTime");
            this.legacyTime = shader.getUniform("time");
            this.cameraYaw = firstUniform(shader, "CameraYaw", "yaw");
            this.cameraPitch = firstUniform(shader, "CameraPitch", "pitch");
            this.colorKeyColor = shader.getUniform("ColorKeyColor");
            this.colorKeyTolerance = shader.getUniform("ColorKeyTolerance");
            this.localUvMin = shader.getUniform("LocalUvMin");
            this.localUvScale = shader.getUniform("LocalUvScale");
            this.externalScale = shader.getUniform("externalScale");
            this.opacity = shader.getUniform("opacity");
            this.previewAnimations = List.copyOf(previewAnimations);
            this.importedArrays = new LinkedHashMap<>();
            uniformArrays.forEach((name, values) -> {
                Uniform uniform = shader.getUniform(name);
                if (uniform != null) importedArrays.put(uniform, values.clone());
            });
        }

        private void apply() {
            previewAnimations.forEach(PreviewAnimation::update);
            Minecraft minecraft = Minecraft.getInstance();
            if (time != null) {
                float systemTime = (System.currentTimeMillis() % 1000000L) / 1000.0F;
                time.set(systemTime);
                if (legacyTime != null) legacyTime.set(systemTime * 10.0F);
            } else if (legacyTime != null) {
                legacyTime.set((System.currentTimeMillis() % 1000000L) / 100.0F);
            }
            if (minecraft.gameRenderer != null && minecraft.gameRenderer.getMainCamera() != null) {
                if (cameraYaw != null) {
                    cameraYaw.set((float) Math.toRadians(
                        minecraft.gameRenderer.getMainCamera().getYRot()
                    ));
                }
                if (cameraPitch != null) {
                    cameraPitch.set((float) Math.toRadians(
                        minecraft.gameRenderer.getMainCamera().getXRot()
                    ));
                }
            }
            EcaShaderInstance.applyColorKeyUniforms(colorKeyColor, colorKeyTolerance);
            EcaShaderInstance.applyLocalUvBoundsUniforms(localUvMin, localUvScale);
            if (externalScale != null) externalScale.set(1.0F);
            if (opacity != null) opacity.set(1.0F);
            importedArrays.forEach((uniform, values) -> uniform.set(values));
        }

        private static Uniform firstUniform(ShaderInstance shader, String... names) {
            for (String name : names) {
                Uniform uniform = shader.getUniform(name);
                if (uniform != null) return uniform;
            }
            return null;
        }

    }

    private static PreviewTextureBindings bindPreviewTextures(
        ShaderInstance blockShader,
        ShaderInstance entityShader,
        Dependencies dependencies,
        List<ResourceLocation> registered
    ) throws IOException {
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        long revision = TEXTURE_REVISION.incrementAndGet();
        NativeImage whiteImage = new NativeImage(1, 1, false);
        whiteImage.setPixelRGBA(0, 0, 0xFFFFFFFF);
        DynamicTexture whiteTexture = new DynamicTexture(whiteImage);
        ResourceLocation whiteLocation = textureManager.register(
            "eca_shader_generator/" + revision + "/preview_white", whiteTexture
        );
        registered.add(whiteLocation);
        for (String sampler : dependencies.samplers()) {
            if ("Sampler0".equals(sampler)) continue;
            blockShader.setSampler(sampler, whiteTexture);
            entityShader.setSampler(sampler, whiteTexture);
        }

        ResourceLocation blockTexture = whiteLocation;
        Map<String, float[]> uniformArrays = new LinkedHashMap<>();
        List<AnimatedPreviewAtlas> animatedAtlases = new ArrayList<>();
        try {
            for (ResolvedAtlasBinding binding : dependencies.atlases()) {
                AnimatedPreviewAtlas atlas = AnimatedPreviewAtlas.create(binding.spritePaths());
                animatedAtlases.add(atlas);
                DynamicTexture texture = atlas.texture();
                ResourceLocation location = textureManager.register(
                    "eca_shader_generator/" + revision + "/atlas_" + registered.size(), texture
                );
                registered.add(location);
                blockShader.setSampler(binding.samplerName(), texture);
                entityShader.setSampler(binding.samplerName(), texture);
                uniformArrays.put(binding.uniformName(), atlas.spriteUvs());
                if ("Sampler0".equals(binding.samplerName())) {
                    blockTexture = location;
                }
            }
            return new PreviewTextureBindings(
                blockTexture, uniformArrays, animatedAtlases, whiteLocation
            );
        } catch (IOException | RuntimeException exception) {
            animatedAtlases.forEach(AnimatedPreviewAtlas::close);
            throw exception;
        }
    }

    public record Dependencies(
        Map<ResourceLocation, Path> resources,
        Set<String> samplers,
        List<ResolvedAtlasBinding> atlases
    ) {
        public static final Dependencies EMPTY = new Dependencies(Map.of(), Set.of(), List.of());

        public Dependencies {
            resources = resources == null ? Map.of() : Map.copyOf(resources);
            samplers = samplers == null ? Set.of() : Set.copyOf(samplers);
            atlases = atlases == null ? List.of() : List.copyOf(atlases);
        }
    }

    public record ResolvedAtlasBinding(
        String samplerName,
        String uniformName,
        List<Path> spritePaths
    ) {
        public ResolvedAtlasBinding {
            spritePaths = List.copyOf(spritePaths);
        }
    }

    private record PreviewTextureBindings(
        ResourceLocation blockTexture,
        Map<String, float[]> uniformArrays,
        List<AnimatedPreviewAtlas> animatedAtlases,
        ResourceLocation whiteTexture
    ) {}

    private record ImportedTextureBindings(
        List<ResourceLocation> locations,
        List<AnimatedPreviewTexture> animations
    ) {}
}
