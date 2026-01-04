package net.eca.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public class EcaCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("eca")
                .requires(source -> source.hasPermission(2))
                .then(InvulnerableCommand.registerSubCommand())
                .then(SetHealthCommand.registerSubCommand())
                .then(LockHealthCommand.registerSubCommand())
                .then(UnlockHealthCommand.registerSubCommand())
                .then(KillEntityCommand.registerSubCommand())
                .then(RemoveEntityCommand.registerSubCommand())
                .then(TeleportCommand.registerSubCommand())
                .then(CleanupBossBarCommand.registerSubCommand())
        );
    }
}
