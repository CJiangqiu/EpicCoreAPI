package net.eca.util.selector;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.eca.api.EcaAPI;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public class EcaGlobalEntitySelector extends EntitySelector {

    private final EntitySelector vanilla;
    private final EcaEntitySelector.SelectorMode mode;

    public EcaGlobalEntitySelector(EntitySelector vanilla, EcaEntitySelector.SelectorMode mode) {
        super(
            vanilla.getMaxResults(),
            vanilla.includesEntities(),
            vanilla.isWorldLimited(),
            entity -> true,
            MinMaxBounds.Doubles.ANY,
            Function.identity(),
            null,
            EntitySelector.ORDER_ARBITRARY,
            mode == EcaEntitySelector.SelectorMode.SELF,
            null, null, null,
            vanilla.usesSelector()
        );
        this.vanilla = vanilla;
        this.mode = mode;
    }

    @Override
    public List<? extends Entity> findEntities(CommandSourceStack source) throws CommandSyntaxException {
        Vec3 pos = vanilla.position.apply(source.getPosition());

        // Replicate EntitySelector.getPredicate logic using AT-exposed fields
        Predicate<Entity> predicate = vanilla.predicate;
        if (vanilla.aabb != null) {
            AABB movedAabb = vanilla.aabb.move(pos);
            predicate = predicate.and(e -> movedAabb.intersects(e.getBoundingBox()));
        }
        if (!vanilla.range.isAny()) {
            predicate = predicate.and(e -> vanilla.range.matchesSqr(e.distanceToSqr(pos)));
        }
        Predicate<Entity> finalPredicate = predicate.and(e -> e.getType().isEnabled(source.enabledFeatures()));

        // @eca_s: return only the executing entity if it matches
        if (mode == EcaEntitySelector.SelectorMode.SELF) {
            Entity self = source.getEntity();
            if (self != null && (!self.isRemoved() || EcaAPI.isInvulnerable(self)) && finalPredicate.test(self)) {
                return List.of(self);
            }
            return Collections.emptyList();
        }

        // Get ECA's extended entity list (includes invulnerable entities not in vanilla lookup)
        Level level = source.getLevel();
        boolean playersOnly = !vanilla.includesEntities();
        List<Entity> entities = new ArrayList<>(EcaEntitySelector.getEntities(level,
            playersOnly ? e -> e instanceof Player && finalPredicate.test(e) : finalPredicate));

        // Sort and limit (replicating EntitySelector.sortAndLimit)
        if (entities.size() > 1) {
            vanilla.order.accept(pos, entities);
        }
        return entities.subList(0, Math.min(vanilla.maxResults, entities.size()));
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
