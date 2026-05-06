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
    for (int i = 0; i < 4; i++) {
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
    // HEAT HAZE
    // ============================================================
    float hazeStrength = smoothstep(0.05, 1.0, texCoord.y) * 0.008;

    vec2 hazeCoord = texCoord;
    hazeCoord.x += fbm(vec2(texCoord.x * 8.0 + t * 0.5, texCoord.y * 6.0 + t * 0.35)) * hazeStrength;
    hazeCoord.y += fbm(vec2(texCoord.x * 7.0 + t * 0.45 + 1.7, texCoord.y * 6.0 + t * 0.25 + 3.1)) * hazeStrength * 1.3;
    hazeCoord = clamp(hazeCoord, vec2(0.001), vec2(0.999));

    vec3 scene = texture(Sampler1, hazeCoord).rgb;

    // ============================================================
    // SAND SYSTEM
    // ============================================================

    vec3 sandMain   = vec3(0.86, 0.70, 0.40);
    vec3 sandDark   = vec3(0.58, 0.42, 0.20);
    vec3 sandBright = vec3(0.94, 0.83, 0.55);

    vec2 windDir = vec2(0.55, 0.0);
    float sandCover = 0.0;

    // --- LAYER A: blowing sand wisps ---
    for (int w = 0; w < 3; w++) {
        float fw = float(w);
        vec2 wispUv = texCoord;
        wispUv -= windDir * t * (0.35 + fw * 0.15);

        float baseNoise = fbm(wispUv * vec2(3.0, 1.5) + fw * 2.7);
        float detail = fbm(wispUv * vec2(7.0, 3.0) + fw * 5.1);
        float wisp = baseNoise * 0.6 + detail * 0.4;

        wisp = smoothstep(0.38, 0.72, wisp);
        wisp *= 1.0 - abs(texCoord.y - 0.5) * 0.4;

        sandCover += wisp * (0.28 - fw * 0.07);
    }

    // --- LAYER B: settled residue (organic blobs, naturally ephemeral) ---
    {
        // Primary residue field — faster evolution so no patch is permanent
        float blobField = fbm(texCoord * vec2(4.5 * aspect, 3.5) + vec2(t * 0.15, t * 0.12));

        // Edge detail for irregular boundaries
        float edgeNoise = fbm(texCoord * vec2(9.0 * aspect, 7.0) + vec2(t * 0.08, t * 0.05));

        float residue = blobField * 0.7 + edgeNoise * 0.3;
        residue = smoothstep(0.42, 0.68, residue);

        // Internal thickness variation
        float thickness = fbm(texCoord * vec2(3.0 * aspect, 2.5) + vec2(t * 0.07, t * 0.06));
        thickness = smoothstep(0.3, 0.8, thickness);
        residue *= 0.5 + thickness * 0.5;

        // Erosion: wind eats away at residue, creating ragged edges
        float erodeField = fbm(texCoord * vec2(2.5, 3.0) + vec2(t * 0.13, t * 0.11));
        float erosion = smoothstep(0.25, 0.7, erodeField);

        float remaining = residue * (1.0 - erosion * 0.85);
        float erodedWisp = residue * erosion * fbm(texCoord * 8.0 + windDir * t * 0.3) * 0.35;

        sandCover += remaining * 0.55;
        sandCover += erodedWisp * 0.25;
    }

    // --- LAYER C: ambient fine dust ---
    {
        float dust = fbm(texCoord * vec2(6.0 * aspect, 4.0) + vec2(t * 0.22, t * 0.17));
        dust = smoothstep(0.35, 0.6, dust) * 0.08;
        sandCover += dust;
    }

    // --- Screen-bottom accumulation ---
    {
        float bottomAccum = smoothstep(0.9, 0.5, texCoord.y);
        float bottomNoise = fbm(texCoord * vec2(5.0, 10.0) + t * 0.06);
        bottomAccum *= bottomNoise * 0.12;
        sandCover += bottomAccum;
    }

    sandCover = clamp(sandCover, 0.0, 1.0);

    // ============================================================
    // COMPOSITE
    // ============================================================

    float colorVar = fbm(texCoord * 30.0 + t * 0.03) * 0.5 + 0.25;
    vec3 sandHere = mix(sandDark, sandMain, colorVar);

    vec3 result = mix(scene, sandHere, sandCover);

    // Warm tint at bottom (hot ground)
    float warmth = smoothstep(0.15, 1.0, texCoord.y) * 0.1;
    result = result * (1.0 + warmth * 0.9) + vec3(0.06, 0.02, -0.03) * warmth;

    // Atmospheric haze at bottom
    float bottomHaze = smoothstep(0.4, 1.0, texCoord.y) * 0.06;
    result = mix(result, sandBright * 0.25, bottomHaze);

    fragColor = vec4(result, 1.0);
}
