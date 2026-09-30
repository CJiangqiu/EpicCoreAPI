package net.eca.blender.animation.controller;

/** Called only by the server executor; callbacks must not retain per-entity state in shared definitions. */
public interface BlenderSkillController {
    BlenderSkillDefinition findSkill(BlenderControllerContext context, String skillId);
    default boolean canStartSkill(BlenderControllerContext context, BlenderSkillDefinition skill) { return true; }
    default boolean canInterrupt(BlenderControllerContext context, BlenderSkillDefinition current,
                                 BlenderSkillDefinition incoming) {
        return current.interruptible() && incoming.priority() >= current.priority();
    }
    default boolean canInterruptForHurt(BlenderControllerContext context, BlenderSkillDefinition current) {
        return current.interruptOnHurt();
    }
    default void onSkillStarted(BlenderControllerContext context) { }
    default void onSkillMarker(BlenderControllerContext context, BlenderSkillMarker marker) { }
    default void onSkillCompleted(BlenderControllerContext context) { }
    default void onSkillCancelled(BlenderControllerContext context, BlenderCancellationReason reason) { }
}
