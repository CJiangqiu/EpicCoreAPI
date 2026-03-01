package net.eca.init;

import net.eca.EcaMod;
import net.eca.client.render.ArcaneRenderTypes;
import net.eca.client.render.AuroraRenderTypes;
import net.eca.client.render.BlackHoleRenderTypes;
import net.eca.client.render.CosmosRenderTypes;
import net.eca.client.render.DreamSakuraRenderTypes;
import net.eca.client.render.ForestRenderTypes;
import net.eca.client.render.HackerRenderTypes;
import net.eca.client.render.OceanRenderTypes;
import net.eca.client.render.StarlightRenderTypes;
import net.eca.client.render.StormRenderTypes;
import net.eca.client.render.TheLastEndRenderTypes;
import net.eca.client.render.VolcanoRenderTypes;
import net.eca.client.render.shader.ArcaneShader;
import net.eca.client.render.shader.AuroraShader;
import net.eca.client.render.shader.BlackHoleShader;
import net.eca.client.render.shader.CosmosShader;
import net.eca.client.render.shader.DreamSakuraShader;
import net.eca.client.render.shader.ForestShader;
import net.eca.client.render.shader.HackerShader;
import net.eca.client.render.shader.OceanShader;
import net.eca.client.render.shader.StarlightShader;
import net.eca.client.render.shader.StormShader;
import net.eca.client.render.shader.TheLastEndShader;
import net.eca.client.render.shader.VolcanoShader;
import net.eca.util.entity_extension.GlobalEffectRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

@SuppressWarnings("removal")
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

        registerSkyboxPresets();
    }

    private static void registerSkyboxPresets() {
        GlobalEffectRegistry.registerSkyboxPreset(new ResourceLocation(EcaMod.MOD_ID, "the_last_end"), TheLastEndRenderTypes.SKYBOX);
        GlobalEffectRegistry.registerSkyboxPreset(new ResourceLocation(EcaMod.MOD_ID, "dream_sakura"), DreamSakuraRenderTypes.SKYBOX);
        GlobalEffectRegistry.registerSkyboxPreset(new ResourceLocation(EcaMod.MOD_ID, "forest"), ForestRenderTypes.SKYBOX);
        GlobalEffectRegistry.registerSkyboxPreset(new ResourceLocation(EcaMod.MOD_ID, "ocean"), OceanRenderTypes.SKYBOX);
        GlobalEffectRegistry.registerSkyboxPreset(new ResourceLocation(EcaMod.MOD_ID, "storm"), StormRenderTypes.SKYBOX);
        GlobalEffectRegistry.registerSkyboxPreset(new ResourceLocation(EcaMod.MOD_ID, "volcano"), VolcanoRenderTypes.SKYBOX);
        GlobalEffectRegistry.registerSkyboxPreset(new ResourceLocation(EcaMod.MOD_ID, "arcane"), ArcaneRenderTypes.SKYBOX);
        GlobalEffectRegistry.registerSkyboxPreset(new ResourceLocation(EcaMod.MOD_ID, "aurora"), AuroraRenderTypes.SKYBOX);
        GlobalEffectRegistry.registerSkyboxPreset(new ResourceLocation(EcaMod.MOD_ID, "hacker"), HackerRenderTypes.SKYBOX);
        GlobalEffectRegistry.registerSkyboxPreset(new ResourceLocation(EcaMod.MOD_ID, "starlight"), StarlightRenderTypes.SKYBOX);
        GlobalEffectRegistry.registerSkyboxPreset(new ResourceLocation(EcaMod.MOD_ID, "cosmos"), CosmosRenderTypes.SKYBOX);
        GlobalEffectRegistry.registerSkyboxPreset(new ResourceLocation(EcaMod.MOD_ID, "black_hole"), BlackHoleRenderTypes.SKYBOX);
    }

    private ModRenderTypes() {}
}
