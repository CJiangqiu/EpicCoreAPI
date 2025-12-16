package net.eca.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.api.EcaAPI;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.Collection;

public class InvulnerableCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("setInvulnerable")
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("invulnerable", BoolArgumentType.bool())
                    .executes(InvulnerableCommand::setInvulnerable)
                )
            );
    }

    private static int setInvulnerable(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
            boolean invulnerable = BoolArgumentType.getBool(context, "invulnerable");

            int successCount = 0;
            for (Entity entity : targets) {
                try {
                    EcaAPI.setInvulnerable(entity, invulnerable);
                    successCount++;
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cFailed to set invulnerability for " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalCount = successCount;
            final String status = invulnerable ? "invulnerable" : "vulnerable";

            if (finalCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aSet %d %s to %s",
                        finalCount,
                        finalCount == 1 ? "entity" : "entities",
                        status)
                ), true);
            }

            return finalCount;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }
}
