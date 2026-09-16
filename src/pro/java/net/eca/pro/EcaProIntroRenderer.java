package net.eca.pro;

import net.eca.agent.AgentLogWriter;
import net.minecraftforge.fml.earlydisplay.ElementShader;
import net.minecraftforge.fml.earlydisplay.QuadHelper;
import net.minecraftforge.fml.earlydisplay.RenderElement;
import net.minecraftforge.fml.earlydisplay.SimpleBufferBuilder;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;

import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL32C.GL_BLEND;
import static org.lwjgl.opengl.GL32C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL32C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL32C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL32C.GL_LINEAR;
import static org.lwjgl.opengl.GL32C.GL_MAX_TEXTURE_SIZE;
import static org.lwjgl.opengl.GL32C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL32C.GL_RGBA;
import static org.lwjgl.opengl.GL32C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL32C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL32C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL32C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL32C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL32C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL32C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL32C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL32C.glActiveTexture;
import static org.lwjgl.opengl.GL32C.glBindTexture;
import static org.lwjgl.opengl.GL32C.glBlendFunc;
import static org.lwjgl.opengl.GL32C.glClear;
import static org.lwjgl.opengl.GL32C.glClearColor;
import static org.lwjgl.opengl.GL32C.glDeleteTextures;
import static org.lwjgl.opengl.GL32C.glEnable;
import static org.lwjgl.opengl.GL32C.glGenTextures;
import static org.lwjgl.opengl.GL32C.glGetInteger;
import static org.lwjgl.opengl.GL32C.glTexImage2D;
import static org.lwjgl.opengl.GL32C.glTexParameteri;

final class EcaProIntroRenderer {
    private static final String RESOURCE_ROOT = "/assets/eca/pro_intro/atlas_";
    private static final int ATLAS_COUNT = 3;
    private static final int ATLAS_COLUMNS = 4;
    private static final int ATLAS_ROWS = 4;
    private static final int FRAME_WIDTH = 854;
    private static final int FRAME_HEIGHT = 480;
    private static final int FRAME_COUNT = 48;
    private static final int FRAMES_PER_SECOND = 20;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private static byte[][] encodedAtlases;
    private static int[] textures;
    private static SimpleBufferBuilder buffer;
    private static long startNanos;
    private static boolean finished;
    private static boolean failed;

    private EcaProIntroRenderer() {
    }

    static synchronized boolean prepareResources() {
        if (failed) return false;
        if (encodedAtlases != null || textures != null) return true;
        try {
            byte[][] prepared = new byte[ATLAS_COUNT][];
            for (int index = 0; index < ATLAS_COUNT; index++) {
                String resource = RESOURCE_ROOT + index + ".png";
                try (InputStream input = EcaProIntroRenderer.class.getResourceAsStream(resource)) {
                    if (input == null) throw new IllegalStateException("intro_resource_missing");
                    prepared[index] = input.readAllBytes();
                }
            }
            encodedAtlases = prepared;
            AgentLogWriter.info("[EcaProIntro] Animation resources prepared");
            return true;
        } catch (Throwable t) {
            failed = true;
            encodedAtlases = null;
            AgentLogWriter.info("[EcaProIntro] Resource preparation failed: " + t.getMessage());
            return false;
        }
    }

    static boolean render(Object displayContext) {
        if (finished || failed || !(displayContext instanceof RenderElement.DisplayContext context)) {
            return false;
        }
        try {
            if (textures == null) initialize();
            if (startNanos == 0L) startNanos = System.nanoTime();

            long elapsed = Math.max(0L, System.nanoTime() - startNanos);
            int frame = (int) (elapsed * FRAMES_PER_SECOND / NANOS_PER_SECOND);
            if (frame >= FRAME_COUNT) {
                release();
                finished = true;
                return false;
            }

            draw(context, frame);
            return true;
        } catch (Throwable t) {
            failed = true;
            releaseQuietly();
            AgentLogWriter.info("[EcaProIntro] Rendering disabled after failure: " + t.getMessage());
            return false;
        }
    }

    private static void initialize() throws Exception {
        if (encodedAtlases == null && !prepareResources()) {
            throw new IllegalStateException("intro_resources_unavailable");
        }
        int atlasWidth = FRAME_WIDTH * ATLAS_COLUMNS;
        int atlasHeight = FRAME_HEIGHT * ATLAS_ROWS;
        int maximumTextureSize = glGetInteger(GL_MAX_TEXTURE_SIZE);
        if (atlasWidth > maximumTextureSize || atlasHeight > maximumTextureSize) {
            throw new IllegalStateException("texture_size_unsupported");
        }

        textures = new int[ATLAS_COUNT];
        for (int index = 0; index < ATLAS_COUNT; index++) {
            textures[index] = loadTexture(encodedAtlases[index], atlasWidth, atlasHeight);
        }
        encodedAtlases = null;
        buffer = new SimpleBufferBuilder(128);
        AgentLogWriter.info("[EcaProIntro] Animation textures initialized");
    }

    private static int loadTexture(byte[] encoded, int expectedWidth, int expectedHeight) {
        ByteBuffer source = BufferUtils.createByteBuffer(encoded.length);
        source.put(encoded).flip();
        int[] width = new int[1];
        int[] height = new int[1];
        int[] channels = new int[1];
        ByteBuffer pixels = STBImage.stbi_load_from_memory(source, width, height, channels, 4);
        if (pixels == null) throw new IllegalStateException(STBImage.stbi_failure_reason());
        try {
            if (width[0] != expectedWidth || height[0] != expectedHeight) {
                throw new IllegalStateException("intro_resource_dimensions");
            }
            int texture = glGenTextures();
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width[0], height[0], 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            return texture;
        } finally {
            STBImage.stbi_image_free(pixels);
        }
    }

    private static void draw(RenderElement.DisplayContext context, int frame) {
        int framesPerAtlas = ATLAS_COLUMNS * ATLAS_ROWS;
        int atlas = frame / framesPerAtlas;
        int cell = frame % framesPerAtlas;
        int column = cell % ATLAS_COLUMNS;
        int row = cell / ATLAS_COLUMNS;
        float atlasWidth = FRAME_WIDTH * ATLAS_COLUMNS;
        float atlasHeight = FRAME_HEIGHT * ATLAS_ROWS;
        float u0 = (column * FRAME_WIDTH + 0.5f) / atlasWidth;
        float u1 = ((column + 1) * FRAME_WIDTH - 0.5f) / atlasWidth;
        float vTop = (row * FRAME_HEIGHT + 0.5f) / atlasHeight;
        float vBottom = ((row + 1) * FRAME_HEIGHT - 0.5f) / atlasHeight;

        glClearColor(3f / 255f, 3f / 255f, 5f / 255f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textures[atlas]);
        context.elementShader().updateTextureUniform(0);
        context.elementShader().updateRenderTypeUniform(ElementShader.RenderType.TEXTURE);
        buffer.begin(SimpleBufferBuilder.Format.POS_TEX_COLOR, SimpleBufferBuilder.Mode.QUADS);
        QuadHelper.loadQuad(buffer, 0, context.scaledWidth(), 0, context.scaledHeight(),
                u0, u1, vTop, vBottom, 0xFFFFFFFF);
        buffer.draw();
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private static void release() {
        if (textures != null) {
            for (int texture : textures) {
                if (texture != 0) glDeleteTextures(texture);
            }
            textures = null;
        }
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }

    private static void releaseQuietly() {
        try {
            release();
        } catch (Throwable ignored) {
        }
    }
}
