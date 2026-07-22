#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 ScreenSize;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 3; i++) {
        v += amp * noise(p);
        p *= 2.1;
        amp *= 0.5;
    }
    return v;
}

void main() {
    float aspect = ScreenSize.x / ScreenSize.y;
    float t = Time;

    vec3 scene = texture(Sampler1, texCoord).rgb;

    // ============================================================
    // SNOWFLAKES — discrete particles, slow falling, appearing & fading
    // ============================================================

    float snowFlakes = 0.0;
    vec2 wind = vec2(0.04, -0.18); // gentle breeze, mostly falling

    // Narrow vertical columns for flake spawning
    float cols = 25.0;
    float colW = 1.0 / cols;
    float cid = floor(texCoord.x / colW);

    for (int dc = -1; dc <= 1; dc++) {
        float col = cid + float(dc);

        // Several flakes at different phases within each column
        for (int fi = 0; fi < 5; fi++) {
            float ff = float(fi);

            // Unique flake identity
            float seed0 = hash(vec2(col, ff * 0.7));
            float seed1 = hash(vec2(col, ff * 0.7 + 3.1));
            float seed2 = hash(vec2(col, ff * 0.7 + 7.2));
            float seed3 = hash(vec2(col, ff * 0.7 + 11.3));

            // Only ~30% of slots have a flake at any time
            if (seed0 > 0.30) continue;

            // Lifecycle: appear → float → fade (8–15 seconds, matches slow fall)
            float lifetime = 8.0 + seed1 * 7.0;
            float phaseOffset = seed2 * lifetime;
            float age = mod(t + phaseOffset, lifetime);
            float ageNorm = age / lifetime;

            // Quick fade in, slow fade out
            float visibility = smoothstep(0.0, 0.06, ageNorm)
                             * (1.0 - smoothstep(0.75, 1.0, ageNorm));
            if (visibility < 0.01) continue;

            // Horizontal position (jittered within column)
            float xJitter = (seed0 - 0.5) * colW * 0.9;
            float x = (col + 0.5) * colW + xJitter;

            // Fall from top of screen to bottom
            float fallSpeed = 0.025 + seed3 * 0.05; // UV/s — gentle drift
            float yStart = 1.08;
            float y = yStart - age * fallSpeed;

            // Gentle horizontal wobble as it falls
            float wobbleAmp = 0.003 + seed0 * 0.006;
            float wobbleFreq = 1.5 + seed2 * 3.0;
            x += sin(age * wobbleFreq + seed1 * 6.28) * wobbleAmp;

            // Distance from pixel to flake center
            float dx = (texCoord.x - x) * aspect; // correct for aspect
            float dy = texCoord.y - y;
            float dist = sqrt(dx * dx + dy * dy);

            // Varying flake sizes — larger, softer
            float radius = 0.012 + seed3 * 0.022;

            if (dist >= radius) continue;

            // Soft fluffy flake, not a bright pinpoint
            float flake = 1.0 - smoothstep(0.0, radius, dist);
            flake = flake * flake; // even softer falloff
            flake *= visibility * (0.35 + seed1 * 0.4);

            snowFlakes += flake;
        }
    }

    // --- Very subtle ground accumulation (light dusting, not heavy patches) ---
    float groundSnow = 0.0;
    {
        float dustField = fbm(texCoord * vec2(3.0 * aspect, 2.5) + vec2(t * 0.06, t * 0.05));
        float dust = smoothstep(0.45, 0.65, dustField);
        // Much fainter than desert sand — just a light dusting
        groundSnow += dust * 0.10;

        // Bottom edge gets slightly more
        float bottom = smoothstep(0.95, 0.5, texCoord.y);
        groundSnow += bottom * fbm(texCoord * 8.0 + t * 0.04) * 0.06;
    }

    float snowCover = snowFlakes + groundSnow;
    snowCover = clamp(snowCover, 0.0, 1.0);

    // ============================================================
    // COMPOSITE
    // ============================================================

    // White / pale blue snow colour
    vec3 snowColor = mix(vec3(0.88, 0.92, 0.97), vec3(0.97, 0.98, 1.0), snowFlakes);

    vec3 result = mix(scene, snowColor, snowCover);

    // Cool blue-white global tint
    result = result * 0.92 + vec3(0.75, 0.85, 0.96) * 0.08;

    // Slight desaturation
    float lum = dot(result, vec3(0.299, 0.587, 0.114));
    result = mix(result, vec3(lum), 0.06);

    fragColor = vec4(result, 1.0);
}
