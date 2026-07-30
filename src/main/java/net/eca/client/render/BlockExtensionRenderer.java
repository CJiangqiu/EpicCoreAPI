package net.eca.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.eca.util.block_extension.BlockExtension;
import net.eca.util.block_extension.BlockExtensionManager;
import net.eca.util.block_extension.BlockExtensionSafeAccess;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public final class BlockExtensionRenderer {

    private static final double MAX_RENDER_DISTANCE_SQR = 256.0 * 256.0;
    private static final Map<Long, Set<BlockPos>> SECTION_BLOCKS = new HashMap<>();

    private BlockExtensionRenderer() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(BlockExtensionRenderer::onChunkLoad);
        MinecraftForge.EVENT_BUS.addListener(BlockExtensionRenderer::onChunkUnload);
        MinecraftForge.EVENT_BUS.addListener(BlockExtensionRenderer::onRenderLevel);
    }

    public static void onBlockChanged(ClientLevel level, BlockPos pos, BlockState state) {
        long sectionKey = SectionPos.asLong(pos);
        Set<BlockPos> positions = SECTION_BLOCKS.get(sectionKey);
        if (BlockExtensionManager.getExtension(state.getBlock()) != null) {
            if (positions == null) {
                positions = new HashSet<>();
                SECTION_BLOCKS.put(sectionKey, positions);
            }
            positions.add(pos.immutable());
        } else if (positions != null) {
            positions.remove(pos);
            if (positions.isEmpty()) {
                SECTION_BLOCKS.remove(sectionKey);
            }
        }
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk) || !(chunk.getLevel() instanceof ClientLevel)) {
            return;
        }
        scanChunk(chunk);
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getChunk() instanceof LevelChunk chunk) || !(chunk.getLevel() instanceof ClientLevel)) {
            return;
        }
        ChunkPos chunkPos = chunk.getPos();
        SECTION_BLOCKS.keySet().removeIf(key -> SectionPos.x(key) == chunkPos.x && SectionPos.z(key) == chunkPos.z);
    }

    private static void scanChunk(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
            long sectionKey = SectionPos.asLong(chunk.getPos().x, sectionY, chunk.getPos().z);
            SECTION_BLOCKS.remove(sectionKey);
            if (!section.maybeHas(state -> BlockExtensionManager.getExtension(state.getBlock()) != null)) {
                continue;
            }
            Set<BlockPos> positions = new HashSet<>();
            int baseX = chunk.getPos().getMinBlockX();
            int baseY = SectionPos.sectionToBlockCoord(sectionY);
            int baseZ = chunk.getPos().getMinBlockZ();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (BlockExtensionManager.getExtension(state.getBlock()) != null) {
                            positions.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
                        }
                    }
                }
            }
            if (!positions.isEmpty()) {
                SECTION_BLOCKS.put(sectionKey, positions);
            }
        }
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || SECTION_BLOCKS.isEmpty()) {
            return;
        }

        Camera camera = event.getCamera();
        Map<ShaderMaskPass, SpriteBatchingVertexConsumer> batches = new LinkedHashMap<>();
        Map<BlockExtension, List<ShaderMaskPass>> passCache = new HashMap<>();
        List<BlockPos> stale = new ArrayList<>();
        for (Set<BlockPos> section : SECTION_BLOCKS.values()) {
            for (BlockPos pos : section) {
                if (pos.distToCenterSqr(camera.getPosition()) > MAX_RENDER_DISTANCE_SQR) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                BlockExtension extension = BlockExtensionManager.getExtension(state.getBlock());
                if (extension == null) {
                    stale.add(pos);
                    continue;
                }
                if (state.getRenderShape() != RenderShape.MODEL
                    || !BlockExtensionSafeAccess.shouldRender(extension, state, level, pos)) {
                    continue;
                }
                List<ShaderMaskPass> passes = passCache.computeIfAbsent(extension,
                    ignored -> extension.getBlockShaderPasses());
                if (passes == null) continue;
                for (ShaderMaskPass pass : passes) {
                    if (pass == null || pass.alpha() <= 0.0f) continue;
                    SpriteBatchingVertexConsumer batch = batches.computeIfAbsent(pass,
                        ignored -> new SpriteBatchingVertexConsumer(pass.renderType().format()));
                    renderBlock(level, event.getPoseStack(), pos, state, batch);
                }
            }
        }
        for (BlockPos pos : stale) {
            onBlockChanged(level, pos, level.getBlockState(pos));
        }
        batches.forEach(BlockExtensionRenderer::enqueue);
    }

    private static void renderBlock(ClientLevel level, PoseStack poseStack, BlockPos pos, BlockState state,
                                    SpriteBatchingVertexConsumer consumer) {
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getBlockRenderer().getBlockModel(state);
        ModelData modelData = model.getModelData(level, pos, state, ModelData.EMPTY);
        poseStack.pushPose();
        poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
        minecraft.getBlockRenderer().getModelRenderer().tesselateBlock(level, model, state, pos, poseStack,
            consumer, true, RandomSource.create(), state.getSeed(pos), 0, modelData, null);
        poseStack.popPose();
    }

    private static void enqueue(ShaderMaskPass pass, SpriteBatchingVertexConsumer batch) {
        batch.finish(spriteBatch -> ShaderMaskRenderQueue.enqueue(pass, spriteBatch.builder(),
            spriteBatch.builder().end(), spriteBatch.uvTransform()));
    }
}
