package net.eca.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.eca.client.render.ItemLayerRenderQueue;
import net.eca.client.render.shader.EcaShaderInstance;
import net.eca.util.item_extension.ItemExtension;
import net.eca.util.item_extension.ItemExtensionManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

@OnlyIn(Dist.CLIENT)
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

    // 缓存每个 BakedModel 的 UV 包围盒：{uMin, vMin, uScale, vScale}
    private static final Map<BakedModel, float[]> ECA$UV_BOUNDS_CACHE = new WeakHashMap<>();
    private static final RandomSource ECA$SHARED_RNG = RandomSource.create();

    // 在 popPose() 之前注入：此时 vanilla 已完成基础模型渲染，poseStack 仍保留 display context 变换
    @Inject(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V",
                     shift = At.Shift.BEFORE))
    private void eca$renderItemExtension(ItemStack stack, ItemDisplayContext displayContext, boolean leftHand,
                                          PoseStack poseStack, MultiBufferSource bufferSource,
                                          int combinedLight, int combinedOverlay, BakedModel model,
                                          CallbackInfo ci) {
        if (stack.isEmpty()) {
            return;
        }
        ItemExtension extension = ItemExtensionManager.getExtension(stack.getItem());
        if (extension == null || !extension.enabled() || !extension.shouldRender(stack)) {
            return;
        }
        RenderType extType = extension.getRenderType();
        if (extType == null) {
            return;
        }

        // 先刷新基础模型的缓冲，确保原版渲染已完成
        if (bufferSource instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch();
        }

        // 计算该 BakedModel 的 UV 包围盒
        float[] bounds = eca$computeUvBounds(model);

        /*
         * 计算本物品生效的 ColorKey：指定了就按目标色 + 容差做颜色过滤；未指定时仍按物品贴图 alpha 做蒙版，使叠加效果贴合物品形状而非铺满整个 quad，
         * 取颜色黑、容差 2.0（> 最大 RGB 距离 √3≈1.73），因此 shader 只会 discard 透明像素，不做颜色过滤。
         */
        float ckR, ckG, ckB, ckTolerance;
        float[] colorKey = extension.getColorKey();
        if (colorKey != null && colorKey.length >= 3) {
            ckR = colorKey[0];
            ckG = colorKey[1];
            ckB = colorKey[2];
            ckTolerance = extension.getColorKeyTolerance();
        } else {
            ckR = 0.0f;
            ckG = 0.0f;
            ckB = 0.0f;
            ckTolerance = 2.0f;
        }

        ItemRenderer itemRenderer = (ItemRenderer) (Object) this;
        boolean worldContext = eca$isWorldContext(displayContext);

        if (worldContext) {
            /*
             * 世界 context 的物品批次会在 ItemRenderer 返回后才提交；ColorKey 是共享状态，必须随顶点数据一起入队，
             * 在真正绘制前恢复，避免掉落物/FIXED/手持物品拿到已清空的 ColorKey。
             */
            BufferBuilder builder = ItemLayerRenderQueue.acquireBuilder();
            builder.begin(VertexFormat.Mode.QUADS, extType.format());
            itemRenderer.renderModelLists(model, stack, combinedLight, combinedOverlay, poseStack, builder);
            ItemLayerRenderQueue.enqueue(extType, builder, builder.end(),
                ckR, ckG, ckB, ckTolerance, bounds[0], bounds[1], bounds[2], bounds[3]);
            return;
        }

        // 无光影或 GUI：注入共享状态后原位再绘制一次同一个 BakedModel 的 quad 列表
        EcaShaderInstance.setLocalUvBounds(bounds[0], bounds[1], bounds[2], bounds[3]);
        EcaShaderInstance.setColorKey(ckR, ckG, ckB, ckTolerance);

        itemRenderer.renderModelLists(model, stack, combinedLight, combinedOverlay,
                                       poseStack, bufferSource.getBuffer(extType));

        // 立即 flush 扩展缓冲，使 shader 的 applyUniforms 在 ColorKey/LocalUvBounds 仍有效时执行
        if (bufferSource instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch();
        }

        EcaShaderInstance.clearColorKey();
        EcaShaderInstance.clearLocalUvBounds();
    }

    // 判定该展示 context 是否在世界中渲染（走光影 G-buffer），GUI/NONE 不算
    private static boolean eca$isWorldContext(ItemDisplayContext ctx) {
        return switch (ctx) {
            case GROUND, FIXED, HEAD,
                 FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND,
                 THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> true;
            default -> false;
        };
    }

    // 遍历 BakedModel 所有 quad 的 UV0，求得图集内该物品贴图的最小/最大 UV
    // 返回: {uMin, vMin, 1/(uMax-uMin), 1/(vMax-vMin)}
    private static float[] eca$computeUvBounds(BakedModel model) {
        float[] cached = ECA$UV_BOUNDS_CACHE.get(model);
        if (cached != null) {
            return cached;
        }

        float uMin = Float.POSITIVE_INFINITY;
        float vMin = Float.POSITIVE_INFINITY;
        float uMax = Float.NEGATIVE_INFINITY;
        float vMax = Float.NEGATIVE_INFINITY;

        int stride = DefaultVertexFormat.BLOCK.getIntegerSize(); // 8 ints per vertex
        int uvOffset = 4; // position(3) + color(1) = 4 ints before UV0

        // 通用方向 + 6 个方向各取一次
        ECA$SHARED_RNG.setSeed(42L);
        List<BakedQuad> generic = model.getQuads(null, null, ECA$SHARED_RNG);
        for (BakedQuad q : generic) {
            int[] vertices = q.getVertices();
            for (int v = 0; v < 4; v++) {
                int base = v * stride + uvOffset;
                if (base + 1 >= vertices.length) continue;
                float u = Float.intBitsToFloat(vertices[base]);
                float vc = Float.intBitsToFloat(vertices[base + 1]);
                if (u < uMin) uMin = u;
                if (u > uMax) uMax = u;
                if (vc < vMin) vMin = vc;
                if (vc > vMax) vMax = vc;
            }
        }
        for (Direction dir : Direction.values()) {
            ECA$SHARED_RNG.setSeed(42L);
            List<BakedQuad> sided = model.getQuads(null, dir, ECA$SHARED_RNG);
            for (BakedQuad q : sided) {
                int[] vertices = q.getVertices();
                for (int v = 0; v < 4; v++) {
                    int base = v * stride + uvOffset;
                    if (base + 1 >= vertices.length) continue;
                    float u = Float.intBitsToFloat(vertices[base]);
                    float vc = Float.intBitsToFloat(vertices[base + 1]);
                    if (u < uMin) uMin = u;
                    if (u > uMax) uMax = u;
                    if (vc < vMin) vMin = vc;
                    if (vc > vMax) vMax = vc;
                }
            }
        }

        float[] result;
        if (uMin > uMax || vMin > vMax) {
            // 空模型：恒等映射
            result = new float[]{0.0f, 0.0f, 1.0f, 1.0f};
        } else {
            float uRange = Math.max(uMax - uMin, 1.0e-6f);
            float vRange = Math.max(vMax - vMin, 1.0e-6f);
            result = new float[]{uMin, vMin, 1.0f / uRange, 1.0f / vRange};
        }
        ECA$UV_BOUNDS_CACHE.put(model, result);
        return result;
    }
}
