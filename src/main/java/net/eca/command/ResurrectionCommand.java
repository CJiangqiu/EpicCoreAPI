package net.eca.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.util.ResurrectionManager;
import net.eca.util.EntityUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Command handler for {@code /eca resurrection}.
 * Controls the standalone {@link ResurrectionManager} daemon thread and its tracked entity set.
 * <pre>
 * /eca resurrection start              — start the daemon thread
 * /eca resurrection stop               — stop the daemon thread
 * /eca resurrection status             — show thread state + counts
 * /eca resurrection add <targets>       — add entities to tracking
 * /eca resurrection remove <targets>    — remove entities from tracking
 * /eca resurrection list               — list tracked entities with container status
 * /eca resurrection check <target>      — one-shot container check
 * /eca resurrection revive <target>     — manual force-revive
 * /eca resurrection interval <ms>       — set poll interval (100~10000)
 * </pre>
 */
public class ResurrectionCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("resurrection")
            .then(Commands.literal("start")
                .executes(ResurrectionCommand::start))
            .then(Commands.literal("stop")
                .executes(ResurrectionCommand::stop))
            .then(Commands.literal("status")
                .executes(ResurrectionCommand::status))
            .then(Commands.literal("add")
                .then(Commands.argument("targets", EntityArgument.entities())
                    .executes(ResurrectionCommand::add)))
            .then(Commands.literal("remove")
                .then(Commands.argument("targets", EntityArgument.entities())
                    .executes(ResurrectionCommand::remove)))
            .then(Commands.literal("list")
                .executes(ResurrectionCommand::list))
            .then(Commands.literal("check")
                .then(Commands.argument("target", EntityArgument.entity())
                    .executes(ResurrectionCommand::check)))
            .then(Commands.literal("revive")
                .then(Commands.argument("target", EntityArgument.entity())
                    .executes(ResurrectionCommand::revive)))
            .then(Commands.literal("interval")
                .then(Commands.argument("ms", IntegerArgumentType.integer(100, 10000))
                    .executes(ResurrectionCommand::interval)));
    }

    private static int start(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        ResurrectionManager.start();
        long interval = ResurrectionManager.getPollIntervalMs();
        int tracked = ResurrectionManager.getTrackedCount();

        source.sendSuccess(() -> Component.literal(
            String.format("§aResurrectionManager daemon started. poll=%dms tracked=%d", interval, tracked)
        ), true);
        return tracked;
    }

    private static int stop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        long revived = ResurrectionManager.getTotalRevivedCount();
        long checks = ResurrectionManager.getTotalCheckCount();
        ResurrectionManager.stop();

        source.sendSuccess(() -> Component.literal(
            String.format("§eResurrectionManager daemon stopped. totalRevived=%d totalChecks=%d", revived, checks)
        ), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        boolean running = ResurrectionManager.isRunning();
        long revived = ResurrectionManager.getTotalRevivedCount();
        long checks = ResurrectionManager.getTotalCheckCount();
        long interval = ResurrectionManager.getPollIntervalMs();
        int tracked = ResurrectionManager.getTrackedCount();
        int inFlight = ResurrectionManager.getTrackedUUIDs().size(); // approximate

        String stateColor = running ? "§a" : "§c";
        String stateText = running ? "RUNNING" : "STOPPED";

        source.sendSuccess(() -> Component.literal(
            String.format("%s[ResurrectionManager] state=%s§r%s poll=%dms tracked=%d totalRevived=%d totalChecks=%d",
                "§6", stateColor, stateText, interval, tracked, revived, checks)
        ), false);
        return tracked;
    }

    private static int add(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EcaCommandSelector.getEntities(context);
            int added = 0;
            for (Entity entity : targets) {
                if (!ResurrectionManager.isTracked(entity.getUUID())) {
                    ResurrectionManager.add(entity);
                    added++;
                }
            }

            final int finalAdded = added;
            source.sendSuccess(() -> Component.literal(
                String.format("§aAdded %d entities to ResurrectionManager tracking (total=%d)",
                    finalAdded, ResurrectionManager.getTrackedCount())
            ), true);
            return added;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c" + e.getMessage()));
            return 0;
        }
    }

    private static int remove(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EcaCommandSelector.getEntities(context);
            int removed = 0;
            for (Entity entity : targets) {
                if (ResurrectionManager.isTracked(entity.getUUID())) {
                    ResurrectionManager.remove(entity);
                    removed++;
                }
            }

            final int finalRemoved = removed;
            source.sendSuccess(() -> Component.literal(
                String.format("§eRemoved %d entities from ResurrectionManager tracking (total=%d)",
                    finalRemoved, ResurrectionManager.getTrackedCount())
            ), true);
            return removed;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c" + e.getMessage()));
            return 0;
        }
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();

        Set<UUID> tracked = ResurrectionManager.getTrackedUUIDs();
        if (tracked.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§eNo entities tracked by ResurrectionManager"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
            String.format("§6=== Tracked Entities (%d) ===", tracked.size())
        ), false);

        int index = 1;
        for (UUID uuid : tracked) {
            Entity entity = EntityUtil.getEntity(level, uuid);
            String name = entity != null ? entity.getName().getString() : "Unknown";
            int id = entity != null ? entity.getId() : -1;
            boolean alive = entity != null && entity.isAlive();

            final int finalIndex = index;
            String aliveMark = alive ? "§a✓" : "§c✗";
            source.sendSuccess(() -> Component.literal(
                String.format("§b%d. §f%s %s §7(ID=%d UUID=%s)", finalIndex, name, aliveMark, id, uuid)
            ), false);

            if (entity != null) {
                Map<String, Boolean> status = ResurrectionManager.check(level, uuid);
                for (Map.Entry<String, Boolean> entry : orderKeys(status).entrySet()) {
                    boolean ok = Boolean.TRUE.equals(entry.getValue());
                    String mark = ok ? "§a✓" : "§c✗";
                    source.sendSuccess(() -> Component.literal(
                        String.format("§7    %s %s §8(%s)", mark, entry.getKey(), ok ? "ok" : "missing")
                    ), false);
                }
            }

            index++;
        }

        source.sendSuccess(() -> Component.literal("§6=== End of list ==="), false);
        return tracked.size();
    }

    private static int check(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();

        try {
            Entity target = EntityArgument.getEntity(context, "target");
            Map<String, Boolean> status = ResurrectionManager.check(level, target.getUUID());

            source.sendSuccess(() -> Component.literal(
                String.format("§6=== Container check: %s (UUID=%s) ===",
                    target.getName().getString(), target.getUUID())
            ), false);

            for (Map.Entry<String, Boolean> entry : orderKeys(status).entrySet()) {
                boolean ok = Boolean.TRUE.equals(entry.getValue());
                String mark = ok ? "§a✓" : "§c✗";
                source.sendSuccess(() -> Component.literal(
                    String.format("%s %s §8(%s)", mark, entry.getKey(), ok ? "ok" : "missing")
                ), false);
            }

            long missingCount = status.values().stream().filter(v -> !v).count();
            source.sendSuccess(() -> Component.literal(
                String.format("%s missing=%d", missingCount > 0 ? "§c" : "§a", missingCount)
            ), false);

            return (int) missingCount;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c" + e.getMessage()));
            return -1;
        }
    }

    private static int revive(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();

        try {
            Entity target = EntityArgument.getEntity(context, "target");
            Map<String, Boolean> before = ResurrectionManager.check(level, target.getUUID());
            long missingBefore = before.values().stream().filter(v -> !v).count();

            Map<String, Boolean> after = ResurrectionManager.reviveNow(level, target.getUUID());
            long missingAfter = after.values().stream().filter(v -> !v).count();

            source.sendSuccess(() -> Component.literal(
                String.format("§aRevived %s: containers missing before=%d after=%d",
                    target.getName().getString(), missingBefore, missingAfter)
            ), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c" + e.getMessage()));
            return 0;
        }
    }

    private static int interval(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        int ms = IntegerArgumentType.getInteger(context, "ms");
        ResurrectionManager.setPollIntervalMs(ms);

        source.sendSuccess(() -> Component.literal(
            String.format("§aResurrectionManager poll interval set to %dms", ms)
        ), true);
        return ms;
    }

    /**
     * Reorder the status map so that server-side container keys appear first
     * in a meaningful diagnostic order.
     */
    private static Map<String, Boolean> orderKeys(Map<String, Boolean> raw) {
        List<String> orderedKeys = List.of(
            "ServerLevel.getEntity(uuid)",
            "PersistentEntitySectionManager.knownUuids",
            "EntitySectionStorage.sections",
            "EntityLookup.byUuid",
            "EntityLookup.byId",
            "ServerLevel.entityTickList",
            "ChunkMap.entityMap",
            "ChunkMap.TrackedEntity.seenBy",
            "Entity.levelCallback",
            "ServerLevel.players",
            "ServerLevel.navigatingMobs",
            "ClientCheck.response",
            "ClientLevel.getEntity(uuid)",
            "ClientEntityStorage.entityLookup.byUuid",
            "ClientEntityStorage.entityLookup.byId",
            "ClientEntityStorage.sectionStorage",
            "ClientLevel.tickingEntities",
            "ClientEntity.levelCallback",
            "ClientLevel.players"
        );

        Map<String, Boolean> result = new java.util.LinkedHashMap<>();
        Set<String> visited = new LinkedHashSet<>();

        for (String key : orderedKeys) {
            if (raw.containsKey(key)) {
                result.put(key, raw.get(key));
                visited.add(key);
            }
        }
        for (Map.Entry<String, Boolean> entry : raw.entrySet()) {
            if (!visited.contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}
