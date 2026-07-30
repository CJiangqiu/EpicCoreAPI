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

//强制实体受伤命令
public class HurtCommand {

    //注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("hurt")
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("amount", FloatArgumentType.floatArg(0.0f))
                    .executes(HurtCommand::hurt)
                )
            );
    }

    //执行强制受伤
    private static int hurt(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EcaCommandSelector.getEntities(context);
            float amount = FloatArgumentType.getFloat(context, "amount");

            int successCount = 0;
            int skippedCount = 0;

            for (Entity entity : targets) {
                if (!(entity instanceof LivingEntity livingEntity)) {
                    skippedCount++;
                    continue;
                }

                try {
                    /* 执行者是生物时按其身份构造伤害源，使掉落与经验归属给它；
                       控制台与命令方块没有实体身份，退回通用伤害源。 */
                    Entity executor = source.getEntity();
                    boolean success = executor instanceof LivingEntity attacker
                        ? EcaAPI.hurt(livingEntity, attacker, amount)
                        : EcaAPI.hurt(livingEntity, source.getLevel().damageSources().generic(), amount);
                    if (success) {
                        successCount++;
                    } else {
                        source.sendFailure(Component.literal(
                            "§cFailed to hurt " + entity.getName().getString()
                        ));
                    }
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cError hurting " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;
            final int finalSkippedCount = skippedCount;
            final float finalAmount = amount;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aDealt %.1f damage to %d %s",
                        finalAmount,
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
}
