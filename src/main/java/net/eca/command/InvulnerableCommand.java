package net.eca.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.api.EcaAPI;
import net.eca.util.InvulnerableEntityManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;
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
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
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

                Map<String, Boolean> containerCheck = InvulnerableEntityManager.checkEntityInContainers(level, uuid);

                source.sendSuccess(() -> Component.literal("§7  Container Status:"), false);

                for (Map.Entry<String, Boolean> entry : containerCheck.entrySet()) {
                    String color = entry.getValue() ? "§a" : "§c";
                    String status = entry.getValue() ? "✓" : "✗";
                    String containerName = entry.getKey();

                    source.sendSuccess(() -> Component.literal(
                        String.format("§7    %s%s §7%s", color, status, containerName)
                    ), false);
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
}
