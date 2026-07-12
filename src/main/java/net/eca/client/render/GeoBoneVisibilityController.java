package net.eca.client.render;

import net.eca.util.entity_extension.EntityLayerExtension;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

final class GeoBoneVisibilityController {

    private final Map<GeoBone, BoneVisibility> originalVisibility = new IdentityHashMap<>();

    void begin(BakedGeoModel model, EntityLayerExtension extension) {
        restore();
        for (GeoBone bone : model.topLevelBones()) {
            capture(bone);
        }
        for (GeoBone bone : model.topLevelBones()) {
            hideConfiguredBones(bone, extension.hiddenGeoBones());
        }
    }

    void restrictOverlay(BakedGeoModel model, Set<String> roots) {
        if (roots == null || roots.isEmpty()) {
            return;
        }
        for (GeoBone bone : model.topLevelBones()) {
            hideOutsideOverlayRoots(bone, roots, false);
        }
    }

    void restore() {
        for (Map.Entry<GeoBone, BoneVisibility> entry : originalVisibility.entrySet()) {
            GeoBone bone = entry.getKey();
            BoneVisibility visibility = entry.getValue();
            bone.setHidden(visibility.hidden());
            bone.setChildrenHidden(visibility.childrenHidden());
        }
        originalVisibility.clear();
    }

    private void capture(GeoBone bone) {
        originalVisibility.putIfAbsent(bone, new BoneVisibility(bone.isHidden(), bone.isHidingChildren()));
        for (GeoBone child : bone.getChildBones()) {
            capture(child);
        }
    }

    private void hideConfiguredBones(GeoBone bone, Set<String> hiddenBones) {
        if (hiddenBones != null && hiddenBones.contains(bone.getName())) {
            bone.setHidden(true);
            return;
        }
        for (GeoBone child : bone.getChildBones()) {
            hideConfiguredBones(child, hiddenBones);
        }
    }

    private boolean hideOutsideOverlayRoots(GeoBone bone, Set<String> roots, boolean included) {
        boolean renderThisBranch = included || roots.contains(bone.getName());
        boolean hasIncludedDescendant = false;

        for (GeoBone child : bone.getChildBones()) {
            hasIncludedDescendant |= hideOutsideOverlayRoots(child, roots, renderThisBranch);
        }

        if (!renderThisBranch && !hasIncludedDescendant) {
            bone.setHidden(true);
        }
        return renderThisBranch || hasIncludedDescendant;
    }

    private record BoneVisibility(boolean hidden, boolean childrenHidden) {}
}
