package net.eca.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.network.NetworkHandler;
import net.eca.network.ShaderGeneratorOpenPacket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class ShaderGeneratorCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("shaderGenerator")
            .executes(ShaderGeneratorCommand::open);
    }

    private static int open(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            NetworkHandler.sendToPlayer(new ShaderGeneratorOpenPacket(), player);
            source.sendSuccess(
                () -> Component.translatable("command.eca.shader_generator.opened"),
                false
            );
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.translatable(
                "command.eca.shader_generator.failed",
                exception.getMessage()
            ));
            return 0;
        }
    }

    private ShaderGeneratorCommand() {}
}
