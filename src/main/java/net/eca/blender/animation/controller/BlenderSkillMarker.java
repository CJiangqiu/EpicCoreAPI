package net.eca.blender.animation.controller;

public record BlenderSkillMarker(String name, int tick) {
    public BlenderSkillMarker {
        if (name == null || name.isBlank() || name.length() > 128 || tick < 0) {
            throw new IllegalArgumentException("Invalid skill marker");
        }
    }
}
