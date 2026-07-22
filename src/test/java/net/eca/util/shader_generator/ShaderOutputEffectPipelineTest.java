package net.eca.util.shader_generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderOutputEffectPipelineTest {

    @Test
    void outputEffectsRoundTripAndGenerateInStageOrder() {
        ShaderCompositionProject project = new ShaderCompositionProject();
        ShaderOutputEffectInstance wave = ShaderOutputEffectRegistry.get("wave_distortion").createInstance();
        wave.setValue("amplitude", 0.012F);
        ShaderOutputEffectInstance chromatic = ShaderOutputEffectRegistry.get("chromatic_aberration").createInstance();
        ShaderOutputEffectInstance hueCycle = ShaderOutputEffectRegistry.get("hue_cycle").createInstance();
        project.addOutputEffect(wave);
        project.addOutputEffect(chromatic);
        project.addOutputEffect(hueCycle);

        String serialized = ShaderProjectCodec.serialize("eca", "output_effect_test", project);
        assertTrue(serialized.contains("\"output_effects\""));

        ShaderCompositionProject restored = new ShaderCompositionProject();
        ShaderProjectCodec.deserializeInto(serialized, restored);
        assertEquals(3, restored.outputEffects().size());
        assertEquals(0.012F, restored.outputEffects().get(0).value("amplitude"));

        ShaderProject shaderProject = restored.toShaderProject("eca", "output_effect_test");
        String source = new StandardShaderSourceAssembler().assembleFragment(shaderProject, ShaderExportMode.PORTABLE);
        int waveIndex = source.indexOf("effectUv += vec2");
        int renderIndex = source.indexOf("vec4 effectColor = renderEffect(effectUv, effectDirection, GameTime);");
        int chromaticIndex = source.indexOf("chromaticPositive");
        int hueIndex = source.indexOf("ecaHueRotate(effectColor.rgb");

        assertTrue(waveIndex >= 0 && waveIndex < renderIndex);
        assertTrue(chromaticIndex > renderIndex);
        assertTrue(hueIndex > chromaticIndex);
    }

    @Test
    void environmentElementsGeneratePortableShaderCode() {
        ShaderCompositionProject project = new ShaderCompositionProject();
        ShaderLayer layer = project.layers().get(0);
        layer.addElement(ShaderModuleRegistry.get("lightning"));
        layer.addElement(ShaderModuleRegistry.get("aurora"));
        layer.addElement(ShaderModuleRegistry.get("fireflies"));
        layer.addElement(ShaderModuleRegistry.get("water_bubbles"));
        layer.addElement(ShaderModuleRegistry.get("toxic_bubbles"));
        layer.addElement(ShaderModuleRegistry.get("rain_streaks"));
        layer.addElement(ShaderModuleRegistry.get("snowfall"));
        layer.addElement(ShaderModuleRegistry.get("falling_leaves"));
        layer.addElement(ShaderModuleRegistry.get("magma_debris"));
        layer.addElement(ShaderModuleRegistry.get("dust_haze"));
        layer.addElement(ShaderModuleRegistry.get("digital_rain"));

        String source = new StandardShaderSourceAssembler().assembleFragment(
            project.toShaderProject("eca", "environment_element_test"),
            ShaderExportMode.PORTABLE
        );

        assertTrue(source.contains("lightningMask"));
        assertTrue(source.contains("auroraBand"));
        assertTrue(source.contains("fireflyDistance"));
        assertTrue(source.contains("waterBubbleRing"));
        assertTrue(source.contains("toxicFade"));
        assertTrue(source.contains("rainPhase"));
        assertTrue(source.contains("snowPhase"));
        assertTrue(source.contains("leafPhase"));
        assertTrue(source.contains("magmaDistance"));
        assertTrue(source.contains("dustCloud"));
        assertTrue(source.contains("digitalLine"));
    }

    @Test
    void blackHoleGeneratesOnlyItsOwnProceduralParts() {
        ShaderCompositionProject project = new ShaderCompositionProject();
        project.layers().get(0).addElement(ShaderModuleRegistry.get("black_hole"));

        String source = new StandardShaderSourceAssembler().assembleFragment(
            project.toShaderProject("eca", "black_hole_element_test"),
            ShaderExportMode.PORTABLE
        );

        assertTrue(source.contains("eventHorizon"));
        assertTrue(source.contains("accretionDisk"));
        assertTrue(source.contains("photonRing"));
        assertTrue(!source.contains("pulledPos"));
    }

    @Test
    void heatHazeRunsBeforeLayerRendering() {
        ShaderCompositionProject project = new ShaderCompositionProject();
        project.addOutputEffect(ShaderOutputEffectRegistry.get("heat_haze").createInstance());

        String source = new StandardShaderSourceAssembler().assembleFragment(
            project.toShaderProject("eca", "heat_haze_effect_test"),
            ShaderExportMode.PORTABLE
        );

        int heatHazeIndex = source.indexOf("heatHazeMask");
        int renderIndex = source.indexOf("vec4 effectColor = renderEffect(effectUv, effectDirection, GameTime);");
        assertTrue(heatHazeIndex >= 0 && heatHazeIndex < renderIndex);
    }
}
