package net.eca.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.eca.util.bossshow.BossShowDefinition.Frame;
import net.eca.util.bossshow.BossShowEditorState;
import net.eca.util.bossshow.BossShowInterpolator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.SkullModelBase;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

//编辑器可视化：锚点光柱 + 摄像机路径折线 + 关键帧头颅
@Mod.EventBusSubscriber(modid = "eca", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BossShowAnchorRenderer {

    private static final float BEAM_HEIGHT = 6.0f;
    private static final float BEAM_HALF = 0.06f;
    private static final float BASE_HALF = 0.4f;

    private static final float PATH_R = 0.2f, PATH_G = 0.9f, PATH_B = 0.4f, PATH_A = 0.8f;

    //头颅半透明度
    private static final float HEAD_ALPHA = 0.5f;
    //序号文字距头颅落点的高度（头颅高 0.5 格，再往上留一点）
    private static final double LABEL_Y_OFFSET = 0.7;

    //头颅模型与 RenderType 懒加载缓存：资源加载完成后固定不变
    private static SkullModelBase playerHeadModel = null;
    private static RenderType playerHeadRenderType = null;

    private BossShowAnchorRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!BossShowEditorState.isActive()) return;
        if (!BossShowEditorState.hasAnchor()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        double ax = BossShowEditorState.getAnchorX();
        double ay = BossShowEditorState.getAnchorY();
        double az = BossShowEditorState.getAnchorZ();
        float anchorYaw = BossShowEditorState.getAnchorYawDeg();

        Camera cam = event.getCamera();
        Vec3 camPos = cam.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        pose.pushPose();
        pose.translate(-camPos.x, -camPos.y, -camPos.z);

        VertexConsumer vc = buffer.getBuffer(RenderType.lines());

        //锚点光柱
        AABB beam = new AABB(
            ax - BEAM_HALF, ay, az - BEAM_HALF,
            ax + BEAM_HALF, ay + BEAM_HEIGHT, az + BEAM_HALF
        );
        LevelRenderer.renderLineBox(pose, vc, beam, 0.2f, 1.0f, 0.2f, 1.0f);

        AABB base = new AABB(
            ax - BASE_HALF, ay, az - BASE_HALF,
            ax + BASE_HALF, ay + 0.02, az + BASE_HALF
        );
        LevelRenderer.renderLineBox(pose, vc, base, 0.2f, 1.0f, 0.2f, 1.0f);

        //摄像机路径折线
        List<Frame> frames = BossShowEditorState.getFrames();
        if (frames.size() >= 2) {
            renderCameraPath(pose, vc, frames, ax, ay, az, anchorYaw);
        }

        buffer.endBatch(RenderType.lines());

        //关键帧头颅 + 顺序序号
        List<Integer> kfIndices = BossShowEditorState.getKeyframeFrameIndices();
        if (!kfIndices.isEmpty()) {
            ensureHeadModels(mc);
            if (playerHeadModel != null) {
                Quaternionf camRot = cam.rotation();
                for (int i = 0; i < kfIndices.size(); i++) {
                    int frameIdx = kfIndices.get(i);
                    if (frameIdx < frames.size()) {
                        renderKeyframeHead(pose, buffer, frames.get(frameIdx),
                            ax, ay, az, anchorYaw, i + 1, camRot, mc.font);
                    }
                }
                //显式 flush 头颅(translucent)与序号文字
                buffer.endBatch();
            }
        }

        pose.popPose();
    }

    private static void renderCameraPath(PoseStack pose, VertexConsumer vc,
                                         List<Frame> frames,
                                         double ax, double ay, double az, float anchorYaw) {
        List<Vec3> vertices = new ArrayList<>();

        //用实际世界坐标连线，仅对几乎重合的相邻点去重，避免静止段产生零长度线段
        for (Frame f : frames) {
            Vec3 wp = BossShowInterpolator.anchorToWorld(f.dx(), f.dy(), f.dz(), ax, ay, az, anchorYaw);
            if (vertices.isEmpty() || vertices.get(vertices.size() - 1).distanceToSqr(wp) > 1.0e-4) {
                vertices.add(wp);
            }
        }

        if (vertices.size() < 2) return;

        var poseMatrix = pose.last().pose();
        var normalMatrix = pose.last().normal();

        for (int i = 0; i < vertices.size() - 1; i++) {
            Vec3 p1 = vertices.get(i);
            Vec3 p2 = vertices.get(i + 1);
            Vec3 dir = p2.subtract(p1).normalize();
            float nx = (float) dir.x;
            float ny = (float) dir.y;
            float nz = (float) dir.z;
            vc.vertex(poseMatrix, (float) p1.x, (float) p1.y, (float) p1.z)
                .color(PATH_R, PATH_G, PATH_B, PATH_A)
                .normal(normalMatrix, nx, ny, nz)
                .endVertex();
            vc.vertex(poseMatrix, (float) p2.x, (float) p2.y, (float) p2.z)
                .color(PATH_R, PATH_G, PATH_B, PATH_A)
                .normal(normalMatrix, nx, ny, nz)
                .endVertex();
        }
    }

    private static void renderKeyframeHead(PoseStack pose, MultiBufferSource.BufferSource buffer,
                                           Frame frame,
                                           double ax, double ay, double az, float anchorYaw,
                                           int ordinal, Quaternionf camRot, Font font) {
        Vec3 wp = BossShowInterpolator.anchorToWorld(frame.dx(), frame.dy(), frame.dz(), ax, ay, az, anchorYaw);
        //复刻 SkullBlockRenderer.renderSkull 的变换，但走 translucent 渲染类型以支持半透明
        //其内部 scale(-1,-1,1) 等价绕 Z 轴 180° 翻转，故 yaw 加 180° 抵消
        float worldYaw = frame.yaw() + anchorYaw + 180f;

        pose.pushPose();
        pose.translate(wp.x, wp.y, wp.z);
        pose.scale(-1.0f, -1.0f, 1.0f);
        playerHeadModel.setupAnim(0f, worldYaw, 0f);
        playerHeadModel.renderToBuffer(pose, buffer.getBuffer(playerHeadRenderType),
            LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, HEAD_ALPHA);
        pose.popPose();

        renderHeadLabel(pose, buffer, wp, ordinal, camRot, font);
    }

    //在头颅上方渲染朝向摄像机的序号（公告板）
    private static void renderHeadLabel(PoseStack pose, MultiBufferSource.BufferSource buffer,
                                        Vec3 wp, int ordinal, Quaternionf camRot, Font font) {
        String label = Integer.toString(ordinal);
        pose.pushPose();
        pose.translate(wp.x, wp.y + LABEL_Y_OFFSET, wp.z);
        pose.mulPose(camRot);
        pose.scale(-0.025f, -0.025f, 0.025f);
        Matrix4f mat = pose.last().pose();
        float x = -font.width(label) / 2f;
        font.drawInBatch(label, x, 0f, 0xFFFFFFFF, true, mat, buffer,
            Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }

    private static void ensureHeadModels(Minecraft mc) {
        if (playerHeadModel != null) return;
        Map<SkullBlock.Type, SkullModelBase> models =
            SkullBlockRenderer.createSkullRenderers(mc.getEntityModels());
        playerHeadModel = models.get(SkullBlock.Types.PLAYER);
        //translucent 渲染类型支持 alpha 混合，用 vanilla 头颅同款默认皮肤纹理
        playerHeadRenderType = RenderType.entityTranslucent(DefaultPlayerSkin.getDefaultSkin());
    }
}
