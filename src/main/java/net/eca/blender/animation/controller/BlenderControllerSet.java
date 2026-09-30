package net.eca.blender.animation.controller;

/** Controllers are definitions, not mutable playback instances. Either member may be null. */
public record BlenderControllerSet(BlenderLifecycleController lifecycle, BlenderSkillController skills) {
}
