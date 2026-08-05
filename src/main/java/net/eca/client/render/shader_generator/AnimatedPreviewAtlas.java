package net.eca.client.render.shader_generator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class AnimatedPreviewAtlas implements PreviewAnimation {

    private final NativeImage atlasImage;
    private final DynamicTexture texture;
    private final List<AnimatedSprite> sprites;
    private final float[] spriteUvs;
    private long lastAnimationTick = Long.MIN_VALUE;

    static AnimatedPreviewAtlas create(List<Path> paths) throws IOException {
        List<AnimatedSprite> sprites = new ArrayList<>();
        try {
            int cellWidth = 1;
            int cellHeight = 1;
            for (Path path : paths) {
                AnimatedSprite sprite = AnimatedSprite.read(path);
                sprites.add(sprite);
                cellWidth = Math.max(cellWidth, sprite.frameWidth());
                cellHeight = Math.max(cellHeight, sprite.frameHeight());
            }
            int slotCount = Math.max(1, sprites.size());
            int columns = Math.max(1, (int) Math.ceil(Math.sqrt(slotCount)));
            int rows = Math.max(1, (slotCount + columns - 1) / columns);
            int width = columns * cellWidth;
            int height = rows * cellHeight;
            NativeImage atlasImage = new NativeImage(width, height, false);

            float[] spriteUvs = new float[sprites.size() * 4];
            for (int index = 0; index < sprites.size(); index++) {
                int x = index % columns * cellWidth;
                int y = index / columns * cellHeight;
                AnimatedSprite sprite = sprites.get(index);
                sprite.place(x, y);
                sprite.copyCurrentFrame(atlasImage);
                spriteUvs[index * 4] = (float) x / width;
                spriteUvs[index * 4 + 1] = (float) y / height;
                spriteUvs[index * 4 + 2] = (float) (x + sprite.frameWidth()) / width;
                spriteUvs[index * 4 + 3] = (float) (y + sprite.frameHeight()) / height;
            }
            return new AnimatedPreviewAtlas(atlasImage, sprites, spriteUvs);
        } catch (IOException | RuntimeException exception) {
            sprites.forEach(AnimatedSprite::close);
            throw exception;
        }
    }

    private AnimatedPreviewAtlas(
        NativeImage atlasImage,
        List<AnimatedSprite> sprites,
        float[] spriteUvs
    ) {
        this.atlasImage = atlasImage;
        this.texture = new DynamicTexture(atlasImage);
        this.sprites = List.copyOf(sprites);
        this.spriteUvs = spriteUvs;
    }

    DynamicTexture texture() {
        return texture;
    }

    float[] spriteUvs() {
        return spriteUvs.clone();
    }

    @Override
    public void update() {
        long animationTick = System.currentTimeMillis() / 50L;
        if (animationTick == lastAnimationTick) return;
        lastAnimationTick = animationTick;
        boolean changed = false;
        for (AnimatedSprite sprite : sprites) {
            if (!sprite.selectFrame(animationTick)) continue;
            fill(
                atlasImage,
                sprite.targetX(),
                sprite.targetY(),
                sprite.frameWidth(),
                sprite.frameHeight(),
                0x00000000
            );
            sprite.copyCurrentFrame(atlasImage);
            changed = true;
        }
        if (changed) texture.upload();
    }

    @Override
    public void close() {
        sprites.forEach(AnimatedSprite::close);
    }

    private static void fill(NativeImage image, int x, int y, int width, int height, int color) {
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                image.setPixelRGBA(x + column, y + row, color);
            }
        }
    }

    private static final class AnimatedSprite implements AutoCloseable {

        private final NativeImage sheet;
        private final int frameWidth;
        private final int frameHeight;
        private final int columns;
        private final List<Frame> frames;
        private final int totalDuration;
        private int targetX;
        private int targetY;
        private int selectedFrame;

        static AnimatedSprite read(Path path) throws IOException {
            NativeImage sheet;
            try (InputStream input = Files.newInputStream(path)) {
                sheet = NativeImage.read(input);
            }
            try {
                AnimationMetadataSection metadata = readMetadata(path);
                FrameSize frameSize = metadata.calculateFrameSize(sheet.getWidth(), sheet.getHeight());
                int frameWidth = frameSize.width();
                int frameHeight = frameSize.height();
                if (frameWidth <= 0 || frameHeight <= 0
                        || sheet.getWidth() % frameWidth != 0 || sheet.getHeight() % frameHeight != 0) {
                    throw new IOException("Invalid animation frame size for " + path);
                }
                int availableFrames = sheet.getWidth() / frameWidth * (sheet.getHeight() / frameHeight);
                List<Frame> frames = new ArrayList<>();
                metadata.forEachFrame((index, duration) -> {
                    if (index >= 0 && index < availableFrames && duration > 0) {
                        frames.add(new Frame(index, duration));
                    }
                });
                if (frames.isEmpty()) {
                    for (int index = 0; index < availableFrames; index++) {
                        frames.add(new Frame(index, metadata.getDefaultFrameTime()));
                    }
                }
                return new AnimatedSprite(sheet, frameWidth, frameHeight, frames);
            } catch (IOException | RuntimeException exception) {
                sheet.close();
                throw exception;
            }
        }

        private AnimatedSprite(
            NativeImage sheet,
            int frameWidth,
            int frameHeight,
            List<Frame> frames
        ) {
            this.sheet = sheet;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.columns = sheet.getWidth() / frameWidth;
            this.frames = List.copyOf(frames);
            this.totalDuration = frames.stream().mapToInt(Frame::duration).sum();
            this.selectedFrame = frames.get(0).index();
        }

        private static AnimationMetadataSection readMetadata(Path texturePath) throws IOException {
            Path metadataPath = Path.of(texturePath.toString() + ".mcmeta");
            if (!Files.isRegularFile(metadataPath)) return AnimationMetadataSection.EMPTY;
            JsonObject root = JsonParser.parseString(Files.readString(metadataPath)).getAsJsonObject();
            if (!root.has("animation") || !root.get("animation").isJsonObject()) {
                return AnimationMetadataSection.EMPTY;
            }
            return AnimationMetadataSection.SERIALIZER.fromJson(root.getAsJsonObject("animation"));
        }

        void place(int x, int y) {
            targetX = x;
            targetY = y;
        }

        boolean selectFrame(long animationTick) {
            int offset = (int) Math.floorMod(animationTick, totalDuration);
            int nextFrame = frames.get(frames.size() - 1).index();
            for (Frame frame : frames) {
                if (offset < frame.duration()) {
                    nextFrame = frame.index();
                    break;
                }
                offset -= frame.duration();
            }
            if (nextFrame == selectedFrame) return false;
            selectedFrame = nextFrame;
            return true;
        }

        void copyCurrentFrame(NativeImage target) {
            int sourceX = selectedFrame % columns * frameWidth;
            int sourceY = selectedFrame / columns * frameHeight;
            for (int y = 0; y < frameHeight; y++) {
                for (int x = 0; x < frameWidth; x++) {
                    target.setPixelRGBA(
                        targetX + x,
                        targetY + y,
                        sheet.getPixelRGBA(sourceX + x, sourceY + y)
                    );
                }
            }
        }

        int frameWidth() {
            return frameWidth;
        }

        int frameHeight() {
            return frameHeight;
        }

        int targetX() {
            return targetX;
        }

        int targetY() {
            return targetY;
        }

        @Override
        public void close() {
            sheet.close();
        }

        private record Frame(int index, int duration) {}
    }
}
