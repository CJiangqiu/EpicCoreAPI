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
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;

//类还原命令：将目标实体关键生命周期方法还原为原版实现
public class RestoreCommand {

    //注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("restore")
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("enable", BoolArgumentType.bool())
                    .executes(RestoreCommand::applyRestore)
                )
            );
    }

    //执行类还原 / 取消还原
    private static int applyRestore(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean enable = BoolArgumentType.getBool(context, "enable");

        try {
            Collection<? extends Entity> targets = EcaCommandSelector.getEntities(context);

            int successCount = 0;
            int skippedCount = 0;

            for (Entity entity : targets) {
                if (!(entity instanceof LivingEntity livingEntity)) {
                    skippedCount++;
                    continue;
                }
                if (enable) {
                    if (EcaAPI.restoreEntity(livingEntity)) {
                        successCount++;
                    } else {
                        source.sendFailure(Component.literal(
                            "§cFailed to restore " + entity.getName().getString()
                                + " (check Attack Radical Logic config and Agent)"
                        ));
                    }
                } else {
                    EcaAPI.unrestoreEntity(livingEntity);
                    successCount++;
                }
            }

            final int finalSuccessCount = successCount;
            final int finalSkippedCount = skippedCount;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(String.format(
                    "§a%s %d %s",
                    enable ? "Restored" : "Unrestored",
                    finalSuccessCount,
                    finalSuccessCount == 1 ? "entity" : "entities"
                )), true);
            }

            if (finalSkippedCount > 0) {
                source.sendSuccess(() -> Component.literal(String.format(
                    "§eSkipped %d %s (not living)",
                    finalSkippedCount,
                    finalSkippedCount == 1 ? "entity" : "entities"
                )), false);
            }

            return finalSuccessCount;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }
}
