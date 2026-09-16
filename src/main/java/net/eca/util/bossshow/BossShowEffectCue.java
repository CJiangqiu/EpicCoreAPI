package net.eca.util.bossshow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// BossShow 屏幕效果时间点；具体参数由效果注册表解释。
public record BossShowEffectCue(int tick,
                                String type,
                                String effect,
                                int durationTicks,
                                int fadeInTicks,
                                int fadeOutTicks,
                                Curve easing,
                                Map<String, Float> parameters) {

    public static final String CAMERA_SHAKE = "camera_shake";
    public static final String SHADER_EFFECT = "shader_effect";
    public static final String FILTER = "filter";

    public BossShowEffectCue {
        tick = Math.max(0, tick);
        type = normalize(type);
        effect = normalize(effect);
        durationTicks = Math.max(1, durationTicks);
        fadeInTicks = Math.max(0, Math.min(fadeInTicks, durationTicks));
        fadeOutTicks = Math.max(0, Math.min(fadeOutTicks, durationTicks));
        easing = easing != null ? easing : Curve.NONE;
        LinkedHashMap<String, Float> cleaned = new LinkedHashMap<>();
        if (parameters != null) {
            parameters.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && Float.isFinite(value)) {
                    cleaned.put(normalize(key), value);
                }
            });
        }
        parameters = Collections.unmodifiableMap(cleaned);
    }

    public float parameter(String key, float fallback) {
        return parameters.getOrDefault(normalize(key), fallback);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
