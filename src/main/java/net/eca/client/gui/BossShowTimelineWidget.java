package net.eca.client.gui;

import net.eca.util.bossshow.BossShowDefinition.EventCue;
import net.eca.util.bossshow.BossShowDefinition.Frame;
import net.eca.util.bossshow.BossShowDefinition.SubtitleCue;
import net.eca.util.bossshow.BossShowEditorState;
import net.eca.util.bossshow.BossShowEffectCue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

//BossShow 多轨时间线：镜头、事件和字幕共用同一个播放头。
final class BossShowTimelineWidget extends AbstractWidget {

    enum Track { CAMERA, EVENT, SUBTITLE, EFFECT }

    @FunctionalInterface
    interface SelectionListener {
        void onSelected(Track track, int tick);
    }

    private static final int LABEL_WIDTH = 64;
    private static final int RULER_HEIGHT = 16;
    private static final int MIN_TRACK_HEIGHT = 13;
    private final SelectionListener listener;
    private double zoom = 1.0;
    private int scrollTick;

    BossShowTimelineWidget(int x, int y, int width, int height, SelectionListener listener) {
        super(x, y, width, height, Component.translatable("gui.eca.bossshow.editor.timeline.narration"));
        this.listener = listener;
    }

    void zoomBy(double factor) {
        zoom = Math.max(1.0, Math.min(16.0, zoom * factor));
        clampScroll();
    }

    void resetView() {
        zoom = 1.0;
        scrollTick = 0;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        selectAt(mouseX, mouseY);
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        selectAt(mouseX, mouseY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta == 0.0) return false;
        if (Screen.hasShiftDown()) {
            scrollTick += delta > 0 ? -visibleTicks() / 5 : visibleTicks() / 5;
            clampScroll();
        } else {
            zoomBy(delta > 0 ? 1.25 : 0.8);
        }
        return true;
    }

    boolean selectAt(double mouseX, double mouseY) {
        int frameCount = BossShowEditorState.frameCount();
        if (frameCount <= 0 || !isMouseOver(mouseX, mouseY) || mouseX < getX() + LABEL_WIDTH) return false;
        int tick = xToTick(mouseX, frameCount);
        int row = (int) ((mouseY - getY() - RULER_HEIGHT) / trackHeight());
        Track track = row == 1 ? Track.EVENT : row == 2 ? Track.SUBTITLE
            : row == 3 ? Track.EFFECT : Track.CAMERA;
        BossShowEditorState.setPlayhead(tick);
        BossShowEditorState.setSelectedKeyframeFrameIndex(tick);
        if (listener != null) listener.onSelected(track, tick);
        return true;
    }

    private int contentLeft() {
        return getX() + LABEL_WIDTH;
    }

    private int contentWidth() {
        return Math.max(1, getWidth() - LABEL_WIDTH);
    }

    private int visibleTicks() {
        int total = Math.max(1, BossShowEditorState.frameCount() - 1);
        double fitPixelsPerTick = contentWidth() / (double) total;
        double pixelsPerTick = Math.max(1.5, fitPixelsPerTick * zoom);
        return Math.max(1, (int) Math.floor(contentWidth() / pixelsPerTick));
    }

    private double pixelsPerTick() {
        int total = Math.max(1, BossShowEditorState.frameCount() - 1);
        double fitPixelsPerTick = contentWidth() / (double) total;
        return Math.max(1.5, fitPixelsPerTick * zoom);
    }

    private void clampScroll() {
        int total = Math.max(0, BossShowEditorState.frameCount() - 1);
        scrollTick = Math.max(0, Math.min(scrollTick, Math.max(0, total - visibleTicks())));
    }

    private int xToTick(double mouseX, int frameCount) {
        clampScroll();
        double tick = scrollTick + (mouseX - contentLeft()) / pixelsPerTick();
        return Math.max(0, Math.min(frameCount - 1, (int) Math.round(tick)));
    }

    private int tickToX(int tick) {
        return contentLeft() + (int) Math.round((tick - scrollTick) * pixelsPerTick());
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int left = getX();
        int top = getY();
        int right = left + getWidth();
        int bottom = top + getHeight();
        int contentLeft = contentLeft();
        g.fill(left, top, right, bottom, 0xDD0D0F14);
        g.fill(left, top, right, top + RULER_HEIGHT, 0xEE191C25);
        g.fill(left, top + RULER_HEIGHT, contentLeft, bottom, 0xEE161922);

        Font font = Minecraft.getInstance().font;
        int trackHeight = trackHeight();
        drawRuler(g, font, contentLeft, top);
        drawTrack(g, font, Track.CAMERA, Component.translatable("gui.eca.bossshow.editor.track.camera"),
            top + RULER_HEIGHT, trackHeight);
        drawTrack(g, font, Track.EVENT, Component.translatable("gui.eca.bossshow.editor.track.event"),
            top + RULER_HEIGHT + trackHeight, trackHeight);
        drawTrack(g, font, Track.SUBTITLE, Component.translatable("gui.eca.bossshow.editor.track.subtitle"),
            top + RULER_HEIGHT + trackHeight * 2, trackHeight);
        drawTrack(g, font, Track.EFFECT, Component.translatable("gui.eca.bossshow.editor.track.effect"),
            top + RULER_HEIGHT + trackHeight * 3, trackHeight);

        if (BossShowEditorState.hasValidRange()) {
            int inX = tickToX(BossShowEditorState.getInPoint());
            int outX = tickToX(BossShowEditorState.getOutPoint());
            int clipLeft = Math.max(contentLeft, Math.min(inX, outX));
            int clipRight = Math.min(right, Math.max(inX, outX) + 2);
            if (clipRight > clipLeft) g.fill(clipLeft, top, clipRight, bottom, 0x223D9BFF);
        }

        int playheadX = tickToX(BossShowEditorState.getPlayhead());
        if (playheadX >= contentLeft && playheadX <= right) {
            g.fill(playheadX, top, playheadX + 2, bottom, 0xFFFFF2A8);
        }
        drawRangeMarker(g, font, BossShowEditorState.getInPoint(), "I", 0xFF55D68A, -7);
        drawRangeMarker(g, font, BossShowEditorState.getOutPoint(), "O", 0xFFFF6B6B, 3);
        g.renderOutline(left, top, getWidth(), getHeight(), 0xFF454A58);
    }

    private void drawRangeMarker(GuiGraphics g, Font font, int tick, String label, int color, int labelOffset) {
        if (tick < 0 || tick >= BossShowEditorState.frameCount()) return;
        int x = tickToX(tick);
        int left = contentLeft();
        int right = getX() + getWidth();
        if (x < left || x > right) return;
        g.fill(Math.max(left, x - 1), getY(), Math.min(right, x + 2), getY() + getHeight(), color);
        int labelX = Math.max(left + 2, Math.min(x + labelOffset, right - font.width(label) - 2));
        g.drawString(font, label, labelX, getY() + 2, color, false);
    }

    private void drawRuler(GuiGraphics g, Font font, int contentLeft, int top) {
        int total = BossShowEditorState.frameCount();
        if (total <= 0) return;
        int step = rulerStep();
        int first = Math.max(0, (scrollTick / step) * step);
        for (int tick = first; tick < total; tick += step) {
            int x = tickToX(tick);
            if (x < contentLeft || x > getX() + getWidth()) continue;
            g.fill(x, top + 10, x + 1, top + RULER_HEIGHT, 0xFF777C8B);
            g.drawString(font, formatTime(tick), x + 2, top + 2, 0xFF9DA3AC, false);
        }
    }

    private int rulerStep() {
        double pixels = pixelsPerTick();
        if (pixels >= 18) return 1;
        if (pixels >= 7) return 5;
        if (pixels >= 3) return 10;
        return 20;
    }

    private String formatTime(int tick) {
        return String.format("%d:%02d", tick / 1200, (tick / 20) % 60);
    }

    private void drawTrack(GuiGraphics g, Font font, Track track, Component label, int top, int trackHeight) {
        int contentLeft = contentLeft();
        int right = getX() + getWidth();
        g.fill(contentLeft, top, right, top + trackHeight, track == Track.CAMERA ? 0x55262B37 : 0x5520252E);
        g.drawString(font, label, getX() + 6, top + Math.max(2, (trackHeight - font.lineHeight) / 2), 0xFFD0D4DE, false);
        g.fill(contentLeft, top + trackHeight - 1, right, top + trackHeight, 0xFF303541);

        if (track == Track.CAMERA) {
            List<Frame> frames = BossShowEditorState.getFrames();
            for (int i = Math.max(scrollTick, 0); i < frames.size() - 1; i++) {
                int x0 = tickToX(i);
                int x1 = tickToX(i + 1);
                if (x1 < contentLeft) continue;
                if (x0 > right) break;
                int barTop = top + Math.max(3, trackHeight / 2 - 3);
                g.fill(Math.max(contentLeft, x0), barTop, Math.min(right, Math.max(x0 + 2, x1)), barTop + 6, 0xFF4D83D8);
            }
            return;
        }

        if (track == Track.EVENT) {
            for (EventCue cue : BossShowEditorState.getEventCues()) {
                drawCue(g, cue.tick(), cue.eventId(), top, trackHeight, 0xFFE7A63B);
            }
        } else if (track == Track.SUBTITLE) {
            for (SubtitleCue cue : BossShowEditorState.getSubtitleCues()) {
                drawCue(g, cue.tick(), cue.text(), top, trackHeight, 0xFF52C7A5);
            }
        } else {
            for (BossShowEffectCue cue : BossShowEditorState.getEffectCues()) {
                drawCue(g, cue.tick(), cue.effect().isEmpty() ? cue.type() : cue.effect(),
                    top, trackHeight, 0xFFC879FF);
            }
        }
    }

    private void drawCue(GuiGraphics g, int tick, String text, int top, int trackHeight, int color) {
        int x = tickToX(tick);
        int next = tickToX(Math.min(BossShowEditorState.frameCount() - 1, tick + 1));
        if (x < contentLeft() - 8 || x > getX() + getWidth()) return;
        int width = Math.max(7, next - x);
        g.fill(Math.max(contentLeft(), x), top + 3, Math.min(getX() + getWidth(), x + width), top + trackHeight - 3, color);
        if (width > 26 && text != null) {
            g.drawString(Minecraft.getInstance().font,
                Component.literal(text), Math.max(contentLeft(), x) + 3,
                top + Math.max(3, (trackHeight - Minecraft.getInstance().font.lineHeight) / 2), 0xFF101217, false);
        }
    }

    private int trackHeight() {
        return Math.max(MIN_TRACK_HEIGHT, (getHeight() - RULER_HEIGHT) / 4);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
