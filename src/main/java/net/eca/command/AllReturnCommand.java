package net.eca.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.eca.api.EcaAPI;
import net.eca.coremod.AllReturnToggle;
import net.eca.coremod.TransformerWhitelist;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

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

        // 收集目标实体的包名前缀
        Set<String> targetPrefixes = new HashSet<>();
        for (Entity entity : targets) {
            String binaryName = entity.getClass().getName();

            if (TransformerWhitelist.isProtected(binaryName)) {
                if (entity instanceof LivingEntity livingEntity) {
                    collectEquipmentModPrefixes(livingEntity, targetPrefixes);
                }
                continue;
            }

            String internalPrefix = toInternalPrefix(binaryName);
            if (internalPrefix != null) {
                targetPrefixes.add(internalPrefix);
            }
        }

        if (targetPrefixes.isEmpty()) {
            context.getSource().sendFailure(Component.literal("§cNo valid target packages found."));
            return 0;
        }

        if (enable) {
            AllReturnToggle.setEnabled(true);
            for (String prefix : targetPrefixes) {
                AllReturnToggle.addAllowedPrefix(prefix);
            }
            context.getSource().sendSuccess(() -> Component.literal(
                "§aAllReturn enabled for " + targetPrefixes.size() + " package(s)"
            ), true);
        } else {
            for (String prefix : targetPrefixes) {
                AllReturnToggle.removeAllowedPrefix(prefix);
            }
            context.getSource().sendSuccess(() -> Component.literal(
                "§aAllReturn disabled for " + targetPrefixes.size() + " package(s)"
            ), true);
        }
        return 1;
    }

    private static void collectEquipmentModPrefixes(LivingEntity entity, Set<String> targetPrefixes) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (stack.isEmpty()) continue;

            String itemClassName = stack.getItem().getClass().getName();
            if (TransformerWhitelist.isProtected(itemClassName)) continue;

            String internalPrefix = toInternalPrefix(itemClassName);
            if (internalPrefix != null) {
                targetPrefixes.add(internalPrefix);
            }
        }
    }

    private static String toInternalPrefix(String binaryName) {
        int lastDot = binaryName.lastIndexOf('.');
        if (lastDot <= 0) return null;
        return binaryName.substring(0, lastDot + 1).replace('.', '/');
    }
}
