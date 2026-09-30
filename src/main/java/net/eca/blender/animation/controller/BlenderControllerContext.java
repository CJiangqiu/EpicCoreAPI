package net.eca.blender.animation.controller;

import net.minecraft.world.entity.LivingEntity;

/** A callback snapshot. Skill is null and executionId is zero outside skill execution. */
public record BlenderControllerContext(LivingEntity entity, long gameTime, long executionId,
                                        BlenderSkillDefinition skill, float elapsedTicks) {
}
