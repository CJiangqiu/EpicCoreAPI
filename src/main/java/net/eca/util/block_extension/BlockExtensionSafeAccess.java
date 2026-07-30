package net.eca.util.block_extension;

import net.eca.util.EcaLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public final class BlockExtensionSafeAccess {

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private BlockExtensionSafeAccess() {
    }

    public static boolean shouldRender(BlockExtension extension, BlockState state,
                                       BlockAndTintGetter level, BlockPos pos) {
        try {
            return extension.enabled() && extension.shouldRender(state, level, pos);
        } catch (Throwable throwable) {
            String key = extension.getClass().getName() + "#shouldRender";
            if (LOGGED.add(key)) {
                EcaLogger.error("BlockExtension " + extension.getClass().getName()
                    + " threw while deciding whether to render", throwable);
            }
            return false;
        }
    }
}
