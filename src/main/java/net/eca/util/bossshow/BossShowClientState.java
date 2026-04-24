package net.eca.util.bossshow;

import net.eca.client.gui.BossShowEditorHomeScreen;
import net.eca.config.EcaConfiguration;
import net.eca.network.BossShowSkipPacket;
import net.eca.network.BossShowStartPacket;
import net.eca.network.BossShowStopPacket;
import net.eca.network.BossShowSubtitlePacket;
import net.eca.network.NetworkHandler;
import net.eca.util.bossshow.BossShowDefinition.Marker;
import net.eca.util.bossshow.BossShowDefinition.Sample;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.UUID;

/**
 * Client-side state of the currently-playing BossShow cutscene.
 * Holds the full sample list so we can interpolate the camera each frame
 * without per-tick network traffic.
 */
@OnlyIn(Dist.CLIENT)
public final class BossShowClientState {

    private static volatile boolean active = false;
    private static ResourceLocation cutsceneId;
    private static UUID targetUuid;
    private static EntityType<?> targetType;
    private static double anchorX, anchorY, anchorZ;
    private static float anchorYaw;
    private static List<Sample> samples;
    private static List<Marker> markers;
    private static boolean cinematic;
    private static int tickCounter;
    private static final BossShowPose POSE = new BossShowPose();

    //字幕状态
    private static Component subtitleComponent = null;
    private static long subtitleSetTimeMs = 0L;
    private static int subtitleSetTickCounter = 0;
    private static final long SUBTITLE_FADE_IN_MS = 300L;

    private BossShowClientState() {}

    public static void onServerStart(BossShowStartPacket msg) {
        cutsceneId = msg.cutsceneId();
        targetUuid = msg.targetUuid();
        targetType = msg.targetTypeId() != null ? BuiltInRegistries.ENTITY_TYPE.get(msg.targetTypeId()) : null;
        anchorX = msg.anchorX();
        anchorY = msg.anchorY();
        anchorZ = msg.anchorZ();
        anchorYaw = msg.anchorYaw();
        samples = msg.samples();
        markers = msg.markers();
        cinematic = msg.cinematic();
        tickCounter = 0;
        active = true;
    }

    public static void onSubtitle(BossShowSubtitlePacket msg) {
        String text = msg.text();
        if (text == null || text.isEmpty()) {
            subtitleComponent = null;
            return;
        }
        //整合包 config override 优先：命中当前 locale 直接用，不命中回退 I18n（由 vanilla 做 mod(current)→mod(en_us) 兜底）
        String overridden = BossShowLangOverride.lookup(text);
        if (overridden != null) {
            subtitleComponent = Component.literal(overridden);
        } else {
            //先当翻译键查，查不到（返回值 == 原文）则当字面文本
            String translated = I18n.get(text);
            if (translated.equals(text) && text.contains(".")) {
                subtitleComponent = Component.translatable(text);
            } else if (!translated.equals(text)) {
                subtitleComponent = Component.translatable(text);
            } else {
                subtitleComponent = Component.literal(text);
            }
        }
        subtitleSetTimeMs = System.currentTimeMillis();
        subtitleSetTickCounter = tickCounter;
    }

    public static void onServerStop(BossShowStopPacket msg) {
        active = false;
        cutsceneId = null;
        targetUuid = null;
        targetType = null;
        samples = null;
        markers = null;
        tickCounter = 0;
        subtitleComponent = null;
        //如果 editor session 还活着（玩家通过 Home Play 触发的试播），自动回到 Home
        if (BossShowEditorState.isActive()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen == null) {
                mc.setScreen(new BossShowEditorHomeScreen());
            }
        }
    }

    public static void requestSkip() {
        if (!active) return;
        NetworkHandler.sendToServer(new BossShowSkipPacket());
        active = false;
    }

    public static void tick() {
        if (!active) return;
        tickCounter++;
    }

    public static boolean isActive() {
        return active;
    }

    public static BossShowPose computePoseForRender(float partialTick) {
        if (!active || samples == null || samples.isEmpty()) {
            POSE.x = POSE.y = POSE.z = 0;
            POSE.yaw = POSE.pitch = 0;
            POSE.cinematic = false;
            return POSE;
        }

        double cursor = tickCounter + partialTick;
        BossShowInterpolator.computePose(samples, markers, cinematic, cursor,
            anchorX, anchorY, anchorZ, anchorYaw, POSE);
        return POSE;
    }

    public static ResourceLocation currentId() { return cutsceneId; }

    public static boolean isCinematic() { return cinematic; }

    public static Component getSubtitle() {
        //超过 max_subtitle_duration_ticks 后自动清空，避免一句字幕卡屏
        if (subtitleComponent != null) {
            int maxTicks = EcaConfiguration.getBossShowMaxSubtitleDurationTicksSafely();
            if (tickCounter - subtitleSetTickCounter >= maxTicks) {
                subtitleComponent = null;
            }
        }
        return subtitleComponent;
    }

    public static float getSubtitleAlpha() {
        if (subtitleComponent == null) return 0f;
        long elapsed = System.currentTimeMillis() - subtitleSetTimeMs;
        if (elapsed >= SUBTITLE_FADE_IN_MS) return 1f;
        return (float) elapsed / SUBTITLE_FADE_IN_MS;
    }
}
