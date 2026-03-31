package net.eca.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.api.EcaAPI;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;

public class ForceLoadingCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("setForceLoading")
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("forceLoad", BoolArgumentType.bool())
                                .executes(ForceLoadingCommand::setForceLoading)
                        )
                );
    }

    private static int setForceLoading(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EcaCommandSelector.getEntities(context);
            boolean forceLoad = BoolArgumentType.getBool(context, "forceLoad");
            ServerLevel level = source.getLevel();

            int successCount = 0;
            int skippedCount = 0;
            for (Entity entity : targets) {
                if (!(entity instanceof LivingEntity livingEntity)) {
                    skippedCount++;
                    continue;
                }
                EcaAPI.setForceLoading(livingEntity, level, forceLoad);
                successCount++;
            }

            final int finalCount = successCount;
            final int finalSkipped = skippedCount;
            final String status = forceLoad ? "force loaded" : "not force loaded";

            if (finalCount > 0) {
                source.sendSuccess(() -> Component.literal(
                        String.format("§aSet %d %s to %s", finalCount,
                                finalCount == 1 ? "entity" : "entities", status)
                ), true);
            }
            if (finalSkipped > 0) {
                source.sendSuccess(() -> Component.literal(
                        String.format("§eSkipped %d %s (not living)",
                                finalSkipped, finalSkipped == 1 ? "entity" : "entities")
                ), false);
            }

            return finalCount;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }
}
