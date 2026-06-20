package net.eca.client.render.shader_generator;

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
import net.eca.client.render.preset.ShaderPreset;
import net.eca.client.render.preset.ShaderPresetRegistry;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

public final class ShaderPreviewSourceCatalog {

    public static List<ShaderPreviewSource> loadRegisteredSources() {
        List<ShaderPreviewSource> sources = new ArrayList<>();
        sources.add(builtin(Component.translatable("gui.eca.shader_generator.source.the_last_end"), TheLastEndRenderTypes.BOSS_BAR,
            TheLastEndRenderTypes.SKYBOX, TheLastEndRenderTypes.ITEM, TheLastEndRenderTypes::createEntityEffect));
        sources.add(builtin(Component.translatable("gui.eca.shader_generator.source.dream_sakura"), DreamSakuraRenderTypes.BOSS_BAR,
            DreamSakuraRenderTypes.SKYBOX, DreamSakuraRenderTypes.ITEM, DreamSakuraRenderTypes::createEntityEffect));
        sources.add(builtin(Component.translatable("gui.eca.shader_generator.source.forest"), ForestRenderTypes.BOSS_BAR,
            ForestRenderTypes.SKYBOX, ForestRenderTypes.ITEM, ForestRenderTypes::createEntityEffect));
        sources.add(builtin(Component.translatable("gui.eca.shader_generator.source.ocean"), OceanRenderTypes.BOSS_BAR,
            OceanRenderTypes.SKYBOX, OceanRenderTypes.ITEM, OceanRenderTypes::createEntityEffect));
        sources.add(builtin(Component.translatable("gui.eca.shader_generator.source.storm"), StormRenderTypes.BOSS_BAR,
            StormRenderTypes.SKYBOX, StormRenderTypes.ITEM, StormRenderTypes::createEntityEffect));
        sources.add(builtin(Component.translatable("gui.eca.shader_generator.source.volcano"), VolcanoRenderTypes.BOSS_BAR,
            VolcanoRenderTypes.SKYBOX, VolcanoRenderTypes.ITEM, VolcanoRenderTypes::createEntityEffect));
        sources.add(builtin(Component.translatable("gui.eca.shader_generator.source.arcane"), ArcaneRenderTypes.BOSS_BAR,
            ArcaneRenderTypes.SKYBOX, ArcaneRenderTypes.ITEM, ArcaneRenderTypes::createEntityEffect));
        sources.add(builtin(Component.translatable("gui.eca.shader_generator.source.aurora"), AuroraRenderTypes.BOSS_BAR,
            AuroraRenderTypes.SKYBOX, AuroraRenderTypes.ITEM, AuroraRenderTypes::createEntityEffect));
        sources.add(builtin(Component.translatable("gui.eca.shader_generator.source.hacker"), HackerRenderTypes.BOSS_BAR,
            HackerRenderTypes.SKYBOX, HackerRenderTypes.ITEM, HackerRenderTypes::createEntityEffect));
        sources.add(builtin(Component.translatable("gui.eca.shader_generator.source.starlight"), StarlightRenderTypes.BOSS_BAR,
            StarlightRenderTypes.SKYBOX, StarlightRenderTypes.ITEM, StarlightRenderTypes::createEntityEffect));
        sources.add(builtin(Component.translatable("gui.eca.shader_generator.source.cosmos"), CosmosRenderTypes.BOSS_BAR,
            CosmosRenderTypes.SKYBOX, CosmosRenderTypes.ITEM, CosmosRenderTypes::createEntityEffect));
        sources.add(builtin(Component.translatable("gui.eca.shader_generator.source.black_hole"), BlackHoleRenderTypes.BOSS_BAR,
            BlackHoleRenderTypes.SKYBOX, BlackHoleRenderTypes.ITEM, BlackHoleRenderTypes::createEntityEffect));

        ShaderPresetRegistry.getPresetIds().stream()
            .sorted(Comparator.comparing(ResourceLocation::toString))
            .map(ShaderPresetRegistry::getPreset)
            .filter(preset -> preset != null)
            .map(ShaderPreviewSourceCatalog::registered)
            .forEach(sources::add);
        return sources;
    }

    private static ShaderPreviewSource registered(ShaderPreset preset) {
        return builtin(
            Component.translatable("gui.eca.shader_generator.source.preset", preset.id()),
            preset.bossBar(),
            preset.skybox(),
            preset.item(),
            preset::entityEffect
        );
    }

    private static ShaderPreviewSource builtin(
        Component displayName,
        RenderType bossBar,
        RenderType skybox,
        RenderType item,
        Function<ResourceLocation, RenderType> entityFactory
    ) {
        return new ShaderPreviewSource() {
            @Override
            public Component displayName() {
                return displayName;
            }

            @Override
            public RenderType bossBar() {
                return bossBar;
            }

            @Override
            public RenderType skybox() {
                return skybox;
            }

            @Override
            public RenderType item() {
                return item;
            }

            @Override
            public RenderType entity(ResourceLocation texture) {
                return entityFactory.apply(texture);
            }
        };
    }

    private ShaderPreviewSourceCatalog() {}
}
