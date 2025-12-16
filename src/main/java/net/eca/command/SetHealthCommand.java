package net.eca.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.api.EcaAPI;
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
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
            float health = FloatArgumentType.getFloat(context, "health");

            int successCount = 0;

            for (Entity entity : targets) {
                if (!(entity instanceof LivingEntity livingEntity)) continue;

                try {
                    boolean success = EcaAPI.setHealth(livingEntity, health);
                    if (success) {
                        successCount++;
                    } else {
                        source.sendFailure(Component.literal(
                            "§cFailed to set health for " + entity.getName().getString()
                        ));
                    }
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cError setting health for " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;
            final float finalHealth = health;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aSet health of %d %s to %.1f",
                        finalSuccessCount,
                        finalSuccessCount == 1 ? "entity" : "entities",
                        finalHealth)
                ), true);
            }

            return finalSuccessCount;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }
}
