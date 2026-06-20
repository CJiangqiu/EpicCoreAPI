package net.eca.client.render.shader_generator;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.eca.client.render.shader.EcaShaderInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;

public final class ShaderPreviewRenderer {

    private static final ItemStack PREVIEW_ITEM = createPreviewItem();

    public static void render(
        GuiGraphics graphics,
        ShaderPreviewSource source,
        ShaderPreviewTarget target,
        int left,
        int top,
        int right,
        int bottom,
        int mouseX,
        int mouseY,
        float partialTick
    ) {
        if (source == null || right <= left || bottom <= top) {
            return;
        }

        graphics.flush();
        graphics.enableScissor(left, top, right, bottom);
        try {
            switch (target) {
                case PLANE -> renderBlockSurface(
                    source.skybox(),
                    left + 16,
                    top + 16,
                    right - 16,
                    bottom - 16
                );
                case SKYBOX -> renderBlockSurface(source.skybox(), left, top, right, bottom);
                case BOSS_BAR -> {
                    int width = Math.max(80, Math.min(right - left - 40, 360));
                    int height = Math.max(16, Math.min(42, (bottom - top) / 5));
                    int centerX = (left + right) / 2;
                    int centerY = (top + bottom) / 2;
                    renderBlockSurface(
                        source.bossBar(),
                        centerX - width / 2,
                        centerY - height / 2,
                        centerX + width / 2,
                        centerY + height / 2
                    );
                }
                case ITEM -> renderItem(graphics, source, left, top, right, bottom);
                case ENTITY -> renderEntity(
                    graphics,
                    source,
                    left,
                    top,
                    right,
                    bottom,
                    mouseX,
                    mouseY,
                    partialTick
                );
            }
        } finally {
            graphics.disableScissor();
            Lighting.setupFor3DItems();
        }
    }

    private static void renderBlockSurface(
        RenderType renderType,
        int left,
        int top,
        int right,
        int bottom
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        try {
            modelView.setIdentity();
            RenderSystem.applyModelViewMatrix();

            float centerX = ((left + right) / (float) minecraft.getWindow().getGuiScaledWidth()) - 1.0F;
            float centerY = 1.0F - ((top + bottom) / (float) minecraft.getWindow().getGuiScaledHeight());
            float scaleX = (right - left) / (float) minecraft.getWindow().getGuiScaledWidth();
            float scaleY = (bottom - top) / (float) minecraft.getWindow().getGuiScaledHeight();
            Matrix4f projection = new Matrix4f()
                .translation(centerX, centerY, 0.0F)
                .scale(scaleX, scaleY, 1.0F);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);

            BufferBuilder builder = Tesselator.getInstance().getBuilder();
            builder.begin(renderType.mode(), DefaultVertexFormat.BLOCK);
            blockVertex(builder, -1.0F, -1.0F, 0.0F, 1.0F);
            blockVertex(builder, 1.0F, -1.0F, 1.0F, 1.0F);
            blockVertex(builder, 1.0F, 1.0F, 1.0F, 0.0F);
            blockVertex(builder, -1.0F, 1.0F, 0.0F, 0.0F);
            blockVertex(builder, -1.0F, 1.0F, 0.0F, 0.0F);
            blockVertex(builder, 1.0F, 1.0F, 1.0F, 0.0F);
            blockVertex(builder, 1.0F, -1.0F, 1.0F, 1.0F);
            blockVertex(builder, -1.0F, -1.0F, 0.0F, 1.0F);
            renderType.end(builder, VertexSorting.ORTHOGRAPHIC_Z);
        } finally {
            RenderSystem.setProjectionMatrix(savedProjection, VertexSorting.ORTHOGRAPHIC_Z);
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void blockVertex(
        BufferBuilder builder,
        float x,
        float y,
        float u,
        float v
    ) {
        builder.vertex(x, y, 0.5F)
            .color(255, 255, 255, 255)
            .uv(u, v)
            .uv2(0xF000F0)
            .normal(0.0F, 0.0F, 1.0F)
            .endVertex();
    }

    private static void renderItem(
        GuiGraphics graphics,
        ShaderPreviewSource source,
        int left,
        int top,
        int right,
        int bottom
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        ItemRenderer itemRenderer = minecraft.getItemRenderer();
        Level level = minecraft.level;
        BakedModel model = itemRenderer.getModel(PREVIEW_ITEM, level, minecraft.player, 0);
        MultiBufferSource.BufferSource delegate = MultiBufferSource.immediate(new BufferBuilder(4096));
        MultiBufferSource forced = ignored -> delegate.getBuffer(source.item());

        int size = Math.max(40, Math.min(right - left, bottom - top) * 2 / 5);
        graphics.pose().pushPose();
        try {
            float[] bounds = computeUvBounds(model);
            EcaShaderInstance.setLocalUvBounds(
                bounds[0],
                bounds[1],
                bounds[2],
                bounds[3]
            );
            graphics.pose().translate((left + right) / 2.0, (top + bottom) / 2.0, 180.0);
            graphics.pose().scale(size, -size, size);
            graphics.pose().mulPose(new Quaternionf().rotateY(0.65F).rotateX(-0.35F));
            itemRenderer.render(
                PREVIEW_ITEM,
                ItemDisplayContext.GUI,
                false,
                graphics.pose(),
                forced,
                0xF000F0,
                OverlayTexture.NO_OVERLAY,
                model
            );
            delegate.endBatch();
        } finally {
            EcaShaderInstance.clearLocalUvBounds();
            graphics.pose().popPose();
        }
    }

    private static void renderEntity(
        GuiGraphics graphics,
        ShaderPreviewSource source,
        int left,
        int top,
        int right,
        int bottom,
        int mouseX,
        int mouseY,
        float partialTick
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        RenderType entityType = source.entity(minecraft.player.getSkinTextureLocation());
        MultiBufferSource.BufferSource delegate = MultiBufferSource.immediate(new BufferBuilder(8192));
        MultiBufferSource forced = ignored -> delegate.getBuffer(entityType);
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        int size = Math.max(24, Math.min(right - left, bottom - top) / 3);
        float horizontal = (float) Math.atan(((left + right) * 0.5F - mouseX) / 80.0F);
        float vertical = (float) Math.atan(((top + bottom) * 0.5F - mouseY) / 80.0F);
        Quaternionf orientation = new Quaternionf()
            .rotateZ((float) Math.PI)
            .rotateX(vertical * 0.35F)
            .rotateY(horizontal * 0.35F);

        graphics.pose().pushPose();
        try {
            graphics.pose().translate((left + right) / 2.0, bottom - 28.0, 220.0);
            graphics.pose().mulPoseMatrix(new Matrix4f().scaling(size, size, -size));
            graphics.pose().mulPose(orientation);
            Lighting.setupForEntityInInventory();
            dispatcher.overrideCameraOrientation(new Quaternionf().rotateX(vertical * 0.35F));
            dispatcher.setRenderShadow(false);
            RenderSystem.runAsFancy(() -> dispatcher.render(
                minecraft.player,
                0.0,
                0.0,
                0.0,
                0.0F,
                partialTick,
                graphics.pose(),
                forced,
                0xF000F0
            ));
            delegate.endBatch();
        } finally {
            dispatcher.setRenderShadow(true);
            graphics.pose().popPose();
        }
    }

    private static ItemStack createPreviewItem() {
        return new ItemStack(Items.DIAMOND_SWORD);
    }

    private static float[] computeUvBounds(BakedModel model) {
        float uMin = Float.POSITIVE_INFINITY;
        float vMin = Float.POSITIVE_INFINITY;
        float uMax = Float.NEGATIVE_INFINITY;
        float vMax = Float.NEGATIVE_INFINITY;
        RandomSource random = RandomSource.create(42L);

        float[] bounds = includeQuads(
            model.getQuads(null, null, random),
            uMin,
            vMin,
            uMax,
            vMax
        );
        uMin = bounds[0];
        vMin = bounds[1];
        uMax = bounds[2];
        vMax = bounds[3];
        for (Direction direction : Direction.values()) {
            random.setSeed(42L);
            bounds = includeQuads(
                model.getQuads(null, direction, random),
                uMin,
                vMin,
                uMax,
                vMax
            );
            uMin = bounds[0];
            vMin = bounds[1];
            uMax = bounds[2];
            vMax = bounds[3];
        }

        if (uMin > uMax || vMin > vMax) {
            return new float[]{0.0F, 0.0F, 1.0F, 1.0F};
        }
        return new float[]{
            uMin,
            vMin,
            1.0F / Math.max(uMax - uMin, 1.0E-6F),
            1.0F / Math.max(vMax - vMin, 1.0E-6F)
        };
    }

    private static float[] includeQuads(
        List<BakedQuad> quads,
        float uMin,
        float vMin,
        float uMax,
        float vMax
    ) {
        int stride = DefaultVertexFormat.BLOCK.getIntegerSize();
        int uvOffset = 4;
        for (BakedQuad quad : quads) {
            int[] vertices = quad.getVertices();
            for (int vertex = 0; vertex < 4; vertex++) {
                int base = vertex * stride + uvOffset;
                if (base + 1 >= vertices.length) {
                    continue;
                }
                float u = Float.intBitsToFloat(vertices[base]);
                float v = Float.intBitsToFloat(vertices[base + 1]);
                uMin = Math.min(uMin, u);
                vMin = Math.min(vMin, v);
                uMax = Math.max(uMax, u);
                vMax = Math.max(vMax, v);
            }
        }
        return new float[]{uMin, vMin, uMax, vMax};
    }

    private ShaderPreviewRenderer() {}
}
