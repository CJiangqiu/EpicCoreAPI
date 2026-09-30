package net.eca.blender.animation.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DefaultBlenderSkillController implements BlenderSkillController {
    private final Map<String, BlenderSkillDefinition> skills;

    public DefaultBlenderSkillController(List<BlenderSkillDefinition> definitions) {
        Map<String, BlenderSkillDefinition> entries = new HashMap<>();
        for (BlenderSkillDefinition definition : definitions) {
            if (entries.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate skill id: " + definition.id());
            }
        }
        skills = Map.copyOf(entries);
    }

    @Override
    public BlenderSkillDefinition findSkill(BlenderControllerContext context, String skillId) { return skills.get(skillId); }
}
