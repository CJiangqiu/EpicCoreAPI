package net.eca.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.eca.client.render.ShaderMaskPass;
import net.eca.client.render.ShaderMaskRenderQueue;
import net.eca.client.render.SpriteBatchingVertexConsumer;
import net.eca.util.block_extension.BlockExtension;
import net.eca.util.block_extension.BlockExtensionManager;
import net.eca.util.block_extension.BlockExtensionSafeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.FallingBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(FallingBlockRenderer.class)
public class FallingBlockRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void eca$renderBlockExtension(FallingBlockEntity entity, float yaw, float partialTick,
                                          PoseStack poseStack, MultiBufferSource bufferSource,
                                          int packedLight, CallbackInfo ci) {
        BlockState state = entity.getBlockState();
        BlockExtension extension = BlockExtensionManager.getExtension(state.getBlock());
        if (extension == null || state.getRenderShape() != RenderShape.MODEL
            || state == entity.level().getBlockState(entity.blockPosition())
            || !BlockExtensionSafeAccess.shouldRender(extension, state, entity.level(), entity.blockPosition())) {
            return;
        }
        List<ShaderMaskPass> passes = extension.getBlockShaderPasses();
        if (passes == null || passes.isEmpty()) return;

        BlockPos renderPos = BlockPos.containing(entity.getX(), entity.getBoundingBox().maxY, entity.getZ());
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getBlockRenderer().getBlockModel(state);
        boolean fullBright = BlockExtensionSafeAccess.isGlow(extension);
        for (ShaderMaskPass pass : passes) {
            if (pass == null || pass.alpha() <= 0.0f) continue;
            SpriteBatchingVertexConsumer consumer =
                new SpriteBatchingVertexConsumer(pass.renderType().format(), fullBright);
            poseStack.pushPose();
            poseStack.translate(-0.5, 0.0, -0.5);
            minecraft.getBlockRenderer().getModelRenderer().tesselateBlock(entity.level(), model, state, renderPos,
                poseStack, consumer, false, RandomSource.create(), state.getSeed(entity.getStartPos()), 0,
                ModelData.EMPTY, null);
            poseStack.popPose();
            consumer.finish(batch -> ShaderMaskRenderQueue.enqueue(pass, batch.builder(),
                batch.builder().end(), batch.uvTransform()));
        }
    }
}
