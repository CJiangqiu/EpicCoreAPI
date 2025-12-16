package net.eca.init;

import net.eca.EcaMod;
import net.eca.command.EcaCommand;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = EcaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ModCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        EcaCommand.register(event.getDispatcher());
    }
}
