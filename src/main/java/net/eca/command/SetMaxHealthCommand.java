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

//设置实体最大生命值命令
public class SetMaxHealthCommand {

    //注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("setMaxHealth")
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("maxHealth", FloatArgumentType.floatArg())
                    .executes(SetMaxHealthCommand::setMaxHealth)
                )
            );
    }

    //执行设置最大生命值
    private static int setMaxHealth(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
            float maxHealth = FloatArgumentType.getFloat(context, "maxHealth");

            int successCount = 0;
            int skippedCount = 0;

            for (Entity entity : targets) {
                if (!(entity instanceof LivingEntity livingEntity)) {
                    skippedCount++;
                    continue;
                }

                try {
                    boolean success = EcaAPI.setMaxHealth(livingEntity, maxHealth);
                    if (success) {
                        successCount++;
                    } else {
                        source.sendFailure(Component.literal(
                            "§cFailed to set max health for " + entity.getName().getString()
                        ));
                    }
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cError setting max health for " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;
            final int finalSkippedCount = skippedCount;
            final float finalMaxHealth = maxHealth;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aSet max health of %d %s to %.1f",
                        finalSuccessCount,
                        finalSuccessCount == 1 ? "entity" : "entities",
                        finalMaxHealth)
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
}
