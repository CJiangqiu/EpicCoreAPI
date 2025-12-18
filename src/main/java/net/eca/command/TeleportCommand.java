package net.eca.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.api.EcaAPI;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;

//传送实体命令
public class TeleportCommand {

    //注册子命令
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("teleport")
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("location", Vec3Argument.vec3())
                    .executes(TeleportCommand::teleportToCoordinates)
                )
            );
    }

    //执行传送（坐标）
    private static int teleportToCoordinates(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
            Vec3 location = Vec3Argument.getVec3(context, "location");

            int successCount = 0;

            for (Entity entity : targets) {
                try {
                    boolean success = EcaAPI.teleportEntity(entity, location.x, location.y, location.z);
                    if (success) {
                        successCount++;
                    } else {
                        source.sendFailure(Component.literal(
                            "§cFailed to teleport " + entity.getName().getString()
                        ));
                    }
                } catch (Exception e) {
                    source.sendFailure(Component.literal(
                        "§cError teleporting " + entity.getName().getString() + ": " + e.getMessage()
                    ));
                }
            }

            final int finalSuccessCount = successCount;
            final double x = location.x;
            final double y = location.y;
            final double z = location.z;

            if (finalSuccessCount > 0) {
                source.sendSuccess(() -> Component.literal(
                    String.format("§aTeleported %d %s to (%.1f, %.1f, %.1f)",
                        finalSuccessCount,
                        finalSuccessCount == 1 ? "entity" : "entities",
                        x, y, z)
                ), true);
            }

            return finalSuccessCount;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§cCommand execution failed: " + e.getMessage()));
            return 0;
        }
    }
}
