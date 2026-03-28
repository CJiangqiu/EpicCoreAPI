package net.eca.util.selector;

import net.minecraft.network.chat.Component;
import net.minecraftforge.common.command.EntitySelectorManager;

public final class EcaSelectorRegistry {

    private static boolean registered = false;

    private EcaSelectorRegistry() {
    }

    public static void register() {
        if (registered) {
            return;
        }

        EntitySelectorManager.register("eca_e", new EcaGlobalSelectorType(
            "eca_e",
            EcaEntitySelector.SelectorMode.ALL_ENTITIES,
            Component.translatable("eca.argument.entity.selector.allEntities")
        ));
        EntitySelectorManager.register("eca_p", new EcaGlobalSelectorType(
            "eca_p",
            EcaEntitySelector.SelectorMode.NEAREST_PLAYER,
            Component.translatable("eca.argument.entity.selector.nearestPlayer")
        ));
        EntitySelectorManager.register("eca_a", new EcaGlobalSelectorType(
            "eca_a",
            EcaEntitySelector.SelectorMode.ALL_PLAYERS,
            Component.translatable("eca.argument.entity.selector.allPlayers")
        ));
        EntitySelectorManager.register("eca_r", new EcaGlobalSelectorType(
            "eca_r",
            EcaEntitySelector.SelectorMode.RANDOM_PLAYER,
            Component.translatable("eca.argument.entity.selector.randomPlayer")
        ));
        EntitySelectorManager.register("eca_s", new EcaGlobalSelectorType(
            "eca_s",
            EcaEntitySelector.SelectorMode.SELF,
            Component.translatable("eca.argument.entity.selector.self")
        ));

        registered = true;
    }
}
