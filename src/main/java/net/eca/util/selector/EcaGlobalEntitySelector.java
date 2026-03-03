package net.eca.util.selector;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class EcaGlobalEntitySelector extends EntitySelector {

    private final String rawSelector;
    private final EcaEntitySelector.SelectorMode mode;

    public EcaGlobalEntitySelector(String rawSelector, EcaEntitySelector.SelectorMode mode) {
        super(
            mode == EcaEntitySelector.SelectorMode.NEAREST_PLAYER
                || mode == EcaEntitySelector.SelectorMode.RANDOM_PLAYER
                || mode == EcaEntitySelector.SelectorMode.SELF ? 1 : Integer.MAX_VALUE,
            mode != EcaEntitySelector.SelectorMode.NEAREST_PLAYER
                && mode != EcaEntitySelector.SelectorMode.ALL_PLAYERS
                && mode != EcaEntitySelector.SelectorMode.RANDOM_PLAYER,
            false,
            entity -> true,
            MinMaxBounds.Doubles.ANY,
            Function.identity(),
            null,
            EntitySelector.ORDER_ARBITRARY,
            mode == EcaEntitySelector.SelectorMode.SELF,
            null,
            null,
            null,
            true
        );
        this.rawSelector = rawSelector;
        this.mode = mode;
    }

    @Override
    public List<? extends Entity> findEntities(CommandSourceStack source) throws CommandSyntaxException {
        return EcaEntitySelector.select(source, this.rawSelector, this.mode);
    }

    @Override
    public Entity findSingleEntity(CommandSourceStack source) throws CommandSyntaxException {
        List<? extends Entity> entities = this.findEntities(source);
        if (entities.isEmpty()) {
            throw EntityArgument.NO_ENTITIES_FOUND.create();
        }
        if (entities.size() > 1) {
            throw EntityArgument.ERROR_NOT_SINGLE_ENTITY.create();
        }
        return entities.get(0);
    }

    @Override
    public List<ServerPlayer> findPlayers(CommandSourceStack source) throws CommandSyntaxException {
        List<? extends Entity> entities = this.findEntities(source);
        List<ServerPlayer> players = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity instanceof ServerPlayer player) {
                players.add(player);
            }
        }
        return players;
    }

    @Override
    public ServerPlayer findSinglePlayer(CommandSourceStack source) throws CommandSyntaxException {
        List<ServerPlayer> players = this.findPlayers(source);
        if (players.isEmpty()) {
            throw EntityArgument.NO_PLAYERS_FOUND.create();
        }
        if (players.size() > 1) {
            throw EntityArgument.ERROR_NOT_SINGLE_PLAYER.create();
        }
        return players.get(0);
    }
}
