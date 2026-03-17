package net.eca.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.InvulnerableEntityManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityInLevelCallback;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class InvulnerableCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("setInvulnerable")
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("invulnerable", BoolArgumentType.bool())
                    .executes(InvulnerableCommand::setInvulnerable)
                )
            )
            .then(Commands.literal("show_all")
                .executes(InvulnerableCommand::showAllInvulnerableEntities)
            );
    }

    private static int setInvulnerable(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EcaCommandSelector.getEntities(context);
            boolean invulnerable = BoolArgumentType.getBool(context, "invulnerable");

            int successCount = 0;
            int skippedCount = 0;
            for (Entity entity : targets) {
                if (!(entity instanceof LivingEntity)) {
                    skippedCount++;
                    continue;
                }

                try {
                    EcaAPI.setInvulnerable(entity, invulnerable);
                    successCount++;
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cFailed to set invulnerability for " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalCount = successCount;
            final int finalSkippedCount = skippedCount;
            final String status = invulnerable ? "invulnerable" : "vulnerable";

            if (finalCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aSet %d %s to %s",
                        finalCount,
                        finalCount == 1 ? "entity" : "entities",
                        status)
                ), true);
            }

            if (finalSkippedCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§eSkipped %d %s (not living)",
                        finalSkippedCount,
                        finalSkippedCount == 1 ? "entity" : "entities")
                ), false);
            }

            return finalCount;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int showAllInvulnerableEntities(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            ServerLevel level = source.getLevel();
            Set<UUID> invulnerableUUIDs = InvulnerableEntityManager.getAllInvulnerableUUIDs();

            if (invulnerableUUIDs.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§eNo invulnerable entities registered"), false);
                return 0;
            }

            source.sendSuccess(() -> Component.literal(
                String.format("§a=== Invulnerable Entities (%d total) ===", invulnerableUUIDs.size())
            ), false);

            int index = 1;
            for (UUID uuid : invulnerableUUIDs) {
                Entity entity = level.getEntity(uuid);
                String entityName = entity != null ? entity.getName().getString() : "Unknown";
                int entityId = entity != null ? entity.getId() : -1;

                final int finalIndex = index;
                source.sendSuccess(() -> Component.literal(
                    String.format("§b%d. §f%s §7(ID: %d, UUID: %s)", finalIndex, entityName, entityId, uuid)
                ), false);

                Map<String, Boolean> containerCheck = EntityUtil.checkEntityInContainers(level, uuid);

                source.sendSuccess(() -> Component.literal("§7  当前实体状态:"), false);

                List<String> orderedStatus = buildOrderedEntityStatus(entity, containerCheck);
                for (String statusLine : orderedStatus) {
                    source.sendSuccess(() -> Component.literal(statusLine), false);
                }

                index++;
            }

            source.sendSuccess(() -> Component.literal("§a=== End of Report ==="), false);

            return invulnerableUUIDs.size();

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    private static List<String> buildOrderedEntityStatus(Entity entity, Map<String, Boolean> containerCheck) {
        List<String> lines = new ArrayList<>();

        boolean alive = entity != null && entity.isAlive();
        boolean removed = entity != null && entity.isRemoved();
        boolean callbackBound = entity != null && entity.levelCallback != EntityInLevelCallback.NULL;
        String removalReason = entity == null || entity.getRemovalReason() == null ? "null" : entity.getRemovalReason().name();

        lines.add(formatState("Entity.isAlive", alive, alive ? "alive" : "not alive"));
        lines.add(formatState("Entity.isRemoved", !removed, removed ? "removed=true" : "removed=false"));
        lines.add(formatState("Entity.removalReason", "null".equals(removalReason), "value=" + removalReason));

        if (entity instanceof LivingEntity living) {
            float health = EntityUtil.getHealth(living);
            float maxHealth = living.getMaxHealth();
            lines.add(formatState("LivingEntity.health", health > 0.0f, String.format("%.2f / %.2f", health, maxHealth)));
            lines.add(formatState("LivingEntity.dead", !living.dead, "dead=" + living.dead));
            lines.add(formatState("LivingEntity.deathTime", living.deathTime <= 0, "deathTime=" + living.deathTime));
            lines.add(formatState("LivingEntity.pose", true, "pose=" + living.getPose()));
        }

        lines.add(formatState("Entity.levelCallback", callbackBound, callbackBound ? "bound" : "NULL"));

        List<String> orderedKeys = List.of(
            "ChunkMap.entityMap",
            "ChunkMap.TrackedEntity.seenBy",
            "ServerLevel.players",
            "ServerLevel.navigatingMobs",
            "EntitySectionStorage.sections",
            "ServerLevel.entityTickList",
            "EntityLookup.byUuid",
            "EntityLookup.byId",
            "PersistentEntitySectionManager.knownUuids",
            "ServerLevel.getEntity(uuid)",
            "ClientCheck.response",
            "ClientLevel.getEntity(uuid)",
            "ClientEntityStorage.entityLookup.byUuid",
            "ClientEntityStorage.entityLookup.byId",
            "ClientEntityStorage.sectionStorage",
            "ClientLevel.tickingEntities",
            "ClientEntity.levelCallback",
            "ClientLevel.players"
        );

        Set<String> visited = new LinkedHashSet<>();
        for (String key : orderedKeys) {
            if (!containerCheck.containsKey(key)) {
                continue;
            }
            boolean ok = Boolean.TRUE.equals(containerCheck.get(key));
            lines.add(formatState(key, ok, ok ? "ok" : "missing"));
            visited.add(key);
        }

        for (Map.Entry<String, Boolean> entry : containerCheck.entrySet()) {
            if (visited.contains(entry.getKey())) {
                continue;
            }
            boolean ok = Boolean.TRUE.equals(entry.getValue());
            lines.add(formatState(entry.getKey(), ok, ok ? "ok" : "missing"));
        }

        return lines;
    }

    private static String formatState(String name, boolean ok, String detail) {
        String color = ok ? "§a" : "§c";
        String mark = ok ? "✓" : "✗";
        return String.format("§7    %s%s §7%s §8(%s)", color, mark, name, detail);
    }
}
