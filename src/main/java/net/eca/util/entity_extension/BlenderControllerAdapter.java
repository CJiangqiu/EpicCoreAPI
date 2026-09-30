package net.eca.util.entity_extension;

import net.eca.blender.animation.BlenderControllers;

/** Common-side bridge; no client model or renderer classes are referenced here. */
public final class BlenderControllerAdapter {
    private BlenderControllerAdapter() { }

    public static void register() {
        BlenderControllers.setFallbackResolver(entity -> {
            EntityExtension extension = EntityExtensionManager.getExtension(entity.getType());
            return extension == null ? null : extension.blenderAnimationControllers(entity);
        });
    }
}
