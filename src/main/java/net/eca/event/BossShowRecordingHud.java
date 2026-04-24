package net.eca.event;

import net.eca.util.bossshow.BossShowDefinition.Marker;
import net.eca.util.bossshow.BossShowEditorState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

//BossShow 录制模式 HUD：顶部时间轴 + sample 数 + marker 标记
@Mod.EventBusSubscriber(modid = "eca", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BossShowRecordingHud {

    private static final double MIN_WINDOW_SECONDS = 30.0;
    private static final int MARGIN_X = 40;
    private static final int BAR_Y = 20;
    private static final int BAR_HEIGHT = 4;

    private BossShowRecordingHud() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!BossShowEditorState.isRecordingMode()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.level == null) return;

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        int sampleCount = BossShowEditorState.sampleCount();
        double elapsedSec = sampleCount / 20.0;

        //=== 时间轴 ===
        double windowSec = Math.max(MIN_WINDOW_SECONDS, Math.ceil(elapsedSec / MIN_WINDOW_SECONDS) * MIN_WINDOW_SECONDS);
        int barLeft = MARGIN_X;
        int barRight = w - MARGIN_X;
        int barWidth = barRight - barLeft;

        //背景
        g.fill(barLeft, BAR_Y, barRight, BAR_Y + BAR_HEIGHT, 0x80FFFFFF);
        //已录制填充（暂停时变暗）
        boolean active = BossShowEditorState.isActivelyRecording();
        int fillColor = active ? 0xFFCC2222 : 0xFF888888;
        int progressX = barLeft + (int) Math.round(barWidth * (elapsedSec / windowSec));
        if (progressX > barRight) progressX = barRight;
        g.fill(barLeft, BAR_Y, progressX, BAR_Y + BAR_HEIGHT, fillColor);

        //marker 竖线
        List<Marker> markers = BossShowEditorState.getMarkers();
        for (int i = 0; i < markers.size(); i++) {
            Marker m = markers.get(i);
            double sec = m.tickOffset() / 20.0;
            if (sec > windowSec) break;
            int markerX = barLeft + (int) Math.round(barWidth * (sec / windowSec));
            g.fill(markerX - 1, BAR_Y - 4, markerX + 1, BAR_Y + BAR_HEIGHT + 4, 0xFFFFFF55);
            String idxStr = String.valueOf(i);
            g.drawString(font, idxStr, markerX - font.width(idxStr) / 2, BAR_Y - 14, 0xFFFFFF55, false);
        }

        //时间标签
        g.drawString(font, "0:00", barLeft, BAR_Y + BAR_HEIGHT + 2, 0xFFAAAAAA, false);
        String rightLabel = formatTime(windowSec);
        g.drawString(font, rightLabel, barRight - font.width(rightLabel), BAR_Y + BAR_HEIGHT + 2, 0xFFAAAAAA, false);

        //中央 REC/PAUSED 提示
        long blink = (System.currentTimeMillis() / 500) % 2;
        Component recDot;
        if (active) {
            recDot = Component.translatable(blink == 0
                ? "gui.eca.bossshow.recording.rec_on"
                : "gui.eca.bossshow.recording.rec_off");
        } else {
            recDot = Component.translatable("gui.eca.bossshow.recording.paused_dot");
        }
        Component line1 = Component.translatable("gui.eca.bossshow.recording.line1",
            recDot, formatTime(elapsedSec), sampleCount, markers.size());
        Component line2 = Component.translatable("gui.eca.bossshow.recording.line2");
        int cy = h / 4;
        g.drawCenteredString(font, line1, w / 2, cy, 0xFFFFFF);
        g.drawCenteredString(font, line2, w / 2, cy + 14, 0xFFAAAAAA);
    }

    private static String formatTime(double seconds) {
        int min = (int) (seconds / 60);
        double rem = seconds - min * 60;
        return String.format("%d:%05.2f", min, rem);
    }
}
