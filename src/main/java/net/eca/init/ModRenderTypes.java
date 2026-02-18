package net.eca.init;

import net.eca.EcaMod;
import net.eca.client.render.shader.ArcaneShader;
import net.eca.client.render.shader.AuroraShader;
import net.eca.client.render.shader.BlackHoleShader;
import net.eca.client.render.shader.CosmosShader;
import net.eca.client.render.shader.DreamSakuraShader;
import net.eca.client.render.shader.HackerShader;
import net.eca.client.render.shader.StarlightShader;
import net.eca.client.render.shader.ForestShader;
import net.eca.client.render.shader.OceanShader;
import net.eca.client.render.shader.StormShader;
import net.eca.client.render.shader.TheLastEndShader;
import net.eca.client.render.shader.VolcanoShader;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

@Mod.EventBusSubscriber(modid = EcaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModRenderTypes {

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        TheLastEndShader.register(event);
        DreamSakuraShader.register(event);
        ForestShader.register(event);
        OceanShader.register(event);
        StormShader.register(event);
        VolcanoShader.register(event);
        ArcaneShader.register(event);
        AuroraShader.register(event);
        HackerShader.register(event);
        StarlightShader.register(event);
        CosmosShader.register(event);
        BlackHoleShader.register(event);
    }

    private ModRenderTypes() {}
}
