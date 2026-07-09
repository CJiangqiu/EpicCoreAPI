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
import net.eca.util.shader_generator.ShaderTargetProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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

    private GeneratedShaderPreview(
        Component displayName,
        ShaderInstance blockShader,
        ShaderInstance entityShader,
        List<ResourceLocation> importedTextures
    ) {
        this.displayName = displayName;
        this.blockShader = blockShader;
        this.entityShader = entityShader;
        this.blockUniforms = new UniformSet(blockShader);
        this.entityUniforms = new UniformSet(entityShader);
        this.importedTextures = List.copyOf(importedTextures);

        RenderStateShard.ShaderStateShard blockState = shaderState(blockShader, blockUniforms);
        RenderStateShard.ShaderStateShard entityState = shaderState(entityShader, entityUniforms);
        String name = "eca_shader_generator_" + Integer.toHexString(System.identityHashCode(this));
        this.bossBar = createBossBar(name, blockState);
        this.skybox = createSkybox(name, blockState);
        this.item = createItem(name, entityState);
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
        MemoryShaderResourceProvider provider = new MemoryShaderResourceProvider(
            project.namespace(),
            bundle,
            Minecraft.getInstance().getResourceManager()
        );
        ShaderInstance blockShader = null;
        try {
            blockShader = EcaShaderInstance.create(
                provider,
                new ResourceLocation(project.namespace(), project.path() + "_block"),
                DefaultVertexFormat.BLOCK
            );
            ShaderInstance entityShader = EcaShaderInstance.create(
                provider,
                new ResourceLocation(project.namespace(), project.path() + "_entity"),
                DefaultVertexFormat.NEW_ENTITY
            );
            List<ResourceLocation> textures = bindImportedTextures(
                blockShader,
                entityShader,
                texturePaths
            );
            return new GeneratedShaderPreview(
                Component.translatable("gui.eca.shader_generator.source.current"),
                blockShader,
                entityShader,
                textures
            );
        } catch (IOException | RuntimeException exception) {
            if (blockShader != null) {
                blockShader.close();
            }
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
            shaderState(entityShader, entityUniforms),
            value
        ));
    }

    @Override
    public void close() {
        blockShader.close();
        entityShader.close();
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation texture : importedTextures) {
            textureManager.release(texture);
        }
    }

    private static List<ResourceLocation> bindImportedTextures(
        ShaderInstance blockShader,
        ShaderInstance entityShader,
        Map<String, Path> texturePaths
    ) throws IOException {
        List<ResourceLocation> registered = new ArrayList<>();
        if (texturePaths == null || texturePaths.isEmpty()) {
            return registered;
        }
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        long revision = TEXTURE_REVISION.incrementAndGet();
        try {
            for (Map.Entry<String, Path> entry : texturePaths.entrySet()) {
                try (InputStream input = Files.newInputStream(entry.getValue())) {
                    DynamicTexture texture = new DynamicTexture(NativeImage.read(input));
                    ResourceLocation location = textureManager.register(
                        "eca_shader_generator/" + revision + "/" + entry.getKey().toLowerCase(),
                        texture
                    );
                    registered.add(location);
                    blockShader.setSampler(entry.getKey(), texture);
                    entityShader.setSampler(entry.getKey(), texture);
                }
            }
            return registered;
        } catch (IOException | RuntimeException exception) {
            for (ResourceLocation location : registered) {
                textureManager.release(location);
            }
            throw exception;
        }
    }

    private static RenderStateShard.ShaderStateShard shaderState(
        ShaderInstance shader,
        UniformSet uniforms
    ) {
        return new RenderStateShard.ShaderStateShard(() -> shader) {
            @Override
            public void setupRenderState() {
                super.setupRenderState();
                uniforms.apply();
            }
        };
    }

    private static RenderType createBossBar(
        String name,
        RenderStateShard.ShaderStateShard shader
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
                .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(RenderType.NO_DEPTH_TEST)
                .setWriteMaskState(RenderType.COLOR_WRITE)
                .createCompositeState(false)
        );
    }

    private static RenderType createSkybox(
        String name,
        RenderStateShard.ShaderStateShard shader
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
        private final Uniform cameraYaw;
        private final Uniform cameraPitch;
        private final Uniform colorKeyColor;
        private final Uniform colorKeyTolerance;
        private final Uniform localUvMin;
        private final Uniform localUvScale;

        private UniformSet(ShaderInstance shader) {
            this.time = shader.getUniform("GameTime");
            this.cameraYaw = shader.getUniform("CameraYaw");
            this.cameraPitch = shader.getUniform("CameraPitch");
            this.colorKeyColor = shader.getUniform("ColorKeyColor");
            this.colorKeyTolerance = shader.getUniform("ColorKeyTolerance");
            this.localUvMin = shader.getUniform("LocalUvMin");
            this.localUvScale = shader.getUniform("LocalUvScale");
        }

        private void apply() {
            Minecraft minecraft = Minecraft.getInstance();
            if (time != null) {
                float systemTime = (System.currentTimeMillis() % 1000000L) / 1000.0F;
                time.set(systemTime);
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
        }
    }
}
