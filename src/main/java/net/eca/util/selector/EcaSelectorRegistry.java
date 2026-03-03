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
            Component.literal("ECA all entities selector")
        ));
        EntitySelectorManager.register("eca_p", new EcaGlobalSelectorType(
            "eca_p",
            EcaEntitySelector.SelectorMode.NEAREST_PLAYER,
            Component.literal("ECA nearest player selector")
        ));
        EntitySelectorManager.register("eca_a", new EcaGlobalSelectorType(
            "eca_a",
            EcaEntitySelector.SelectorMode.ALL_PLAYERS,
            Component.literal("ECA all players selector")
        ));
        EntitySelectorManager.register("eca_r", new EcaGlobalSelectorType(
            "eca_r",
            EcaEntitySelector.SelectorMode.RANDOM_PLAYER,
            Component.literal("ECA random player selector")
        ));
        EntitySelectorManager.register("eca_s", new EcaGlobalSelectorType(
            "eca_s",
            EcaEntitySelector.SelectorMode.SELF,
            Component.literal("ECA self selector")
        ));

        registered = true;
    }
}
