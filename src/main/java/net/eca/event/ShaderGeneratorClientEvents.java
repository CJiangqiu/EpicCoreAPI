package net.eca.event;

import net.eca.client.ShaderGeneratorKeyBindings;
import net.eca.client.gui.ShaderGeneratorScreen;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

//着色器生成器：按键打开（纯客户端 GUI，无需服务端往返）
@Mod.EventBusSubscriber(modid = "eca", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShaderGeneratorClientEvents {

    private ShaderGeneratorClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean triggered = false;
        while (ShaderGeneratorKeyBindings.OPEN_GENERATOR.consumeClick()) {
            triggered = true;
        }
        if (triggered && mc.screen == null) {
            ShaderGeneratorScreen.open();
        }
    }
}
