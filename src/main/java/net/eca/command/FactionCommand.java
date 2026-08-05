package net.eca.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.eca.api.EcaAPI;
import net.eca.util.faction.Faction;
import net.eca.util.faction.FactionManager;
import net.eca.util.faction.FactionMember;
import net.eca.util.faction.FactionRelation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

// 阵营命令
public class FactionCommand {

    // 颜色预设：名称 → ARGB int
    private static final Map<String, Integer> COLOR_PRESETS = new LinkedHashMap<>();
    static {
        COLOR_PRESETS.put("red",     0xFFFF0000);
        COLOR_PRESETS.put("green",   0xFF00FF00);
        COLOR_PRESETS.put("blue",    0xFF0000FF);
        COLOR_PRESETS.put("yellow",  0xFFFFFF00);
        COLOR_PRESETS.put("cyan",    0xFF00FFFF);
        COLOR_PRESETS.put("magenta", 0xFFFF00FF);
        COLOR_PRESETS.put("white",   0xFFFFFFFF);
        COLOR_PRESETS.put("black",   0xFF000000);
        COLOR_PRESETS.put("gray",    0xFF808080);
        COLOR_PRESETS.put("grey",    0xFF808080);
        COLOR_PRESETS.put("orange",  0xFFFF8800);
        COLOR_PRESETS.put("pink",    0xFFFFC0CB);
        COLOR_PRESETS.put("purple",  0xFF800080);
        COLOR_PRESETS.put("lime",    0xFF80FF00);
        COLOR_PRESETS.put("brown",   0xFF8B4513);
        COLOR_PRESETS.put("teal",    0xFF008080);
        COLOR_PRESETS.put("gold",    0xFFFFD700);
        COLOR_PRESETS.put("silver",  0xFFC0C0C0);
        COLOR_PRESETS.put("navy",    0xFF000080);
        COLOR_PRESETS.put("maroon",  0xFF800000);
        COLOR_PRESETS.put("olive",   0xFF808000);
        COLOR_PRESETS.put("aqua",    0xFF00FFFF);
        COLOR_PRESETS.put("coral",   0xFFFF7F50);
    }

    // 颜色补全建议提供器
    private static final SuggestionProvider<CommandSourceStack> COLOR_SUGGESTIONS =
        (ctx, builder) -> SharedSuggestionProvider.suggest(COLOR_PRESETS.keySet(), builder);

    // 关系补全建议提供器
    private static final SuggestionProvider<CommandSourceStack> RELATION_SUGGESTIONS =
        (ctx, builder) -> SharedSuggestionProvider.suggest(
            Stream.of(FactionRelation.values()).map(Enum::name), builder);

    // 阵营 ID 补全建议提供器，取自当前已注册阵营
    private static final SuggestionProvider<CommandSourceStack> FACTION_ID_SUGGESTIONS =
        (ctx, builder) -> SharedSuggestionProvider.suggest(EcaAPI.getAllFactions().keySet(), builder);

    // 注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("faction")
            // /eca faction create <id> <displayName> [color]
            .then(Commands.literal("create")
                .then(Commands.argument("id", StringArgumentType.word())
                    .then(Commands.argument("displayName", StringArgumentType.greedyString())
                        .executes(ctx -> createFaction(ctx, null))
                        .then(Commands.argument("color", StringArgumentType.word())
                            .suggests(COLOR_SUGGESTIONS)
                            .executes(FactionCommand::createFaction)
                        )
                    )
                )
            )
            // /eca faction remove <id>
            .then(Commands.literal("remove")
                .then(Commands.argument("id", StringArgumentType.word())
                    .suggests(FACTION_ID_SUGGESTIONS)
                    .executes(FactionCommand::removeFaction)
                )
            )
            // /eca faction join <factionId> [targets]
            .then(Commands.literal("join")
                .then(Commands.argument("factionId", StringArgumentType.word())
                    .suggests(FACTION_ID_SUGGESTIONS)
                    .executes(FactionCommand::joinSelf)
                    .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(FactionCommand::joinTargets)
                    )
                )
            )
            // /eca faction leave [targets]
            .then(Commands.literal("leave")
                .executes(FactionCommand::leaveSelf)
                .then(Commands.argument("targets", EntityArgument.entities())
                    .executes(FactionCommand::leaveTargets)
                )
            )
            // /eca faction list
            .then(Commands.literal("list")
                .executes(FactionCommand::listFactions)
            )
            // /eca faction info [factionId]
            .then(Commands.literal("info")
                .executes(FactionCommand::infoSelf)
                .then(Commands.argument("factionId", StringArgumentType.word())
                    .suggests(FACTION_ID_SUGGESTIONS)
                    .executes(FactionCommand::infoFaction)
                )
            )
            // /eca faction relation <factionA> <factionB> <relation>
            .then(Commands.literal("relation")
                .then(Commands.argument("factionA", StringArgumentType.word())
                    .suggests(FACTION_ID_SUGGESTIONS)
                    .then(Commands.argument("factionB", StringArgumentType.word())
                        .suggests(FACTION_ID_SUGGESTIONS)
                        .then(Commands.argument("relation", StringArgumentType.word())
                            .suggests(RELATION_SUGGESTIONS)
                            .executes(FactionCommand::setRelation)
                        )
                    )
                )
            )
            // /eca faction leader <factionId> set|clear [targets]
            .then(Commands.literal("leader")
                .then(Commands.argument("factionId", StringArgumentType.word())
                    .suggests(FACTION_ID_SUGGESTIONS)
                    .executes(FactionCommand::showLeader)
                    .then(Commands.literal("set")
                        .executes(FactionCommand::setLeaderSelf)
                        .then(Commands.argument("targets", EntityArgument.entity())
                            .executes(FactionCommand::setLeaderTarget)
                        )
                    )
                    .then(Commands.literal("clear")
                        .executes(FactionCommand::clearLeader)
                    )
                )
            );
    }

    // ==================== leader ====================

    private static int showLeader(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String factionId = StringArgumentType.getString(context, "factionId");

        if (EcaAPI.getFaction(factionId) == null) {
            source.sendFailure(Component.literal("§cFaction '" + factionId + "' not found"));
            return 0;
        }

        FactionMember leader = EcaAPI.getFactionLeader(factionId);
        if (leader == null) {
            source.sendSuccess(() -> Component.literal(
                String.format("§7Faction '%s' has no leader", factionId)), false);
            return 0;
        }

        Entity resolved = EcaAPI.resolveFactionLeader(factionId, source.getServer());
        String state = resolved != null ? "§aonline" : "§7offline/unloaded";
        source.sendSuccess(() -> Component.literal(String.format(
            "§7Leader of '%s': §f%s §7(%s) %s", factionId, leader.getTypeId(), leader.getUuid(), state)
        ), false);
        return 1;
    }

    private static int setLeaderSelf(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Entity entity = source.getEntity();
        if (entity == null) {
            source.sendFailure(Component.literal("§cThis command must be run by an entity"));
            return 0;
        }
        return applyLeader(context, entity);
    }

    private static int setLeaderTarget(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            return applyLeader(context, EntityArgument.getEntity(context, "targets"));
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cFailed to resolve target: " + e.getMessage()));
            return 0;
        }
    }

    private static int applyLeader(CommandContext<CommandSourceStack> context, Entity leader) {
        CommandSourceStack source = context.getSource();
        String factionId = StringArgumentType.getString(context, "factionId");

        if (EcaAPI.getFaction(factionId) == null) {
            source.sendFailure(Component.literal("§cFaction '" + factionId + "' not found"));
            return 0;
        }
        if (!EcaAPI.setFactionLeader(factionId, leader, source.getLevel())) {
            source.sendFailure(Component.literal("§cFailed to set leader of '" + factionId + "'"));
            return 0;
        }

        String name = leader.getName().getString();
        source.sendSuccess(() -> Component.literal(String.format(
            "§a%s is now the leader of '%s' §7(joined the faction automatically if needed)", name, factionId)
        ), true);
        return 1;
    }

    private static int clearLeader(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String factionId = StringArgumentType.getString(context, "factionId");

        if (!EcaAPI.clearFactionLeader(factionId, source.getLevel())) {
            source.sendFailure(Component.literal(
                "§cFaction '" + factionId + "' not found or has no leader"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(
            "§aCleared the leader of '%s' §7(former leader remains a member)", factionId)), true);
        return 1;
    }

    // ==================== create ====================

    // 颜色字符串 → ARGB int 解析
    /**
     * Parse a color argument string to an ARGB int.
     * Accepts preset names (e.g. "red"), hex with optional prefix (e.g. "#FF0000", "0xAARRGGBB"),
     * or plain hex (e.g. "FF0000").
     *
     * @param input the color string, may be null (returns white)
     * @return the ARGB int, or null if the input could not be parsed
     */
    private static Integer parseColorArg(String input) {
        if (input == null) return 0xFFFFFFFF; // 默认白色
        // 预设名称
        Integer preset = COLOR_PRESETS.get(input.toLowerCase(Locale.ROOT));
        if (preset != null) return preset;
        // 移除前缀
        String hex = input;
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        } else if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        // 6 位 RGB → 补全 Alpha=FF
        if (hex.length() == 6) {
            hex = "FF" + hex;
        }
        // 8 位 ARGB
        if (hex.length() == 8) {
            try {
                return (int) Long.parseLong(hex, 16);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int createFaction(CommandContext<CommandSourceStack> context) {
        String colorStr = null;
        try {
            colorStr = StringArgumentType.getString(context, "color");
        } catch (IllegalArgumentException ignored) {
            // color 参数未提供时为 null，使用默认白色
        }
        return createFaction(context, colorStr);
    }

    private static int createFaction(CommandContext<CommandSourceStack> context, String colorStr) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        String displayName = StringArgumentType.getString(context, "displayName");

        Integer color = parseColorArg(colorStr);
        if (color == null) {
            source.sendFailure(Component.literal(
                "§cInvalid color: '" + colorStr + "'. Use a preset name (e.g. red, blue) "
                + "or hex (e.g. #FF0000, 0xFFFF0000, FF0000)."
            ));
            return 0;
        }

        try {
            if (EcaAPI.getFaction(id) != null) {
                source.sendFailure(Component.literal("§cFaction '" + id + "' already exists"));
                return 0;
            }
            EcaAPI.createFaction(id, displayName, color, source.getLevel());
            String colorHex = String.format("#%08X", color);
            source.sendSuccess(() -> Component.literal(
                String.format("§aCreated faction '%s' (%s) with color %s",
                    displayName, id, colorHex)
            ), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cFailed to create faction: " + e.getMessage()));
            return 0;
        }
    }

    // ==================== remove ====================

    private static int removeFaction(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");

        try {
            Faction removed = EcaAPI.getFaction(id);
            if (removed == null) {
                source.sendFailure(Component.literal("§cFaction '" + id + "' not found"));
                return 0;
            }
            int memberCount = EcaAPI.getFactionMembers(source.getLevel(), id).size();
            EcaAPI.removeFaction(id, source.getLevel());
            int finalCount = memberCount;
            source.sendSuccess(() -> Component.literal(
                String.format("§aRemoved faction '%s' (%s). %d entity(s) still hold the faction tag.",
                    removed.getDisplayName(), id, finalCount)
            ), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cFailed to remove faction: " + e.getMessage()));
            return 0;
        }
    }

    // ==================== join ====================

    private static int joinSelf(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Entity entity = source.getEntity();
        String factionId = StringArgumentType.getString(context, "factionId");

        if (entity == null) {
            source.sendFailure(Component.literal("§cThis command must be run by an entity"));
            return 0;
        }

        Faction faction = EcaAPI.getFaction(factionId);
        String displayName = faction != null ? faction.getDisplayName() : factionId;

        EcaAPI.joinFaction(entity, factionId);
        source.sendSuccess(() -> Component.literal(
            String.format("§aJoined faction '%s'", displayName)
        ), true);
        return 1;
    }

    private static int joinTargets(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String factionId = StringArgumentType.getString(context, "factionId");

        try {
            Collection<? extends Entity> targets = EcaCommandSelector.getEntities(context);
            Faction faction = EcaAPI.getFaction(factionId);
            String displayName = faction != null ? faction.getDisplayName() : factionId;
            int count = 0;

            for (Entity entity : targets) {
                EcaAPI.joinFaction(entity, factionId);
                count++;
            }

            int finalCount = count;
            source.sendSuccess(() -> Component.literal(
                String.format("§a%d entity(s) joined faction '%s'", finalCount, displayName)
            ), true);
            return count;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cFailed to join faction: " + e.getMessage()));
            return 0;
        }
    }

    // ==================== leave ====================

    private static int leaveSelf(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Entity entity = source.getEntity();

        if (entity == null) {
            source.sendFailure(Component.literal("§cThis command must be run by an entity"));
            return 0;
        }

        String prev = EcaAPI.getEntityFaction(entity);
        EcaAPI.leaveFaction(entity);

        if (prev != null) {
            Faction f = EcaAPI.getFaction(prev);
            String displayName = f != null ? f.getDisplayName() : prev;
            source.sendSuccess(() -> Component.literal(
                String.format("§aLeft faction '%s'", displayName)
            ), true);
        } else {
            source.sendSuccess(() -> Component.literal("§eYou are not in any faction"), false);
        }
        return 1;
    }

    private static int leaveTargets(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EcaCommandSelector.getEntities(context);
            int count = 0;

            for (Entity entity : targets) {
                EcaAPI.leaveFaction(entity);
                count++;
            }

            int finalCount = count;
            source.sendSuccess(() -> Component.literal(
                String.format("§a%d entity(s) left their factions", finalCount)
            ), true);
            return count;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cFailed to leave faction: " + e.getMessage()));
            return 0;
        }
    }

    // ==================== list ====================

    private static int listFactions(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Map<String, Faction> factions = EcaAPI.getAllFactions();

        if (factions.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§eNo factions registered"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
            String.format("§a=== Factions (%d total) ===", factions.size())
        ), false);

        int index = 1;
        for (Faction faction : factions.values()) {
            int memberCount = EcaAPI.getFactionMembers(source.getLevel(), faction.getId()).size();
            String colorHex = String.format("#%08X", faction.getColor());
            final int i = index;
            source.sendSuccess(() -> Component.literal(
                String.format("§b%d. §f%s §7(%s) §8- %d members §8| %s",
                    i, faction.getDisplayName(), faction.getId(), memberCount, colorHex)
            ), false);
            index++;
        }

        return factions.size();
    }

    // ==================== info ====================

    private static int infoSelf(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Entity entity = source.getEntity();

        if (entity == null) {
            source.sendFailure(Component.literal("§cThis command must be run by an entity"));
            return 0;
        }

        String factionId = EcaAPI.getEntityFaction(entity);
        if (factionId == null) {
            source.sendSuccess(() -> Component.literal("§eYou are not in any faction"), false);
            return 0;
        }

        return showFactionInfo(source, factionId);
    }

    private static int infoFaction(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String factionId = StringArgumentType.getString(context, "factionId");
        return showFactionInfo(source, factionId);
    }

    private static int showFactionInfo(CommandSourceStack source, String factionId) {
        Faction faction = EcaAPI.getFaction(factionId);
        if (faction == null) {
            source.sendFailure(Component.literal("§cFaction '" + factionId + "' not found"));
            return 0;
        }

        Collection<FactionMember> records = EcaAPI.getFactionMemberRecords(factionId);
        String colorHex = String.format("#%08X", faction.getColor());

        source.sendSuccess(() -> Component.literal(
            String.format("§a=== Faction: %s ===", faction.getDisplayName())
        ), false);
        source.sendSuccess(() -> Component.literal(
            String.format("§7  ID: §f%s", faction.getId())
        ), false);
        source.sendSuccess(() -> Component.literal(
            String.format("§7  Color: §f%s", colorHex)
        ), false);
        source.sendSuccess(() -> Component.literal(
            String.format("§7  Default relation: §f%s", faction.getDefaultRelation().name())
        ), false);

        // 关系覆盖
        Map<String, FactionRelation> relations = faction.getRelations();
        if (!relations.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7  Relations:"), false);
            for (Map.Entry<String, FactionRelation> entry : relations.entrySet()) {
                Faction other = EcaAPI.getFaction(entry.getKey());
                String otherName = other != null ? other.getDisplayName() : entry.getKey();
                source.sendSuccess(() -> Component.literal(
                    String.format("§7    → %s: §f%s", otherName, entry.getValue().name())
                ), false);
            }
        }

        // 首领
        FactionMember leader = faction.getLeader();
        source.sendSuccess(() -> Component.literal(
            leader == null
                ? "§7  Leader: §8none"
                : String.format("§7  Leader: §f%s §7(%s)", leader.getTypeId(), leader.getUuid())
        ), false);

        // 成员列表走成员表，未加载与跨维度的成员同样可见
        source.sendSuccess(() -> Component.literal(
            String.format("§7  Members (%d):", records.size())
        ), false);
        for (FactionMember member : records) {
            boolean loaded = source.getLevel().getEntity(member.getUuid()) != null;
            source.sendSuccess(() -> Component.literal(
                String.format("§b    - §f%s §7%s %s",
                    member.getTypeId(),
                    member.getUuid(),
                    loaded ? "§aloaded" : "§8unloaded")
            ), false);
        }

        return 1;
    }

    // ==================== relation ====================

    private static int setRelation(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String factionA = StringArgumentType.getString(context, "factionA");
        String factionB = StringArgumentType.getString(context, "factionB");
        String relationStr = StringArgumentType.getString(context, "relation");

        FactionRelation relation;
        try {
            relation = FactionRelation.valueOf(relationStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(
                "§cInvalid relation: '" + relationStr + "'. Valid: HOSTILE, NEUTRAL, FRIENDLY, SAME_FACTION"
            ));
            return 0;
        }

        if (EcaAPI.getFaction(factionA) == null) {
            source.sendFailure(Component.literal("§cFaction '" + factionA + "' not found"));
            return 0;
        }
        if (EcaAPI.getFaction(factionB) == null) {
            source.sendFailure(Component.literal("§cFaction '" + factionB + "' not found"));
            return 0;
        }

        // SAME_FACTION 由“双方阵营 ID 相同”派生，存成跨阵营覆盖永远不会被读到，
        // 因此在命令层把它解释为唯一能实现该语义的操作：把 B 并入 A
        if (relation == FactionRelation.SAME_FACTION) {
            return mergeFactions(source, factionA, factionB);
        }

        EcaAPI.setFactionRelation(factionA, factionB, relation, source.getLevel());
        Faction fA = EcaAPI.getFaction(factionA);
        Faction fB = EcaAPI.getFaction(factionB);
        source.sendSuccess(() -> Component.literal(
            String.format("§aSet relation: %s → %s = %s",
                fA.getDisplayName(), fB.getDisplayName(), relation.name())
        ), true);
        return 1;
    }

    // 把阵营 B 并入阵营 A，B 随之解散
    private static int mergeFactions(CommandSourceStack source, String factionAId, String factionBId) {
        if (factionAId.equals(factionBId)) {
            source.sendFailure(Component.literal(
                "§cCannot merge faction '" + factionAId + "' into itself"));
            return 0;
        }

        String nameA = EcaAPI.getFaction(factionAId).getDisplayName();
        String nameB = EcaAPI.getFaction(factionBId).getDisplayName();

        int moved = EcaAPI.mergeFactions(factionAId, factionBId, source.getLevel());
        if (moved < 0) {
            source.sendFailure(Component.literal(String.format(
                "§cFailed to merge '%s' into '%s'", factionBId, factionAId)));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(String.format(
            "§aMerged '%s' into '%s': %d member(s) moved. §7Faction '%s' no longer exists.",
            nameB, nameA, moved, factionBId)
        ), true);
        return 1;
    }
}
