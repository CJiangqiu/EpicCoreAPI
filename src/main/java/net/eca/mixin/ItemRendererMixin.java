package net.eca.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.eca.client.render.ShaderMaskPass;
import net.eca.client.render.ShaderMaskRenderQueue;
import net.eca.client.render.SpriteBatchingVertexConsumer;
import net.eca.util.item_extension.ItemExtension;
import net.eca.util.item_extension.ItemExtensionManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@OnlyIn(Dist.CLIENT)
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V",
                     shift = At.Shift.BEFORE))
    private void eca$renderItemExtension(ItemStack stack, ItemDisplayContext displayContext, boolean leftHand,
                                         PoseStack poseStack, MultiBufferSource bufferSource,
                                         int combinedLight, int combinedOverlay, BakedModel model,
                                         CallbackInfo ci) {
        if (stack.isEmpty()) return;
        ItemExtension extension = ItemExtensionManager.getExtension(stack.getItem());
        if (extension == null || !extension.enabled() || !extension.shouldRender(stack)) return;

        List<ShaderMaskPass> passes = extension.getShaderPasses();
        if (passes == null || passes.isEmpty()) return;
        if (bufferSource instanceof MultiBufferSource.BufferSource source) {
            source.endBatch();
        }

        ItemRenderer renderer = (ItemRenderer) (Object) this;
        boolean queued = eca$isWorldContext(displayContext);
        for (ShaderMaskPass pass : passes) {
            if (pass == null || pass.alpha() <= 0.0f) continue;
            SpriteBatchingVertexConsumer consumer = new SpriteBatchingVertexConsumer(pass.renderType().format());
            renderer.renderModelLists(model, stack, combinedLight, combinedOverlay, poseStack, consumer);
            consumer.finish(batch -> {
                if (queued) {
                    ShaderMaskRenderQueue.enqueue(pass, batch.builder(), batch.builder().end(),
                        batch.uvTransform());
                } else {
                    ShaderMaskRenderQueue.drawNow(pass, batch.builder(), batch.builder().end(),
                        batch.uvTransform());
                }
            });
        }
    }

    private static boolean eca$isWorldContext(ItemDisplayContext context) {
        return switch (context) {
            case GROUND, FIXED, HEAD,
                 FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND,
                 THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> true;
            default -> false;
        };
    }
}
