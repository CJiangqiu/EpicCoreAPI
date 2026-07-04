package net.eca.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.eca.EcaMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

//着色器生成器按键绑定：默认不绑定，交由玩家在控制设置中自行设置
@Mod.EventBusSubscriber(modid = EcaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ShaderGeneratorKeyBindings {

    //打开着色器生成器（默认未绑定）
    public static final KeyMapping OPEN_GENERATOR = new KeyMapping(
        "key.eca.shader_generator.open",
        KeyConflictContext.UNIVERSAL,
        InputConstants.UNKNOWN,
        BossShowKeyBindings.CATEGORY
    );

    private ShaderGeneratorKeyBindings() {}

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_GENERATOR);
    }
}
