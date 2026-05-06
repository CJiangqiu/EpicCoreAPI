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

vec2 hash2(vec2 p) {
    float h = dot(p, vec2(127.1, 311.7));
    return fract(sin(vec2(h, dot(p, vec2(269.5, 183.3)))) * 43758.5453123);
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

    // ============================================================
    // PSYCHEDELIC WAVY DISTORTION — full-screen undulation
    // ============================================================

    // Multiple criss-crossing sine waves for a trippy "breathing" warp
    float warpX = 0.0;
    float warpY = 0.0;

    warpX += sin(texCoord.y * 6.0 + t * 2.0) * cos(texCoord.x * 5.0 + t * 1.7) * 0.003;
    warpX += cos(texCoord.y * 9.0 - t * 2.3) * sin(texCoord.x * 7.0 - t * 1.4) * 0.0025;
    warpX += sin(texCoord.y * 3.5 + t * 1.1) * 0.0018;

    warpY += cos(texCoord.x * 6.0 + t * 1.9) * sin(texCoord.y * 5.5 + t * 1.6) * 0.003;
    warpY += sin(texCoord.x * 8.0 - t * 2.1) * cos(texCoord.y * 4.0 - t * 1.3) * 0.0025;
    warpY += cos(texCoord.x * 3.0 + t * 0.9) * 0.0018;

    // Subtle pulsing
    float pulse = 1.0 + sin(t * 0.7) * 0.15 + sin(t * 1.3) * 0.1;
    warpX *= pulse;
    warpY *= pulse;

    vec2 warpCoord = texCoord + vec2(warpX, warpY);
    warpCoord = clamp(warpCoord, vec2(0.001), vec2(0.999));

    // ============================================================
    // CHROMATIC ABERRATION — R/B offset for psychedelic colour fringe
    // ============================================================
    float caStrength = 0.004 * pulse;
    float r = texture(Sampler1, warpCoord + vec2(caStrength, 0.0)).r;
    float g = texture(Sampler1, warpCoord).g;
    float b = texture(Sampler1, warpCoord - vec2(caStrength, 0.0)).b;
    vec3 scene = vec3(r, g, b);

    // ============================================================
    // TOXIC BUBBLES — green glowing particles floating upward
    // ============================================================

    float bubbles = 0.0;
    vec3 bubbleColor = vec3(0.3, 0.95, 0.25);
    vec3 bubbleGlow  = vec3(0.6, 1.0, 0.3);

    float cols = 20.0;
    float colW = 1.0 / cols;
    float cid = floor(texCoord.x / colW);

    for (int dc = -1; dc <= 1; dc++) {
        float col = cid + float(dc);

        for (int bi = 0; bi < 4; bi++) {
            float bf = float(bi);

            float seed0 = hash(vec2(col, bf + 50.0));
            float seed1 = hash(vec2(col, bf + 53.0));
            float seed2 = hash(vec2(col, bf + 56.0));
            float seed3 = hash(vec2(col, bf + 59.0));

            if (seed0 > 0.22) continue;

            // Lifecycle: 3–6 seconds
            float lifetime = 3.0 + seed1 * 3.0;
            float offset = seed2 * lifetime;
            float age = mod(t + offset, lifetime);
            float ageNorm = age / lifetime;

            // Fade in, float up, fade out
            float visibility = smoothstep(0.0, 0.1, ageNorm)
                             * (1.0 - smoothstep(0.7, 1.0, ageNorm));
            if (visibility < 0.01) continue;

            // Horizontal position
            float xJitter = (seed0 - 0.5) * colW * 0.85;
            float x = (col + 0.5) * colW + xJitter;

            // Float UP from bottom
            float riseSpeed = 0.03 + seed3 * 0.06; // UV/s
            float yStart = -0.06;
            float y = yStart + age * riseSpeed;

            // Gentle horizontal sway
            float swayAmp = 0.004 + seed0 * 0.008;
            float swayFreq = 1.2 + seed2 * 2.5;
            x += sin(age * swayFreq + seed1 * 6.28) * swayAmp;

            // Distance
            float dx = (texCoord.x - x) * aspect;
            float dy = texCoord.y - y;
            float dist = sqrt(dx * dx + dy * dy);

            float radius = 0.008 + seed3 * 0.018;
            if (dist >= radius) continue;

            // Soft glowing bubble
            float bubble = 1.0 - smoothstep(0.0, radius, dist);
            bubble = bubble * bubble;
            bubble *= visibility * (0.3 + seed1 * 0.4);

            bubbles += bubble;
        }
    }

    // --- Floating toxic spores (smaller, more numerous) ---
    float spores = 0.0;
    {
        float sporeGrid = 30.0;
        vec2 sgv = texCoord * sporeGrid;
        vec2 sid = floor(sgv);

        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec2 cell = sid + vec2(x, y);
                float h = hash(cell + vec2(300.0, 400.0));
                if (h > 0.10) continue;

                float life = 2.5 + hash(cell + vec2(301.0, 401.0)) * 3.0;
                float off = hash(cell + vec2(302.0, 402.0)) * life;
                float age = mod(t + off, life);
                float vis = smoothstep(0.0, 0.1, age/life) * (1.0 - smoothstep(0.7, 1.0, age/life));
                if (vis < 0.01) continue;

                vec2 center = (cell + hash2(cell + vec2(303.0, 403.0))) / sporeGrid;
                float rise = 0.02 + hash(cell + vec2(304.0, 404.0)) * 0.04;
                center.y = fract(center.y + age * rise + t * 0.01);

                float dist = length(texCoord - center);
                float rad = 0.004 + h * 0.008;
                if (dist < rad) {
                    spores += (1.0 - dist/rad) * vis * 0.25;
                }
            }
        }
    }

    float toxicCover = bubbles + spores;
    toxicCover = clamp(toxicCover, 0.0, 1.0);

    // ============================================================
    // GREEN VIGNETTE — dark edges with green tinge
    // ============================================================
    float edgeX = abs(texCoord.x - 0.5) * 2.0;
    float edgeY = abs(texCoord.y - 0.5) * 2.0;
    float vignette = smoothstep(0.3, 1.0, max(edgeX, edgeY));
    vignette *= 0.5 + sin(t * 0.5) * 0.15; // gentle pulse

    // ============================================================
    // COMPOSITE
    // ============================================================

    // Green-yellow colour shift
    vec3 result = scene;
    // Boost green channel
    result.g = result.g * 1.15 + 0.04;
    // Shift red toward yellow-green
    result.r = result.r * 0.85;
    // Slightly suppress blue
    result.b = result.b * 0.80;

    // Blend toxic bubbles over scene
    vec3 toxColor = mix(bubbleColor, bubbleGlow, bubbles);
    result = mix(result, toxColor, toxicCover);

    // Dark green vignette
    vec3 vignetteColor = vec3(0.02, 0.08, 0.02);
    result = mix(result, vignetteColor, vignette * 0.55);

    // Overall sickly green-yellow wash
    result = result * 0.88 + vec3(0.15, 0.28, 0.05) * 0.12;

    fragColor = vec4(result, 1.0);
}
