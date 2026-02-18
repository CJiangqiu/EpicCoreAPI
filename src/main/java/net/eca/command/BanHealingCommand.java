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

//禁疗/解除禁疗命令
public class BanHealingCommand {

    //注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("banHealing")
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    //false时直接解除禁疗
                    .executes(BanHealingCommand::handleBanHealing)
                    //true时可以输入禁疗值
                    .then(Commands.argument("value", FloatArgumentType.floatArg(0.0f))
                        .executes(BanHealingCommand::handleBanHealingWithValue)
                    )
                )
            );
    }

    //处理禁疗（无值参数）
    private static int handleBanHealing(CommandContext<CommandSourceStack> context) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        if (enabled) {
            //true但没有值，使用当前血量
            return banHealingWithCurrentHealth(context);
        }
        return unbanHealing(context);
    }

    //处理禁疗（有值参数）
    private static int handleBanHealingWithValue(CommandContext<CommandSourceStack> context) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        if (!enabled) {
            //false带值，忽略值直接解除禁疗
            return unbanHealing(context);
        }
        return banHealing(context);
    }

    //执行禁疗（使用当前血量）
    private static int banHealingWithCurrentHealth(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");

            int successCount = 0;

            for (Entity entity : targets) {
                if (!(entity instanceof LivingEntity livingEntity)) continue;

                try {
                    float currentValue = livingEntity.getHealth();
                    EcaAPI.banHealing(livingEntity, currentValue);
                    successCount++;
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cError banning healing for " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aBanned healing for %d %s",
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

    //执行禁疗（指定值）
    private static int banHealing(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
            float value = FloatArgumentType.getFloat(context, "value");

            int successCount = 0;

            for (Entity entity : targets) {
                if (!(entity instanceof LivingEntity livingEntity)) continue;

                try {
                    EcaAPI.banHealing(livingEntity, value);
                    successCount++;
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cError banning healing for " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;
            final float finalValue = value;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aBanned healing for %d %s at %.1f HP",
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

    //执行解除禁疗
    private static int unbanHealing(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");

            int successCount = 0;
            int skippedCount = 0;

            for (Entity entity : targets) {
                if (!(entity instanceof LivingEntity livingEntity)) continue;

                try {
                    if (!EcaAPI.isHealingBanned(livingEntity)) {
                        skippedCount++;
                        continue;
                    }

                    EcaAPI.unbanHealing(livingEntity);
                    successCount++;
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cError unbanning healing for " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;
            final int finalSkippedCount = skippedCount;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aUnbanned healing for %d %s",
                        finalSuccessCount,
                        finalSuccessCount == 1 ? "entity" : "entities")
                ), true);
            }

            if (finalSkippedCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§eSkipped %d %s (not banned)",
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
