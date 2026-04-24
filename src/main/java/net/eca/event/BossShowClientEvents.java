package net.eca.event;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.eca.util.bossshow.BossShowClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

//客户端事件处理：tick 推进、HUD 隐藏、Letterbox、PauseScreen 拦截
@Mod.EventBusSubscriber(modid = "eca", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BossShowClientEvents {

    private BossShowClientEvents() {}

    //每 client tick 推进
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (Minecraft.getInstance().isPaused()) return;
        BossShowClientState.tick();
    }

    //播放时隐藏 HUD，并在 cancel 前绘制 letterbox 黑边
    //（必须在 Pre 里画：cancel Pre 后 ForgeGui.render 直接 return，Post 不会触发）
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        if (!BossShowClientState.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        GuiGraphics gui = event.getGuiGraphics();

        if (BossShowClientState.isCinematic()) {
            int bar = (int) (height * 0.12f);
            if (bar > 0) {
                PoseStack pose = gui.pose();
                pose.pushPose();
                RenderSystem.enableBlend();
                gui.fill(0, 0, width, bar, 0xFF000000);
                gui.fill(0, height - bar, width, height, 0xFF000000);
                RenderSystem.disableBlend();
                pose.popPose();
            }
        }

        renderSubtitle(gui, mc.font, width, height);

        event.setCanceled(true);
    }

    private static void renderSubtitle(GuiGraphics gui, Font font, int width, int height) {
        Component subtitle = BossShowClientState.getSubtitle();
        if (subtitle == null) return;
        float alpha = BossShowClientState.getSubtitleAlpha();
        if (alpha <= 0f) return;

        int a = (int) (alpha * 255) & 0xFF;
        int color = (a << 24) | 0xFFFFFF;
        //字幕位于下方黑边上沿内侧
        int barHeight = (int) (height * 0.12f);
        int y = height - barHeight + (barHeight - font.lineHeight) / 2;
        gui.drawCenteredString(font, subtitle, width / 2, y, color);
    }

    //相机角度最终锁定（防止其它 mod 的 ViewportEvent 订阅者覆写我们）
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!BossShowClientState.isActive()) return;
        var pose = BossShowClientState.computePoseForRender((float) event.getPartialTick());
        event.setYaw(pose.yaw);
        event.setPitch(pose.pitch);
        event.setRoll(0f);
    }

    //拦截 PauseScreen 打开：直接转为 skip
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!BossShowClientState.isActive()) return;
        Screen next = event.getNewScreen();
        if (next instanceof PauseScreen) {
            event.setCanceled(true);
            BossShowClientState.requestSkip();
        }
    }
}
