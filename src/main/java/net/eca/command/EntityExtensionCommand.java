package net.eca.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.api.EcaAPI;
import net.eca.network.EntityExtensionOverridePacket.SkyboxData;
import net.eca.util.entity_extension.EntityExtension;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;

import java.util.Map;
@SuppressWarnings("removal")
public class EntityExtensionCommand {

    private static final String[] SKYBOX_PRESETS = {
        "the_last_end", "dream_sakura", "forest", "ocean",
        "storm", "volcano", "arcane", "aurora",
        "hacker", "starlight", "cosmos", "black_hole"
    };

    // 注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        LiteralArgumentBuilder<CommandSourceStack> setSkybox = Commands.literal("set_skybox");
        for (String preset : SKYBOX_PRESETS) {
            setSkybox.then(Commands.literal(preset)
                .executes(ctx -> applySkyboxPreset(ctx, preset))
            );
        }

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
                .executes(EntityExtensionCommand::clearAll)
            )
            .then(setSkybox);
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

    // 清空当前维度活跃表 + 全局效果覆盖
    private static int clearAll(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            ServerLevel level = source.getLevel();
            EcaAPI.clearActiveEntityExtensionTable(level);
            EcaAPI.clearAllGlobalEffects(level);
            source.sendSuccess(() -> Component.literal(
                String.format("§aCleared entity extensions and global effects in %s", level.dimension().location())
            ), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }

    // 设置天空盒预设
    private static int applySkyboxPreset(CommandContext<CommandSourceStack> context, String preset) {
        CommandSourceStack source = context.getSource();

        try {
            ServerLevel level = source.getLevel();
            ResourceLocation presetId = new ResourceLocation("eca", preset);

            SkyboxData data = new SkyboxData(
                false, null,
                true, presetId,
                1.0f, 100.0f, 1.0f,
                1.0f, 1.0f, 1.0f
            );
            EcaAPI.setGlobalSkybox(level, data);
            source.sendSuccess(() -> Component.literal(
                String.format("§aSet global skybox to %s in %s", preset, level.dimension().location())
            ), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }
}
