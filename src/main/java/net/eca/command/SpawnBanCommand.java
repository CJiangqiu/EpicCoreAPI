package net.eca.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.api.EcaAPI;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// 禁生成命令
public class SpawnBanCommand {

    // 注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("banSpawn")
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                    .executes(SpawnBanCommand::addBan)
                )
            )
            .then(Commands.literal("clear")
                .executes(SpawnBanCommand::clearBans)
            );
    }

    // 添加禁生成（根据选中实体的类型）
    private static int addBan(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            ServerLevel level = source.getLevel();
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
            int seconds = IntegerArgumentType.getInteger(context, "seconds");

            // 收集所有选中实体的类型（去重）
            Set<EntityType<?>> types = new HashSet<>();
            for (Entity entity : targets) {
                types.add(entity.getType());
            }

            int successCount = 0;
            for (EntityType<?> type : types) {
                if (EcaAPI.banSpawn(level, type, seconds)) {
                    successCount++;
                    ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                    source.sendSuccess(() -> Component.literal(
                        String.format("§aAdded spawn ban for %s for %d seconds", typeId, seconds)
                    ), true);
                }
            }

            return successCount;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }

    // 清除所有禁生成
    private static int clearBans(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            ServerLevel level = source.getLevel();
            Map<EntityType<?>, Integer> bans = EcaAPI.getAllSpawnBans(level);
            int count = bans.size();

            EcaAPI.unbanAllSpawns(level);

            source.sendSuccess(() -> Component.literal(
                String.format("§aCleared %d spawn ban(s) in %s", count, level.dimension().location())
            ), true);

            return count;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }
}
