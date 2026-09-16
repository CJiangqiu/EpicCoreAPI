package net.eca.client;

import net.eca.client.render.shader.FilterRenderer;
import net.eca.util.bossshow.BossShowEffectCue;
import net.eca.util.bossshow.Curve;
import net.eca.util.filter.FilterType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

// BossShow 客户端临时效果层，负责生命周期、强度包络和相机震动。
public final class BossShowScreenEffectState {

    private static final List<ActiveEffect> ACTIVE = new ArrayList<>();

    private BossShowScreenEffectState() {}

    public static void trigger(BossShowEffectCue cue) {
        if (cue == null) return;
        if (BossShowEffectCue.FILTER.equals(cue.type())) {
            ACTIVE.removeIf(active -> BossShowEffectCue.FILTER.equals(active.cue.type()));
        }
        ACTIVE.add(new ActiveEffect(cue));
        updateFilterOverride(0.0F);
    }

    public static void tick() {
        Iterator<ActiveEffect> iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            ActiveEffect active = iterator.next();
            active.age++;
            if (active.age >= active.cue.durationTicks()) iterator.remove();
        }
        updateFilterOverride(0.0F);
    }

    public static void clear() {
        ACTIVE.clear();
        FilterRenderer.clearBossShowFilter();
    }

    public static boolean hasShaderEffects() {
        for (ActiveEffect active : ACTIVE) {
            if (BossShowEffectCue.SHADER_EFFECT.equals(active.cue.type())) return true;
        }
        return false;
    }

    public static void applyShaderUniforms(ShaderInstance shader, float partialTick) {
        set(shader, "ChromaticStrength", combined("chromatic_aberration", "strength", partialTick, 0.0F));
        set(shader, "ChromaticAngle", latest("chromatic_aberration", "angle", 0.0F));
        set(shader, "ChromaticPulseAmount", latest("chromatic_aberration", "pulse_amount", 0.0F));
        set(shader, "ChromaticPulseSpeed", latest("chromatic_aberration", "pulse_speed", 1.0F));
        set(shader, "WaveAmplitude", combined("wave_distortion", "amplitude", partialTick, 0.0F));
        set(shader, "WaveFrequency", latest("wave_distortion", "frequency", 8.0F));
        set(shader, "WaveSpeed", latest("wave_distortion", "speed", 1.5F));
        set(shader, "HeatStrength", combined("heat_haze", "strength", partialTick, 0.0F));
        set(shader, "HeatDensity", latest("heat_haze", "density", 7.0F));
        set(shader, "HeatSpeed", latest("heat_haze", "speed", 0.8F));
        set(shader, "HeatVerticalBias", latest("heat_haze", "vertical_bias", 0.65F));
        set(shader, "BrightnessBase", latestWeighted("brightness_pulse", "base_brightness", partialTick, 1.0F));
        set(shader, "BrightnessPulseAmount", combined("brightness_pulse", "pulse_amount", partialTick, 0.0F));
        set(shader, "BrightnessPulseSpeed", latest("brightness_pulse", "pulse_speed", 1.0F));
        set(shader, "HueStrength", combined("hue_cycle", "strength", partialTick, 0.0F));
        set(shader, "HueSpeed", latest("hue_cycle", "speed", 0.2F));
        set(shader, "ScanlineStrength", combined("scanlines", "strength", partialTick, 0.0F));
        set(shader, "ScanlineDensity", latest("scanlines", "density", 120.0F));
        set(shader, "ScanlineSpeed", latest("scanlines", "speed", 0.0F));
        set(shader, "VignetteStrength", combined("vignette", "strength", partialTick, 0.0F));
        set(shader, "VignetteRadius", latest("vignette", "radius", 0.7F));
        set(shader, "VignetteSoftness", latest("vignette", "softness", 0.25F));
    }

    public static float shakeYaw(float partialTick) { return shake("yaw", partialTick, 1.0F); }
    public static float shakePitch(float partialTick) { return shake("pitch", partialTick, 0.75F); }
    public static float shakeRoll(float partialTick) { return shake("roll", partialTick, 0.4F); }

    private static float shake(String axis, float partialTick, float fallback) {
        double result = 0.0;
        for (ActiveEffect active : ACTIVE) {
            BossShowEffectCue cue = active.cue;
            if (!BossShowEffectCue.CAMERA_SHAKE.equals(cue.type())) continue;
            double time = active.age + partialTick;
            double frequency = cue.parameter("frequency", 1.0F);
            double seed = cue.parameter("seed", cue.tick() * 0.731F);
            double phase = switch (axis) {
                case "pitch" -> 2.094;
                case "roll" -> 4.188;
                default -> 0.0;
            };
            double noise = Math.sin((time * frequency + seed) * 2.399 + phase)
                * 0.65 + Math.sin((time * frequency * 1.73 + seed) * 1.117 + phase) * 0.35;
            result += noise * cue.parameter(axis, fallback) * envelope(active, partialTick);
        }
        return (float) result;
    }

    private static float combined(String effect, String parameter, float partialTick, float fallback) {
        float result = 0.0F;
        boolean found = false;
        for (ActiveEffect active : ACTIVE) {
            if (!matches(active, effect)) continue;
            result += active.cue.parameter(parameter, fallback) * envelope(active, partialTick);
            found = true;
        }
        return found ? result : fallback;
    }

    private static float latest(String effect, String parameter, float fallback) {
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            ActiveEffect active = ACTIVE.get(i);
            if (matches(active, effect)) return active.cue.parameter(parameter, fallback);
        }
        return fallback;
    }

    private static float latestWeighted(String effect, String parameter, float partialTick, float fallback) {
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            ActiveEffect active = ACTIVE.get(i);
            if (matches(active, effect)) {
                float weight = envelope(active, partialTick);
                return Mth.lerp(weight, fallback, active.cue.parameter(parameter, fallback));
            }
        }
        return fallback;
    }

    private static boolean matches(ActiveEffect active, String effect) {
        return BossShowEffectCue.SHADER_EFFECT.equals(active.cue.type()) && effect.equals(active.cue.effect());
    }

    private static float envelope(ActiveEffect active, float partialTick) {
        BossShowEffectCue cue = active.cue;
        float age = active.age + partialTick;
        float weight = 1.0F;
        if (cue.fadeInTicks() > 0) weight = Math.min(weight, age / cue.fadeInTicks());
        if (cue.fadeOutTicks() > 0) {
            weight = Math.min(weight, (cue.durationTicks() - age) / cue.fadeOutTicks());
        }
        weight = Mth.clamp(weight, 0.0F, 1.0F);
        return cue.easing() == Curve.STEP && weight > 0.0F
            ? 1.0F : (float) cue.easing().apply(weight);
    }

    private static void updateFilterOverride(float partialTick) {
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            ActiveEffect active = ACTIVE.get(i);
            if (!BossShowEffectCue.FILTER.equals(active.cue.type())) continue;
            try {
                FilterType filter = FilterType.valueOf(active.cue.effect().toUpperCase(Locale.ROOT));
                FilterRenderer.setBossShowFilter(filter, envelope(active, partialTick),
                    active.cue.parameter("speed", 1.0F));
            } catch (IllegalArgumentException ignored) {
                FilterRenderer.clearBossShowFilter();
            }
            return;
        }
        FilterRenderer.clearBossShowFilter();
    }

    private static void set(ShaderInstance shader, String name, float value) {
        if (shader.getUniform(name) != null) shader.getUniform(name).set(value);
    }

    private static final class ActiveEffect {
        private final BossShowEffectCue cue;
        private int age;

        private ActiveEffect(BossShowEffectCue cue) {
            this.cue = cue;
        }
    }
}
