package net.eca.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.api.EcaAPI;
import net.eca.util.health.HealthReportManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.nio.file.Path;
import java.util.Collection;

//设置实体血量命令
public class SetHealthCommand {

    //注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("setHealth")
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("health", FloatArgumentType.floatArg())
                    .executes(context -> setHealth(context, false))
                    .then(Commands.literal("report")
                        .executes(context -> setHealth(context, true))
                    )
                )
            );
    }

    //执行设置血量
    private static int setHealth(CommandContext<CommandSourceStack> context, boolean createReport) {
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

                boolean success = false;
                if (createReport) HealthReportManager.begin(livingEntity, health);
                try {
                    success = EcaAPI.setHealth(livingEntity, health);
                    if (success) {
                        successCount++;
                    } else {
                        source.sendFailure(Component.translatable(
                                "command.eca.set_health.failed", entity.getDisplayName())
                                .withStyle(ChatFormatting.RED));
                    }
                } catch (Exception e) {
                    source.sendFailure(Component.translatable(
                            "command.eca.set_health.error", entity.getDisplayName(), e.getMessage())
                            .withStyle(ChatFormatting.RED));
                    if (createReport) HealthReportManager.finishWithError(livingEntity, e);
                } finally {
                    if (createReport) {
                        Path report = HealthReportManager.finish(livingEntity, success);
                        if (report != null) {
                            Path absoluteReport = report.toAbsolutePath().normalize();
                            source.sendSuccess(() -> Component.translatable(
                                    "command.eca.set_health.report_created", absoluteReport.toString())
                                    .withStyle(ChatFormatting.AQUA), false);
                        } else {
                            source.sendFailure(Component.translatable(
                                    "command.eca.set_health.report_failed", entity.getDisplayName())
                                    .withStyle(ChatFormatting.RED));
                        }
                    }
                }
            }

            final int finalSuccessCount = successCount;
            final int finalSkippedCount = skippedCount;
            final float finalHealth = health;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.translatable(
                        "command.eca.set_health.success", finalSuccessCount, finalHealth)
                        .withStyle(ChatFormatting.GREEN), true);
            }

            if (finalSkippedCount > 0) {
                source.sendSuccess(() -> Component.translatable(
                        "command.eca.set_health.skipped", finalSkippedCount)
                        .withStyle(ChatFormatting.YELLOW), false);
            }

            return finalSuccessCount;

        } catch (Exception e) {
            source.sendFailure(Component.translatable(
                    "command.eca.set_health.command_error", e.getMessage())
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
    }
}
