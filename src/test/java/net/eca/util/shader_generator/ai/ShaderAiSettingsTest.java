package net.eca.util.shader_generator.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShaderAiSettingsTest {

    @Test
    void defaultsUseExpandedToolRoundLimit() {
        ShaderAiSettings settings = ShaderAiSettings.defaults();

        assertEquals(2, settings.version());
        assertEquals(24, settings.assistant().maxToolRounds());
    }

    @Test
    void zeroToolRoundsMeansUnlimited() {
        ShaderAiSettings.AssistantOptions options = new ShaderAiSettings.AssistantOptions(
            true, true, true, 0, 3, false
        );

        assertEquals(0, options.maxToolRounds());
    }
}
