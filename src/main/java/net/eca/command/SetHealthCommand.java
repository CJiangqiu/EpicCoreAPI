package net.eca.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.util.EntityUtil;
import net.eca.util.health.DelayedHealthVerifier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;

//设置实体血量命令
public class SetHealthCommand {

    //注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("setHealth")
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("health", FloatArgumentType.floatArg())
                    .executes(SetHealthCommand::setHealth)
                )
            );
    }

    //执行设置血量
    private static int setHealth(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EcaCommandSelector.getEntities(context);
            float health = FloatArgumentType.getFloat(context, "health");

            int successCount = 0;
            int skippedCount = 0;

            for (Entity entity : targets) {
                if (!(entity instanceof LivingEntity livingEntity)) {
                    skippedCount++;
                    continue;
                }

                try {
                    String entityName = entity.getName().getString();
                    boolean success = EntityUtil.setHealth(livingEntity, health,
                            outcome -> reportOutcome(source, entityName, health, outcome));
                    if (success) {
                        successCount++;
                    }
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cError setting health for " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;
            final int finalSkippedCount = skippedCount;
            final float finalHealth = health;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§eSubmitted health %.1f for %d %s; awaiting persistence verification",
                        finalHealth,
                        finalSuccessCount,
                        finalSuccessCount == 1 ? "entity" : "entities")
                ), true);
            }

            if (finalSkippedCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§eSkipped %d %s (not living)",
                        finalSkippedCount,
                        finalSkippedCount == 1 ? "entity" : "entities")
                ), false);
            }

            return finalSuccessCount;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }

    private static void reportOutcome(CommandSourceStack source, String entityName, float health,
                                      DelayedHealthVerifier.Outcome outcome) {
        switch (outcome) {
            case PERSISTED -> source.sendSuccess(() -> Component.literal(
                    String.format("§aHealth of %s persisted at %.1f", entityName, health)), true);
            case ROLLED_BACK -> source.sendFailure(Component.literal(
                    String.format("§cHealth change for %s was rolled back", entityName)));
            case SUPERSEDED -> source.sendSuccess(() -> Component.literal(
                    "§eHealth request for " + entityName + " was superseded"), false);
            case INDETERMINATE -> source.sendFailure(Component.literal(
                    "§cCould not verify persisted health for " + entityName));
        }
    }
}
