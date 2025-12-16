package net.eca.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.eca.api.EcaAPI;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.Collection;

//移除实体命令
public class RemoveEntityCommand {

    //移除原因提示
    private static final SuggestionProvider<CommandSourceStack> REMOVAL_REASON_SUGGESTIONS =
        (context, builder) -> SharedSuggestionProvider.suggest(
            new String[]{"KILLED", "DISCARDED", "UNLOADED_TO_CHUNK", "UNLOADED_WITH_PLAYER", "CHANGED_DIMENSION"},
            builder
        );

    //注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("remove")
            .then(Commands.argument("targets", EntityArgument.entities())
                //无参数版本，使用默认原因 KILLED
                .executes(context -> removeEntities(context, null))
                //带原因参数版本
                .then(Commands.argument("reason", StringArgumentType.word())
                    .suggests(REMOVAL_REASON_SUGGESTIONS)
                    .executes(context -> removeEntities(context, StringArgumentType.getString(context, "reason")))
                )
            );
    }

    //执行移除
    private static int removeEntities(CommandContext<CommandSourceStack> context, String reasonString) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");

            //解析移除原因
            Entity.RemovalReason reason;
            if (reasonString == null || reasonString.isEmpty()) {
                reason = Entity.RemovalReason.KILLED;
            } else {
                try {
                    reason = Entity.RemovalReason.valueOf(reasonString.toUpperCase());
                } catch (IllegalArgumentException e) {
                    source.sendFailure(Component.literal(
                        "§cInvalid removal reason: " + reasonString + ". Valid options: KILLED, DISCARDED, UNLOADED_TO_CHUNK, UNLOADED_WITH_PLAYER, CHANGED_DIMENSION"
                    ));
                    return 0;
                }
            }

            int successCount = 0;

            for (Entity entity : targets) {
                try {
                    EcaAPI.removeEntity(entity, reason);
                    successCount++;
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cFailed to remove " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;
            final String finalReasonName = reason.name();

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aRemoved %d %s (Reason: %s)",
                        finalSuccessCount,
                        finalSuccessCount == 1 ? "entity" : "entities",
                        finalReasonName)
                ), true);
            }

            return finalSuccessCount;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }
}
