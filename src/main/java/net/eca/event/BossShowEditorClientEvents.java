package net.eca.event;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.eca.client.BossShowKeyBindings;
import net.eca.client.gui.BossShowEditorHomeScreen;
import net.eca.client.gui.BossShowEditorScreen;
import net.eca.config.EcaConfiguration;
import net.eca.network.BossShowPlaySelectionPacket;
import net.eca.network.NetworkHandler;
import net.eca.util.bossshow.BossShowDefinition;
import net.eca.util.bossshow.BossShowEditorState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

//BossShow 编辑器：实体选择 + 录制状态机
@Mod.EventBusSubscriber(modid = "eca", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BossShowEditorClientEvents {

    private static Entity cachedHovered = null;

    //通用 toast（recording 提示共用）
    private static long toastUntilMillis = 0L;
    private static Component toastText = Component.empty();

    private BossShowEditorClientEvents() {}

    public static void showToast(Component text, long durationMs) {
        toastText = text;
        toastUntilMillis = System.currentTimeMillis() + durationMs;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        //=== 录制相关快捷键（无 screen 时）===
        if (mc.screen == null && BossShowEditorState.isActive() && BossShowEditorState.hasAnchor()) {
            //J = 开始/恢复
            while (BossShowKeyBindings.REC_START.consumeClick()) {
                if (mc.level != null) {
                    BossShowEditorState.startOrResumeRecording(mc.level.getGameTime());
                    showToast(Component.translatable("gui.eca.bossshow.recording.started"), 1000L);
                }
            }
            //I = 暂停
            while (BossShowKeyBindings.REC_PAUSE.consumeClick()) {
                if (BossShowEditorState.isActivelyRecording()) {
                    BossShowEditorState.pauseRecording();
                    showToast(Component.translatable("gui.eca.bossshow.recording.paused"), 1000L);
                }
            }
            //K = 添加 marker（录制和暂停都可）
            while (BossShowKeyBindings.ADD_MARKER.consumeClick()) {
                if (BossShowEditorState.isRecordingMode()) {
                    int t = BossShowEditorState.addMarkerAtCurrentTick();
                    if (t >= 0) {
                        showToast(Component.translatable("gui.eca.bossshow.recording.marker_added", t), 1000L);
                    }
                }
            }
        } else {
            //drain 掉避免延后误触发
            while (BossShowKeyBindings.REC_START.consumeClick()) {}
            while (BossShowKeyBindings.REC_PAUSE.consumeClick()) {}
            while (BossShowKeyBindings.ADD_MARKER.consumeClick()) {}
        }

        //=== 每 tick 采样（仅 RECORDING 状态）===
        if (mc.screen == null
            && BossShowEditorState.isActivelyRecording()
            && BossShowEditorState.hasAnchor()
            && mc.gameRenderer != null) {
            Camera cam = mc.gameRenderer.getMainCamera();
            BossShowEditorState.captureSampleFromCamera(
                cam.getPosition().x, cam.getPosition().y, cam.getPosition().z,
                cam.getYRot(), cam.getXRot()
            );
        }

        //=== selection 模式 raycast ===
        if (!BossShowEditorState.isAnySelectionMode()) {
            cachedHovered = null;
            return;
        }
        if (mc.level == null || mc.player == null || mc.screen != null) {
            BossShowEditorState.setHoveredEntityUuid(null);
            cachedHovered = null;
            return;
        }
        EntityHitResult hit = raycastEntity(mc.player, EcaConfiguration.getBossShowEntitySelectionRangeSafely());
        if (hit != null && hit.getEntity() instanceof LivingEntity le && le.isAlive()) {
            BossShowEditorState.setHoveredEntityUuid(le.getUUID());
            cachedHovered = le;
        } else {
            BossShowEditorState.setHoveredEntityUuid(null);
            cachedHovered = null;
        }
    }

    //ENTER 完成录制（保存）
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (!BossShowEditorState.isRecordingMode()) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        if (event.getKey() == GLFW.GLFW_KEY_ENTER || event.getKey() == GLFW.GLFW_KEY_KP_ENTER) {
            finishRecording(true);
        }
    }

    public static void finishRecording(boolean keep) {
        if (!BossShowEditorState.isRecordingMode()) return;
        if (keep) {
            BossShowEditorState.finishRecording();
        } else {
            BossShowEditorState.discardRecording();
        }
        Minecraft.getInstance().setScreen(new BossShowEditorScreen());
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!BossShowEditorState.isAnySelectionMode()) return;
        Entity target = cachedHovered;
        if (target == null || target.isRemoved()) return;

        Minecraft mc = Minecraft.getInstance();
        Camera cam = event.getCamera();
        Vec3 camPos = cam.getPosition();

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camPos.x, -camPos.y, -camPos.z);

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(RenderType.lines());
        AABB box = target.getBoundingBox().inflate(0.02);
        LevelRenderer.renderLineBox(pose, vc, box, 0.2f, 1.0f, 0.2f, 1.0f);
        buffer.endBatch(RenderType.lines());

        pose.popPose();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!BossShowEditorState.isAnySelectionMode()) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;

        event.setCanceled(true);

        //=== PLAY 选择模式：必须有 hovered entity，把目标实体送到服务端启动播放 ===
        if (BossShowEditorState.isPlaySelectionMode()) {
            UUID uuid = BossShowEditorState.getHoveredEntityUuid();
            if (uuid == null || cachedHovered == null || cachedHovered.isRemoved()) return;
            ResourceLocation defId = BossShowEditorState.getPendingPlayDefId();
            if (defId == null) {
                BossShowEditorState.exitSelectionMode();
                cachedHovered = null;
                mc.setScreen(new BossShowEditorHomeScreen());
                return;
            }
            NetworkHandler.sendToServer(
                new BossShowPlaySelectionPacket(defId, cachedHovered.getUUID()));
            BossShowEditorState.exitSelectionMode();
            cachedHovered = null;
            mc.setScreen(null);
            return;
        }

        //=== CREATE_NEW 选择模式 ===
        //右键 hovered 实体 → 绑定该实体（targetType=type, anchor=entity pos）
        //右键空地 → 不绑定实体（targetType=null, anchor=玩家当前位置 / 玩家 yaw）
        LocalPlayer player = mc.player;
        if (player == null) return;

        Entity target = cachedHovered;
        boolean hasTarget = target != null && !target.isRemoved();

        EntityType<?> type;
        UUID anchorUuid;
        double ax, ay, az;
        float anchorYaw;
        if (hasTarget) {
            type = target.getType();
            anchorUuid = target.getUUID();
            ax = target.getX();
            ay = target.getY();
            az = target.getZ();
            //anchor yaw = 实体→玩家连线方向，让首帧"自然"面向玩家
            double adx = player.getX() - ax;
            double adz = player.getZ() - az;
            anchorYaw = (float)(Math.atan2(adz, adx) * (180.0 / Math.PI)) - 90.0f;
        } else {
            type = null;
            anchorUuid = null;
            ax = player.getX();
            ay = player.getY();
            az = player.getZ();
            anchorYaw = player.getYRot();
        }

        ResourceLocation id = BossShowEditorState.generateAutoId(type);
        BossShowDefinition blank = BossShowEditorState.createBlank(id, type);
        BossShowEditorState.exitSelectionMode();
        cachedHovered = null;
        BossShowEditorState.enter(blank);
        BossShowEditorState.setAnchor(anchorUuid, ax, ay, az, anchorYaw);
        //跳过 EditorScreen 直接进入录制 standby（HUD 显示 PAUSED，等 J 触发采样）
        if (mc.level != null) {
            BossShowEditorState.enterRecordingStandby(mc.level.getGameTime());
        }
        mc.setScreen(null);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof PauseScreen)) return;

        if (BossShowEditorState.isRecordingMode()) {
            event.setCanceled(true);
            finishRecording(false);
            return;
        }
        if (BossShowEditorState.isAnySelectionMode()) {
            event.setCanceled(true);
            BossShowEditorState.exitSelectionMode();
            cachedHovered = null;
            Minecraft.getInstance().setScreen(new BossShowEditorHomeScreen());
            return;
        }
        //在编辑器 session 活着且无 screen 状态下按 ESC 也回到 Home
        if (BossShowEditorState.isActive()) {
            event.setCanceled(true);
            Minecraft.getInstance().setScreen(new BossShowEditorHomeScreen());
        }
    }

    //通用 toast 渲染
    @SubscribeEvent
    public static void onRenderGuiToast(RenderGuiEvent.Post event) {
        if (System.currentTimeMillis() >= toastUntilMillis) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        event.getGuiGraphics().drawCenteredString(mc.font, toastText, w / 2, h - 60, 0xFFFFFF);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!BossShowEditorState.isAnySelectionMode()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;

        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        GuiGraphics g = event.getGuiGraphics();

        boolean targeted = BossShowEditorState.getHoveredEntityUuid() != null;
        boolean isPlay = BossShowEditorState.isPlaySelectionMode();

        Component line1;
        if (isPlay) {
            ResourceLocation defId = BossShowEditorState.getPendingPlayDefId();
            String defStr = defId != null ? defId.toString() : "?";
            line1 = Component.translatable(targeted
                ? "gui.eca.bossshow.play_selection.targeted"
                : "gui.eca.bossshow.play_selection.aim", defStr);
        } else {
            line1 = Component.translatable(targeted
                ? "gui.eca.bossshow.selection.targeted"
                : "gui.eca.bossshow.selection.aim");
        }
        Component line2 = Component.translatable(isPlay
            ? "gui.eca.bossshow.play_selection.hint"
            : "gui.eca.bossshow.selection.hint");

        int y = h / 4;
        g.drawCenteredString(mc.font, line1, w / 2, y, 0xFFFFFF);
        if (targeted && cachedHovered != null) {
            Component hint = Component.literal("§7").append(cachedHovered.getType().getDescription());
            g.drawCenteredString(mc.font, hint, w / 2, y + 12, 0xCCCCCC);
        }
        g.drawCenteredString(mc.font, line2, w / 2, y + 26, 0xAAAAAA);
    }

    private static EntityHitResult raycastEntity(LocalPlayer player, double reach) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getViewVector(1.0f);
        Vec3 end = eye.add(look.x * reach, look.y * reach, look.z * reach);
        AABB scanBox = player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0, 1.0, 1.0);
        return ProjectileUtil.getEntityHitResult(
            player.level(),
            player,
            eye,
            end,
            scanBox,
            e -> e instanceof LivingEntity && e.isAlive() && e != player
        );
    }
}
