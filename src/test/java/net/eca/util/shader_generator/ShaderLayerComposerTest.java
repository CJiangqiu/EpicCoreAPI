package net.eca.util.shader_generator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderLayerComposerTest {

    @Test
    void isolatesRepeatedModuleLocalsInElementScopes() {
        ShaderLayer layer = new ShaderLayer("nebulae");
        layer.setBaseColor(0.0F, 0.0F, 0.0F, 0.0F);
        ShaderModuleDefinition nebula = ShaderModuleRegistry.get("nebula_haze");
        layer.addElement(nebula).setValue("count", 2.0F);
        layer.addElement(nebula).setValue("count", 2.0F);

        String source = ShaderLayerComposer.compose(List.of(layer));
        String marker = "        // @eca-nav element: nebula_haze\n";
        int first = source.indexOf(marker);
        int second = source.indexOf(marker, first + marker.length());
        int blend = source.indexOf("        finalColor =", second);

        assertTrue(first >= 0);
        assertTrue(second > first);
        assertTrue(blend > second);
        assertElementScope(source.substring(first + marker.length(), second));
        assertElementScope(source.substring(second + marker.length(), blend));
    }

    @Test
    void fadesNebulaOutsideItsOuterRadius() {
        ShaderLayer layer = transparentLayer("nebula");
        layer.addElement(ShaderModuleRegistry.get("nebula_haze"));

        String source = ShaderLayerComposer.compose(List.of(layer));

        assertTrue(source.contains(
            "d0 *= 1.0 - smoothstep(1.5000, 1.8000, rf0);"
        ));
    }

    @Test
    void distributesEveryInstanceWhenCountExceedsOne() {
        ShaderLayer layer = transparentLayer("stars");
        ShaderModuleInstance stars = layer.addElement(ShaderModuleRegistry.get("dot_star"));
        stars.setValue("count", 2.0F);
        stars.setValue("center_x", 0.5F);
        stars.setValue("center_y", 0.5F);
        stars.setValue("spread_x", 0.4F);
        stars.setValue("spread_y", 0.4F);

        String source = ShaderLayerComposer.compose(List.of(layer));

        assertFalse(source.contains("effectUv - vec2(0.5000, 0.5000)"));
    }

    @Test
    void blackHoleComposesFarDiskHorizonNearDiskAndPhotonRingInDepthOrder() {
        ShaderLayer layer = transparentLayer("black hole");
        layer.addElement(ShaderModuleRegistry.get("black_hole"));

        String source = ShaderLayerComposer.compose(List.of(layer));

        String farDisk = "color += diskColor0_0 * farDisk0_0";
        String horizon = "color = mix(color, vec3(0.0100, 0.0050, 0.0200), "
            + "eventHorizon0_0 * blackHoleAlpha0_0);";
        String nearDisk = "color += diskColor0_0 * nearDisk0_0";
        String photonRing = "color += vec3(0.8500, 0.6000, 1.0000) * photonRing0_0";

        assertTrue(source.contains(
            "farDisk0_0 = accretionDisk0_0 * (1.0 - diskFront0_0) "
                + "* (1.0 - eventHorizon0_0);"
        ));
        assertTrue(source.contains(
            "nearDisk0_0 = accretionDisk0_0 * diskFront0_0;"
        ));
        assertTrue(source.contains(
            "diskNoise0_0 = ecaFbm("
        ));
        assertTrue(source.contains(
            "diskFrontFeather0_0 = max(fwidth(blackHolePoint0_0.y), 0.00025);"
        ));
        assertTrue(source.contains(
            "diskFront0_0 = smoothstep(-diskFrontFeather0_0, "
                + "diskFrontFeather0_0, blackHolePoint0_0.y);"
        ));
        assertFalse(source.contains("0.0550"));
        assertTrue(source.indexOf(farDisk) >= 0);
        assertTrue(source.indexOf(horizon) >= 0);
        assertTrue(source.indexOf(nearDisk) >= 0);
        assertTrue(source.indexOf(photonRing) >= 0);
        assertTrue(source.indexOf(farDisk) < source.indexOf(horizon));
        assertTrue(source.indexOf(horizon) < source.indexOf(nearDisk));
        assertTrue(source.indexOf(nearDisk) < source.indexOf(photonRing));
    }

    private static ShaderLayer transparentLayer(String name) {
        ShaderLayer layer = new ShaderLayer(name);
        layer.setBaseColor(0.0F, 0.0F, 0.0F, 0.0F);
        return layer;
    }

    private static void assertElementScope(String elementSource) {
        assertTrue(elementSource.startsWith("        {\n"));
        assertTrue(elementSource.endsWith("        }\n"));
    }
}
