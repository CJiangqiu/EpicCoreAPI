package net.eca.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.world.entity.Entity;

import java.util.Collection;

public final class EcaCommandSelector {

    private EcaCommandSelector() {
    }

    public static Collection<? extends Entity> getEntities(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return EntityArgument.getEntities(context, "targets");
    }
}
