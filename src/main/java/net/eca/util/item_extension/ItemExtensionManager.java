package net.eca.util.item_extension;

import net.eca.api.RegisterItemExtension;
import net.eca.util.EcaLogger;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@OnlyIn(Dist.CLIENT)
public final class ItemExtensionManager {

    private static final Map<Item, ItemExtension> REGISTRY = new ConcurrentHashMap<>();

    public static void scanAndRegisterAll() {
        ModList.get().forEachModFile(modFile -> {
            for (IModInfo modInfo : modFile.getModInfos()) {
                modFile.getScanResult().getAnnotations().forEach(annotationData -> {
                    if (RegisterItemExtension.class.getName().equals(annotationData.annotationType().getClassName())) {
                        String className = annotationData.clazz().getClassName();
                        try {
                            Class.forName(className, true, Thread.currentThread().getContextClassLoader());
                        } catch (ClassNotFoundException e) {
                            EcaLogger.error("Failed to load item extension class {}: {}", className, e.getMessage());
                        }
                    }
                });
            }
        });
    }

    public static boolean register(ItemExtension extension) {
        if (extension == null) {
            EcaLogger.error("Cannot register null item extension");
            return false;
        }

        Item item = extension.getItem();

        if (REGISTRY.containsKey(item)) {
            ItemExtension existing = REGISTRY.get(item);
            EcaLogger.error("Item {} already has an extension registered: {}. Skipping new extension: {}",
                item, existing.getClass().getName(), extension.getClass().getName());
            return false;
        }

        REGISTRY.put(item, extension);
        return true;
    }

    public static ItemExtension getExtension(Item item) {
        return REGISTRY.get(item);
    }

    public static Map<Item, ItemExtension> getRegistryView() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    private ItemExtensionManager() {}
}
