package net.eca.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.api.EcaAPI;
import net.eca.util.entity_extension.EntityExtension;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;

import java.util.Map;

// 实体扩展命令
public class EntityExtensionCommand {

    // 注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("entityExtension")
            .then(Commands.literal("get_registry")
                .executes(EntityExtensionCommand::getRegistry)
            )
            .then(Commands.literal("get_active")
                .executes(EntityExtensionCommand::getActive)
            )
            .then(Commands.literal("get_current")
                .executes(EntityExtensionCommand::getCurrent)
            )
            .then(Commands.literal("clear")
                .executes(EntityExtensionCommand::clearActive)
            );
    }

    // 获取注册总表
    private static int getRegistry(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Map<EntityType<?>, EntityExtension> registry = EcaAPI.getEntityExtensionRegistry();
            if (registry.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§eEntity extension registry is empty"), false);
                return 0;
            }

            source.sendSuccess(() -> Component.literal("§aEntity extension registry:"), false);
            registry.forEach((type, extension) -> {
                ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                int priority = extension != null ? extension.getPriority() : 0;
                String line = String.format("§7- %s : priority=%d", typeId, priority);
                source.sendSuccess(() -> Component.literal(line), false);
            });
            return registry.size();
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }

    // 获取当前维度活跃列表
    private static int getActive(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            ServerLevel level = source.getLevel();
            Map<EntityType<?>, Integer> active = EcaAPI.getActiveEntityExtensionTypes(level);
            if (active.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§eNo active entity extension types"), false);
                return 0;
            }

            source.sendSuccess(() -> Component.literal(
                String.format("§aActive entity extension types in %s:", level.dimension().location())
            ), false);

            active.forEach((type, count) -> {
                ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                String line = String.format("§7- %s : count=%d", typeId, count);
                source.sendSuccess(() -> Component.literal(line), false);
            });
            return active.size();
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }

    // 获取当前生效扩展
    private static int getCurrent(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            ServerLevel level = source.getLevel();
            EntityExtension extension = EcaAPI.getActiveEntityExtension(level);
            if (extension == null) {
                source.sendSuccess(() -> Component.literal("§eNo active entity extension"), false);
                return 0;
            }

            EntityType<?> type = extension.getEntityType();
            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            String line = String.format("§aActive extension: %s (priority=%d, class=%s)",
                typeId, extension.getPriority(), extension.getClass().getName());
            source.sendSuccess(() -> Component.literal(line), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }

    // 清空当前维度活跃表
    private static int clearActive(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            ServerLevel level = source.getLevel();
            EcaAPI.clearActiveEntityExtensionTable(level);
            source.sendSuccess(() -> Component.literal(
                String.format("§aCleared active entity extension table in %s", level.dimension().location())
            ), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }
}
