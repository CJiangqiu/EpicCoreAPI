package net.eca.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.eca.api.EcaAPI;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * AllReturn Command - Apply AllReturn transformation to target entity's mod classes.
 *
 * DANGER! This feature may cause unexpected crashes!
 * Requires "Enable Radical Logic" in Attack config to be enabled.
 * Will return all void and boolean methods of the attacked entity's mod.
 */
public class AllReturnCommand {
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("allReturn")
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("enable", BoolArgumentType.bool())
                    .executes(AllReturnCommand::applyAllReturnToTargets)
                )
            )
            .then(Commands.literal("global")
                .then(Commands.argument("enable", BoolArgumentType.bool())
                    .executes(AllReturnCommand::setGlobalAllReturn)
                )
            );
    }

    private static int setGlobalAllReturn(CommandContext<CommandSourceStack> context) {
        boolean enable = BoolArgumentType.getBool(context, "enable");
        boolean success = EcaAPI.setGlobalAllReturn(enable);

        if (success) {
            context.getSource().sendSuccess(() -> Component.literal(
                enable ? "§aGlobal AllReturn enabled (all non-whitelisted mods)"
                       : "§aGlobal AllReturn disabled"
            ), true);
            return 1;
        } else {
            context.getSource().sendFailure(Component.literal(
                "§cFailed to enable global AllReturn. " +
                "Check if Attack Radical Logic is enabled and Agent is initialized."
            ));
            return 0;
        }
    }

    private static int applyAllReturnToTargets(CommandContext<CommandSourceStack> context) {
        boolean enable = BoolArgumentType.getBool(context, "enable");

        Collection<? extends Entity> targets;
        try {
            targets = EcaCommandSelector.getEntities(context);
        } catch (CommandSyntaxException e) {
            context.getSource().sendFailure(Component.literal(
                "§cFailed to resolve targets: " + e.getMessage()
            ));
            return 0;
        }
        if (targets.isEmpty()) {
            context.getSource().sendFailure(Component.literal("§cNo targets found."));
            return 0;
        }

        Set<Class<?>> targetClasses = new HashSet<>();
        int successCount = 0;
        for (Entity entity : targets) {
            if (!targetClasses.add(entity.getClass())) {
                continue;
            }
            boolean success = enable
                    ? EcaAPI.enableAllReturn(entity)
                    : EcaAPI.disableAllReturn(entity);
            if (success) {
                successCount++;
            }
        }

        if (successCount == 0) {
            context.getSource().sendFailure(Component.literal(
                "§cNo selected entity mod could be resolved or transformed."
            ));
            return 0;
        }

        int appliedCount = successCount;
        context.getSource().sendSuccess(() -> Component.literal(
            enable
                    ? "§aAllReturn enabled for " + appliedCount + " selected mod(s)"
                    : "§aAllReturn disabled for " + appliedCount + " selected mod(s)"
        ), true);
        return successCount;
    }
}
