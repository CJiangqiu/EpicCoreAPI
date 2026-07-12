package net.eca.compat;

import net.eca.client.render.GeoEntityExtensionLayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.event.GeoRenderEvent;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.GeoReplacedEntityRenderer;

@OnlyIn(Dist.CLIENT)
public class GeckoLibCompat {

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(GeckoLibCompat::onGeoCompileRenderLayers);
        MinecraftForge.EVENT_BUS.addListener(GeckoLibCompat::onGeoReplacedCompileRenderLayers);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void onGeoCompileRenderLayers(GeoRenderEvent.Entity.CompileRenderLayers event) {
        GeoEntityRenderer geoRenderer = event.getRenderer();
        geoRenderer.addRenderLayer(new GeoEntityExtensionLayer<>(geoRenderer, animatable -> (Entity) animatable));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void onGeoReplacedCompileRenderLayers(GeoRenderEvent.ReplacedEntity.CompileRenderLayers event) {
        GeoReplacedEntityRenderer geoRenderer = event.getRenderer();
        geoRenderer.addRenderLayer(new GeoEntityExtensionLayer<>(geoRenderer, animatable -> geoRenderer.getCurrentEntity()));
    }
}
