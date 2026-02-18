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

//位置锁定命令
public class LocationLockCommand {

    //注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("locationLock")
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    .executes(LocationLockCommand::handleLocationLock)
                )
            );
    }

    //处理位置锁定
    private static int handleLocationLock(CommandContext<CommandSourceStack> context) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        if (enabled) {
            return lockLocation(context);
        }
        return unlockLocation(context);
    }

    //执行位置锁定
    private static int lockLocation(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");

            int successCount = 0;

            for (Entity entity : targets) {
                try {
                    EcaAPI.lockEntityLocation(entity);
                    successCount++;
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cError locking location for " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aLocked location for %d %s",
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

    //执行解除位置锁定
    private static int unlockLocation(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");

            int successCount = 0;
            int skippedCount = 0;

            for (Entity entity : targets) {
                try {
                    if (!EcaAPI.isLocationLocked(entity)) {
                        skippedCount++;
                        continue;
                    }

                    EcaAPI.unlockEntityLocation(entity);
                    successCount++;
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cError unlocking location for " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;
            final int finalSkippedCount = skippedCount;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aUnlocked location for %d %s",
                        finalSuccessCount,
                        finalSuccessCount == 1 ? "entity" : "entities")
                ), true);
            }

            if (finalSkippedCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§eSkipped %d %s (not locked)",
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
