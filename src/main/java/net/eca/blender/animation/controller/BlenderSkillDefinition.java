package net.eca.blender.animation.controller;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record BlenderSkillDefinition(String id, BlenderAnimationClip clip, int priority,
                                      boolean interruptible, boolean interruptOnHurt, boolean restartable,
                                      List<BlenderSkillMarker> markers) {
    public BlenderSkillDefinition {
        if (id == null || id.isBlank() || id.length() > 128 || clip == null || clip.loop()
            || markers == null) {
            throw new IllegalArgumentException("Invalid skill definition");
        }
        Set<String> names = new HashSet<>();
        for (BlenderSkillMarker marker : markers) {
            if (marker == null || marker.tick() > clip.durationTicks() || !names.add(marker.name())) {
                throw new IllegalArgumentException("Invalid or duplicate skill marker");
            }
        }
        markers = markers.stream().sorted(Comparator.comparingInt(BlenderSkillMarker::tick)).toList();
    }
}
