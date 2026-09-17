package net.eca.pro.ingot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ProIngotRegistry {
    private static final List<ProIngot> INGOTS = createIngots();
    private static final Set<String> TARGETS = collectTargets();

    private ProIngotRegistry() {
    }

    static List<ProIngot> applicable(String internalName) {
        List<ProIngot> applicable = new ArrayList<>();
        for (ProIngot ingot : INGOTS) {
            if (ingot.targets().contains(internalName)) applicable.add(ingot);
        }
        return applicable;
    }

    static Set<String> targets() {
        return TARGETS;
    }

    static int size() {
        return INGOTS.size();
    }

    private static List<ProIngot> createIngots() {
        return List.of(
                new EntityStateHookIngot(),
                new ContainerFinalityIngot(),
                contract("entity", "net/minecraft/world/entity/Entity"),
                contract("living_entity", "net/minecraft/world/entity/LivingEntity"),
                contract("entity_lookup", "net/minecraft/world/level/entity/EntityLookup"),
                contract("entity_section", "net/minecraft/world/level/entity/EntitySection"),
                contract("entity_tick_list", "net/minecraft/world/level/entity/EntityTickList"),
                contract("class_instance_multi_map", "net/minecraft/util/ClassInstanceMultiMap"),
                contract("persistent_entity_section_manager",
                        "net/minecraft/world/level/entity/PersistentEntitySectionManager"),
                contract("persistent_entity_callback",
                        "net/minecraft/world/level/entity/PersistentEntitySectionManager$Callback"),
                contract("transient_entity_section_manager",
                        "net/minecraft/world/level/entity/TransientEntitySectionManager"),
                contract("transient_entity_callback",
                        "net/minecraft/world/level/entity/TransientEntitySectionManager$Callback"),
                contract("chunk_map", "net/minecraft/server/level/ChunkMap"),
                contract("tracked_entity", "net/minecraft/server/level/ChunkMap$TrackedEntity"),
                contract("server_level", "net/minecraft/server/level/ServerLevel")
        );
    }

    private static ProIngot contract(String name, String target) {
        return new TargetContractIngot(name, target);
    }

    private static Set<String> collectTargets() {
        Set<String> targets = new LinkedHashSet<>();
        for (ProIngot ingot : INGOTS) targets.addAll(ingot.targets());
        return Set.copyOf(targets);
    }
}
