package net.eca.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.api.EcaAPI;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;

//位置锁定命令
public class LocationLockCommand {

    //注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("lockLocation")
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    .executes(LocationLockCommand::handleLocationLock)
                    .then(Commands.argument("position", Vec3Argument.vec3())
                        .executes(LocationLockCommand::handleLocationLockWithPosition)
                    )
                )
            );
    }

    //处理位置锁定（当前位置）
    private static int handleLocationLock(CommandContext<CommandSourceStack> context) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        if (enabled) {
            return lockLocation(context, null);
        }
        return unlockLocation(context);
    }

    //处理位置锁定（指定位置）
    private static int handleLocationLockWithPosition(CommandContext<CommandSourceStack> context) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        if (enabled) {
            Vec3 position = Vec3Argument.getVec3(context, "position");
            return lockLocation(context, position);
        }
        return unlockLocation(context);
    }

    //执行位置锁定
    private static int lockLocation(CommandContext<CommandSourceStack> context, Vec3 position) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");

            int successCount = 0;

            for (Entity entity : targets) {
                try {
                    if (position != null) {
                        EcaAPI.lockLocation(entity, position);
                    } else {
                        EcaAPI.lockLocation(entity);
                    }
                    successCount++;
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cError locking location for " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;

            if (finalSuccessCount > 0) {
                if (position != null) {
                    source.sendSuccess(() -> Component.literal(
                        String.format("§aLocked location for %d %s at %.1f %.1f %.1f",
                            finalSuccessCount,
                            finalSuccessCount == 1 ? "entity" : "entities",
                            position.x, position.y, position.z)
                    ), true);
                } else {
                    source.sendSuccess(() -> Component.literal(
                        String.format("§aLocked location for %d %s",
                            finalSuccessCount,
                            finalSuccessCount == 1 ? "entity" : "entities")
                    ), true);
                }
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

                    EcaAPI.unlockLocation(entity);
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
