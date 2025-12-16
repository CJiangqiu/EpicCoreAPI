package net.eca.command;

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

//击杀实体命令
public class KillEntityCommand {

    //注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("kill")
            .then(Commands.argument("targets", EntityArgument.entities())
                .executes(KillEntityCommand::killEntities)
            );
    }

    //执行击杀
    private static int killEntities(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
            int successCount = 0;

            for (Entity entity : targets) {
                if (!(entity instanceof LivingEntity livingEntity)) {
                    source.sendFailure(Component.literal(
                        "§c" + entity.getName().getString() + " is not a living entity"
                    ));
                    continue;
                }

                try {
                    //使用通用伤害源
                    EcaAPI.killEntity(livingEntity, source.getLevel().damageSources().generic());
                    successCount++;
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cFailed to kill " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aKilled %d %s",
                        finalSuccessCount,
                        finalSuccessCount == 1 ? "entity" : "entities")
                ), true);
            }

            return finalSuccessCount;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }
}
