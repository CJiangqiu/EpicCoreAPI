package net.eca.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
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

//锁定/解锁实体最大生命值命令
public class LockMaxHealthCommand {

    //注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("lockMaxHealth")
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    //false时直接解锁
                    .executes(LockMaxHealthCommand::handleLockMaxHealth)
                    //true时需要输入锁定值
                    .then(Commands.argument("value", FloatArgumentType.floatArg())
                        .executes(LockMaxHealthCommand::handleLockMaxHealthWithValue)
                    )
                )
            );
    }

    //处理锁定（无值参数）
    private static int handleLockMaxHealth(CommandContext<CommandSourceStack> context) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        if (enabled) {
            context.getSource().sendFailure(Component.literal(
                "§cPlease specify a value when enabling lock: /eca lockMaxHealth <targets> true <value>"
            ));
            return 0;
        }
        return unlockMaxHealth(context);
    }

    //处理锁定（有值参数）
    private static int handleLockMaxHealthWithValue(CommandContext<CommandSourceStack> context) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        if (!enabled) {
            return unlockMaxHealth(context);
        }
        return lockMaxHealth(context);
    }

    //执行锁定最大生命值
    private static int lockMaxHealth(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
            float value = FloatArgumentType.getFloat(context, "value");

            int successCount = 0;

            for (Entity entity : targets) {
                if (!(entity instanceof LivingEntity livingEntity)) continue;

                try {
                    EcaAPI.lockMaxHealth(livingEntity, value);
                    successCount++;
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cError locking max health for " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;
            final float finalValue = value;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aLocked max health of %d %s to %.1f",
                        finalSuccessCount,
                        finalSuccessCount == 1 ? "entity" : "entities",
                        finalValue)
                ), true);
            }

            return finalSuccessCount;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }

    //执行解锁最大生命值
    private static int unlockMaxHealth(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");

            int successCount = 0;
            int skippedCount = 0;

            for (Entity entity : targets) {
                if (!(entity instanceof LivingEntity livingEntity)) continue;

                try {
                    if (!EcaAPI.isMaxHealthLocked(livingEntity)) {
                        skippedCount++;
                        continue;
                    }

                    EcaAPI.unlockMaxHealth(livingEntity);
                    successCount++;
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cError unlocking max health for " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;
            final int finalSkippedCount = skippedCount;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aUnlocked max health of %d %s",
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
