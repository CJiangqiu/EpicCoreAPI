package net.eca.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.eca.util.bossshow.BossShowEditorState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

//Anchor 位置的世界空间绿色光柱，告诉作者"录制锚点在这里"
//不依赖任何实体；anchor 是否绑定实体都会渲染
@Mod.EventBusSubscriber(modid = "eca", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BossShowAnchorRenderer {

    private static final float BEAM_HEIGHT = 6.0f;
    private static final float BEAM_HALF = 0.06f;
    private static final float BASE_HALF = 0.4f;

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

        Camera cam = event.getCamera();
        Vec3 camPos = cam.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        pose.pushPose();
        pose.translate(-camPos.x, -camPos.y, -camPos.z);
        VertexConsumer vc = buffer.getBuffer(RenderType.lines());

        //垂直光柱：细长方体
        AABB beam = new AABB(
            ax - BEAM_HALF, ay, az - BEAM_HALF,
            ax + BEAM_HALF, ay + BEAM_HEIGHT, az + BEAM_HALF
        );
        LevelRenderer.renderLineBox(pose, vc, beam, 0.2f, 1.0f, 0.2f, 1.0f);

        //地面方框：标识锚点 X/Z 中心
        AABB base = new AABB(
            ax - BASE_HALF, ay, az - BASE_HALF,
            ax + BASE_HALF, ay + 0.02, az + BASE_HALF
        );
        LevelRenderer.renderLineBox(pose, vc, base, 0.2f, 1.0f, 0.2f, 1.0f);

        buffer.endBatch(RenderType.lines());
        pose.popPose();
    }
}
