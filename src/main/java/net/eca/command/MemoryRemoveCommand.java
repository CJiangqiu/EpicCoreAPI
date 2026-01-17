package net.eca.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.api.EcaAPI;
import net.eca.config.EcaConfiguration;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.Collection;

//使用LWJGL相关API清除实体命令（需要开启激进攻击逻辑配置）
public class MemoryRemoveCommand {

    //注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("memoryRemove")
            .then(Commands.argument("targets", EntityArgument.entities())
                .executes(MemoryRemoveCommand::memoryRemoveEntities)
            );
    }

    //执行清除
    private static int memoryRemoveEntities(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        //检查配置
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()) {
            source.sendFailure(Component.literal(
                "§cmemoryRemove requires Attack Radical Logic to be enabled in config"
            ));
            return 0;
        }

        try {
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");

            int successCount = 0;
            int failCount = 0;

            for (Entity entity : targets) {
                boolean success = EcaAPI.memoryRemoveEntity(entity);
                if (success) {
                    successCount++;
                } else {
                    failCount++;
                }
            }

            final int finalSuccessCount = successCount;
            final int finalFailCount = failCount;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aRemoved %d %s via LWJGL API",
                        finalSuccessCount,
                        finalSuccessCount == 1 ? "entity" : "entities")
                ), true);
            }

            if (finalFailCount > 0) {
                source.sendFailure(Component.literal(
                    String.format("§cFailed to remove %d %s",
                        finalFailCount,
                        finalFailCount == 1 ? "entity" : "entities")
                ));
            }

            return finalSuccessCount;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }
}
