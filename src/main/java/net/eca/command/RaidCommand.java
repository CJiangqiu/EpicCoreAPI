package net.eca.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.eca.api.EcaAPI;
import net.eca.util.raid.RaidDefinition;
import net.eca.util.raid.RaidInstance;
import net.eca.util.raid.RaidManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 袭击命令
public class RaidCommand {

    // 袭击定义 ID 补全（注册表中的模板）
    private static final SuggestionProvider<CommandSourceStack> DEFINITION_SUGGESTIONS =
        (ctx, builder) -> SharedSuggestionProvider.suggest(RaidManager.getAllDefinitions().keySet(), builder);

    // 活跃袭击实例 ID 补全（当前维度正在进行的具体袭击）
    private static final SuggestionProvider<CommandSourceStack> INSTANCE_SUGGESTIONS =
        (ctx, builder) -> {
            List<String> ids = new ArrayList<>();
            for (RaidInstance raid : EcaAPI.getActiveRaids(ctx.getSource().getLevel())) {
                ids.add(String.valueOf(raid.getId()));
            }
            return SharedSuggestionProvider.suggest(ids, builder);
        };

    // 注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("raid")
            // /eca raid defs
            .then(Commands.literal("defs")
                .executes(RaidCommand::listDefinitions)
            )
            // /eca raid list
            .then(Commands.literal("list")
                .executes(RaidCommand::listActive)
            )
            // /eca raid start <definitionId> [pos]
            .then(Commands.literal("start")
                .then(Commands.argument("definitionId", StringArgumentType.word())
                    .suggests(DEFINITION_SUGGESTIONS)
                    .executes(ctx -> start(ctx, false))
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> start(ctx, true))
                    )
                )
            )
            // /eca raid startat <definitionId> <pos>
            .then(Commands.literal("startat")
                .then(Commands.argument("definitionId", StringArgumentType.word())
                    .suggests(DEFINITION_SUGGESTIONS)
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(RaidCommand::startAt)
                    )
                )
            )
            // /eca raid info <instanceId>
            .then(Commands.literal("info")
                .then(Commands.argument("instanceId", IntegerArgumentType.integer())
                    .suggests(INSTANCE_SUGGESTIONS)
                    .executes(RaidCommand::info)
                )
            )
            // /eca raid end <instanceId> <victory|defeat>
            .then(Commands.literal("end")
                .then(Commands.argument("instanceId", IntegerArgumentType.integer())
                    .suggests(INSTANCE_SUGGESTIONS)
                    .then(Commands.literal("victory")
                        .executes(ctx -> end(ctx, true))
                    )
                    .then(Commands.literal("defeat")
                        .executes(ctx -> end(ctx, false))
                    )
                )
            );
    }

    // ==================== defs ====================

    private static int listDefinitions(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Map<String, RaidDefinition> definitions = RaidManager.getAllDefinitions();

        if (definitions.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7No raid definitions registered"), false);
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("§eRegistered raid definitions (").append(definitions.size()).append("):");
        for (Map.Entry<String, RaidDefinition> entry : definitions.entrySet()) {
            RaidDefinition def = entry.getValue();
            sb.append("\n§7 - §f").append(entry.getKey())
              .append(" §7(").append(def.getDisplayName()).append(")");
            List<?> waves = def.getWaves();
            sb.append(def.isEndless() ? " §6endless" : " §7" + (waves == null ? 0 : waves.size()) + " waves");
            String faction = def.getRaiderFactionId();
            if (faction != null && !faction.isEmpty()) {
                sb.append(" §7faction=").append(faction);
            }
        }
        String message = sb.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return definitions.size();
    }

    // ==================== list ====================

    private static int listActive(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<RaidInstance> raids = EcaAPI.getActiveRaids(source.getLevel());

        if (raids.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7No active raids in this dimension"), false);
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("§eActive raids in this dimension (").append(raids.size()).append("):");
        for (RaidInstance raid : raids) {
            sb.append("\n§7 §f#").append(raid.getId())
              .append(" §7").append(raid.getDefinitionId())
              .append(" §7@ ").append(formatPos(raid.getCenter()))
              .append(" §b").append(raid.getStatus())
              .append(" §7").append(formatWave(raid))
              .append("§7, ").append(raid.getAliveRaiderCount()).append(" alive");
        }
        String message = sb.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return raids.size();
    }

    // ==================== start ====================

    private static int start(CommandContext<CommandSourceStack> context, boolean hasPos)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String definitionId = StringArgumentType.getString(context, "definitionId");
        BlockPos pos = hasPos
                ? BlockPosArgument.getLoadedBlockPos(context, "pos")
                : BlockPos.containing(source.getPosition());

        if (RaidManager.getDefinition(definitionId) == null) {
            source.sendFailure(Component.literal("§cUnknown raid definition '" + definitionId + "'"));
            return 0;
        }

        RaidInstance raid = EcaAPI.startRaid(source.getLevel(), pos, definitionId);
        if (raid == null) {
            source.sendFailure(Component.literal(
                "§c" + formatPos(pos) + " is not inside the target structure of '" + definitionId
                    + "'. Use /eca raid startat to force a center."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(String.format(
            "§aStarted raid '%s' as §f#%d §aat %s", definitionId, raid.getId(), formatPos(raid.getCenter()))
        ), true);
        return 1;
    }

    private static int startAt(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String definitionId = StringArgumentType.getString(context, "definitionId");
        BlockPos center = BlockPosArgument.getLoadedBlockPos(context, "pos");

        RaidInstance raid = EcaAPI.startRaidAt(source.getLevel(), center, definitionId);
        if (raid == null) {
            source.sendFailure(Component.literal("§cUnknown raid definition '" + definitionId + "'"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(String.format(
            "§aStarted raid '%s' as §f#%d §aat %s §7(structure lookup skipped)",
            definitionId, raid.getId(), formatPos(center))
        ), true);
        return 1;
    }

    // ==================== info ====================

    private static int info(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int instanceId = IntegerArgumentType.getInteger(context, "instanceId");
        ServerLevel level = source.getLevel();

        RaidInstance raid = EcaAPI.getRaid(level, instanceId);
        if (raid == null) {
            source.sendFailure(Component.literal("§cNo active raid with id " + instanceId + " in this dimension"));
            return 0;
        }

        RaidDefinition def = raid.getDefinition();
        StringBuilder sb = new StringBuilder();
        sb.append("§eRaid §f#").append(raid.getId()).append(" §7(").append(raid.getDefinitionId()).append(")");
        sb.append("\n§7 status: §b").append(raid.getStatus());
        sb.append("\n§7 center: §f").append(formatPos(raid.getCenter()));
        sb.append("\n§7 waves: §f").append(formatWave(raid));
        sb.append("\n§7 raiders alive: §f").append(raid.getAliveRaiderCount());
        sb.append("\n§7 ticks active: §f").append(raid.getTicksActive());
        sb.append("\n§7 target intact: §f").append(raid.isTargetIntact(level));
        if (def != null) {
            sb.append("\n§7 raider faction: §f")
              .append(def.getRaiderFactionId() == null ? "none" : def.getRaiderFactionId());
        }

        String message = sb.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    // ==================== end ====================

    private static int end(CommandContext<CommandSourceStack> context, boolean victory) {
        CommandSourceStack source = context.getSource();
        int instanceId = IntegerArgumentType.getInteger(context, "instanceId");
        ServerLevel level = source.getLevel();

        RaidInstance raid = EcaAPI.getRaid(level, instanceId);
        if (raid == null) {
            source.sendFailure(Component.literal("§cNo active raid with id " + instanceId + " in this dimension"));
            return 0;
        }

        // 清场前记录人数：endRaid 会 discard 全部存活袭击者
        int cleared = raid.getAliveRaiderCount();
        if (!EcaAPI.endRaid(level, raid, victory)) {
            source.sendFailure(Component.literal("§cFailed to end raid #" + instanceId));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(String.format(
            "§aEnded raid §f#%d §aas %s, cleared %d raider(s)",
            instanceId, victory ? "§evictory" : "§cdefeat", cleared)
        ), true);
        return 1;
    }

    // ==================== 工具 ====================

    private static String formatPos(BlockPos pos) {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }

    private static String formatWave(RaidInstance raid) {
        RaidDefinition def = raid.getDefinition();
        if (def != null && def.isEndless()) {
            return "wave " + raid.getWavesSpawned() + " (endless)";
        }
        return "wave " + raid.getWavesSpawned() + "/" + raid.getWaveCount();
    }
}
