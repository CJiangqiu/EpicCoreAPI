package net.eca.util.block_extension;

import net.eca.api.RegisterBlockExtension;
import net.eca.util.EcaLogger;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public final class BlockExtensionManager {

    private static final Map<Block, BlockExtension> REGISTRY = new ConcurrentHashMap<>();

    private BlockExtensionManager() {
    }

    public static void scanAndRegisterAll() {
        ModList.get().forEachModFile(modFile -> modFile.getScanResult().getAnnotations().forEach(annotation -> {
            if (!RegisterBlockExtension.class.getName().equals(annotation.annotationType().getClassName())) {
                return;
            }
            String className = annotation.clazz().getClassName();
            try {
                Class.forName(className, true, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                EcaLogger.error("Failed to load block extension class {}: {}", className, e.getMessage());
            }
        }));
    }

    public static boolean register(BlockExtension extension) {
        if (extension == null) {
            EcaLogger.error("Cannot register null block extension");
            return false;
        }
        BlockExtension existing = REGISTRY.putIfAbsent(extension.getBlock(), extension);
        if (existing != null) {
            EcaLogger.error("Block {} already has an extension registered: {}. Skipping new extension: {}",
                extension.getBlock(), existing.getClass().getName(), extension.getClass().getName());
            return false;
        }
        return true;
    }

    public static BlockExtension getExtension(Block block) {
        return REGISTRY.get(block);
    }

    public static Map<Block, BlockExtension> getRegistryView() {
        return Collections.unmodifiableMap(REGISTRY);
    }
}
