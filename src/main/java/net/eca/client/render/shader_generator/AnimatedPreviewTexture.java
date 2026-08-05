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

final class AnimatedPreviewTexture implements PreviewAnimation {

    private final NativeImage sheet;
    private final NativeImage currentImage;
    private final DynamicTexture texture;
    private final int frameWidth;
    private final int frameHeight;
    private final int columns;
    private final List<Frame> frames;
    private final int totalDuration;
    private final boolean interpolate;
    private long lastAnimationTick = Long.MIN_VALUE;
    private int lastFramePosition = -1;

    static AnimatedPreviewTexture create(Path path) throws IOException {
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
                    || sheet.getWidth() % frameWidth != 0
                    || sheet.getHeight() % frameHeight != 0) {
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
            return new AnimatedPreviewTexture(
                sheet,
                frameWidth,
                frameHeight,
                frames,
                metadata.isInterpolatedFrames()
            );
        } catch (IOException | RuntimeException exception) {
            sheet.close();
            throw exception;
        }
    }

    private AnimatedPreviewTexture(
        NativeImage sheet,
        int frameWidth,
        int frameHeight,
        List<Frame> frames,
        boolean interpolate
    ) {
        this.sheet = sheet;
        this.currentImage = new NativeImage(frameWidth, frameHeight, false);
        this.texture = new DynamicTexture(currentImage);
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.columns = sheet.getWidth() / frameWidth;
        this.frames = List.copyOf(frames);
        this.totalDuration = frames.stream().mapToInt(Frame::duration).sum();
        this.interpolate = interpolate;
        renderFrame(0, 0.0D);
    }

    DynamicTexture texture() {
        return texture;
    }

    @Override
    public void update() {
        long animationTick = System.currentTimeMillis() / 50L;
        if (animationTick == lastAnimationTick) return;
        lastAnimationTick = animationTick;
        FrameSelection selection = selectFrame(animationTick);
        if (!interpolate && selection.position() == lastFramePosition) return;
        lastFramePosition = selection.position();
        renderFrame(selection.position(), selection.progress());
        texture.upload();
    }

    private FrameSelection selectFrame(long animationTick) {
        int offset = (int) Math.floorMod(animationTick, totalDuration);
        for (int position = 0; position < frames.size(); position++) {
            Frame frame = frames.get(position);
            if (offset < frame.duration()) {
                return new FrameSelection(position, (double) offset / frame.duration());
            }
            offset -= frame.duration();
        }
        return new FrameSelection(frames.size() - 1, 0.0D);
    }

    private void renderFrame(int position, double progress) {
        Frame current = frames.get(position);
        Frame next = frames.get((position + 1) % frames.size());
        int currentX = current.index() % columns * frameWidth;
        int currentY = current.index() / columns * frameHeight;
        int nextX = next.index() % columns * frameWidth;
        int nextY = next.index() / columns * frameHeight;
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                int currentColor = sheet.getPixelRGBA(currentX + x, currentY + y);
                int color = interpolate && current.index() != next.index()
                    ? interpolateColor(
                        currentColor,
                        sheet.getPixelRGBA(nextX + x, nextY + y),
                        progress
                    )
                    : currentColor;
                currentImage.setPixelRGBA(x, y, color);
            }
        }
    }

    private static int interpolateColor(int current, int next, double progress) {
        double currentWeight = 1.0D - progress;
        int red = mix(currentWeight, current >> 16 & 0xFF, next >> 16 & 0xFF);
        int green = mix(currentWeight, current >> 8 & 0xFF, next >> 8 & 0xFF);
        int blue = mix(currentWeight, current & 0xFF, next & 0xFF);
        return current & 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static int mix(double currentWeight, int current, int next) {
        return (int) (currentWeight * current + (1.0D - currentWeight) * next);
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

    @Override
    public void close() {
        sheet.close();
    }

    private record Frame(int index, int duration) {}

    private record FrameSelection(int position, double progress) {}
}
