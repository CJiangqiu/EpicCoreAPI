package net.eca.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.api.EcaAPI;
import net.eca.util.filter.FilterType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Set;

public class FilterCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        LiteralArgumentBuilder<CommandSourceStack> enableBranch = Commands.literal("true");
        for (FilterType filter : FilterType.values()) {
            String name = filter.name().toLowerCase();
            enableBranch.then(Commands.literal(name)
                    .executes(ctx -> enableFilter(ctx, filter))
            );
        }

        return Commands.literal("setFilter")
                .then(Commands.argument("targets", EntityArgument.players())
                        .then(enableBranch)
                        .then(Commands.literal("false")
                                .executes(FilterCommand::disableAllFilters)
                        )
                );
    }

    private static int enableFilter(CommandContext<CommandSourceStack> context, FilterType filter) {
        CommandSourceStack source = context.getSource();
        try {
            Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "targets");
            int count = 0;
            for (ServerPlayer player : players) {
                EcaAPI.enableFilter(player, filter);
                count++;
            }
            final int finalCount = count;
            final String filterName = filter.name().toLowerCase();
            source.sendSuccess(() -> Component.literal(
                    String.format("§aEnabled filter '%s' for %d %s",
                            filterName, finalCount, finalCount == 1 ? "player" : "players")
            ), true);
            return finalCount;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int disableAllFilters(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "targets");
            int count = 0;
            for (ServerPlayer player : players) {
                Set<FilterType> active = EcaAPI.getActiveFilters(player);
                for (FilterType filter : active) {
                    EcaAPI.disableFilter(player, filter);
                }
                count++;
            }
            final int finalCount = count;
            source.sendSuccess(() -> Component.literal(
                    String.format("§aDisabled all filters for %d %s",
                            finalCount, finalCount == 1 ? "player" : "players")
            ), true);
            return finalCount;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }
}
