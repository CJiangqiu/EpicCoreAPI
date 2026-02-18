#version 150

uniform vec4 ColorModulator;
uniform float GameTime;
uniform sampler2D Sampler2;
uniform float CameraYaw;
uniform float CameraPitch;

in vec4 vertexColor;
in vec2 texCoord0;
in vec2 texCoord2;
in vec3 skyDir;
out vec4 fragColor;

#define PI 3.14159265359

float hash(float n) {
    return fract(sin(n) * 43758.5453123);
}

float hash2(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float a = hash2(i);
    float b = hash2(i + vec2(1.0, 0.0));
    float c = hash2(i + vec2(0.0, 1.0));
    float d = hash2(i + vec2(1.0, 1.0));

    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p, int octaves) {
    float value = 0.0;
    float amplitude = 0.5;
    float frequency = 1.0;

    for (int i = 0; i < octaves; i++) {
        if (i >= 6) break;
        value += amplitude * noise(p * frequency);
        amplitude *= 0.5;
        frequency *= 2.0;
    }

    return value;
}

float auroraWave(vec2 uv, float t, float seed) {
    float baseWave = sin(uv.x * 2.0 + t * 0.3 + seed * 10.0) * 0.5;
    baseWave += sin(uv.x * 4.5 + t * 0.5 + seed * 7.0) * 0.25;
    baseWave += sin(uv.x * 1.2 + t * 0.2 - seed * 5.0) * 0.3;

    float noiseOffset = fbm(vec2(uv.x * 0.8 + t * 0.1, seed * 20.0), 4) * 0.6;
    baseWave += noiseOffset;

    return baseWave;
}

float auroraBand(vec2 uv, float t, float yCenter, float thickness, float seed) {
    float wave = auroraWave(uv, t, seed);
    float y = uv.y - yCenter - wave * 0.15;

    float band = exp(-y * y / (thickness * thickness * 2.0));

    float shimmer = noise(vec2(uv.x * 8.0 + t * 2.0, uv.y * 4.0 + seed * 10.0));
    shimmer = 0.7 + 0.3 * shimmer;

    float curtain = noise(vec2(uv.x * 15.0 + t * 0.5, t * 0.3 + seed));
    curtain = 0.5 + 0.5 * curtain;

    return band * shimmer * curtain;
}

void main() {
    vec3 dir = normalize(skyDir);

    float sb = sin(-CameraPitch);
    float cb = cos(-CameraPitch);
    dir = vec3(dir.x, dir.y * cb - dir.z * sb, dir.y * sb + dir.z * cb);

    float sa = sin(-CameraYaw);
    float ca = cos(-CameraYaw);
    dir = vec3(dir.z * sa + dir.x * ca, dir.y, dir.z * ca - dir.x * sa);

    vec2 skyPos = dir.xz / max(0.15, 1.0 + dir.y);
    vec2 uvDeriv = fwidth(texCoord0);
    float useUvSpace = step(0.00001, uvDeriv.x + uvDeriv.y);

    vec2 uv;
    if (useUvSpace > 0.5) {
        float dx = abs(dFdx(texCoord0.x));
        float dy = abs(dFdy(texCoord0.y));
        float aspectRatio = (dy > 0.00001) ? (dy / dx) : 1.0;

        vec2 centered = texCoord0 * 2.0 - 1.0;
        centered.x *= aspectRatio;
        uv = centered;
    } else {
        uv = skyPos * 0.8;
    }

    vec3 backgroundColor = vec3(0.0);
    float t = GameTime * 600.0;

    vec3 greenColor = vec3(0.1, 0.9, 0.4);
    vec3 cyanColor = vec3(0.2, 0.8, 0.7);
    vec3 purpleColor = vec3(0.6, 0.2, 0.9);
    vec3 pinkColor = vec3(0.9, 0.3, 0.6);

    float totalAlpha = 0.0;

    for (int i = 0; i < 12; i++) {
        float seed = float(i) * 1.37;
        float yCenter = -0.8 + float(i) * 0.15 + sin(t * 0.1 + seed * 3.0) * 0.12;
        float thickness = 0.1 + 0.06 * sin(t * 0.15 + seed * 2.0);

        float band = auroraBand(uv, t, yCenter, thickness, seed);

        float pulse = 0.6 + 0.4 * sin(t * 0.2 + seed * 5.0);
        band *= pulse;

        float heightFactor = clamp((float(i) / 11.0), 0.0, 1.0);
        vec3 bandColor;
        if (heightFactor < 0.33) {
            bandColor = mix(greenColor, cyanColor, heightFactor * 3.0);
        } else if (heightFactor < 0.66) {
            bandColor = mix(cyanColor, purpleColor, (heightFactor - 0.33) * 3.0);
        } else {
            bandColor = mix(purpleColor, pinkColor, (heightFactor - 0.66) * 3.0);
        }

        float indexFactor = float(i) / 11.0;
        bandColor = mix(bandColor, mix(cyanColor, purpleColor, indexFactor), 0.3);

        backgroundColor += bandColor * band * (0.4 + 0.15 * (1.0 - indexFactor));
        totalAlpha += band * 0.3;
    }

    float stars = 0.0;
    vec2 starUv = uv * 20.0;
    vec2 starGrid = floor(starUv);
    vec2 starLocal = fract(starUv);
    float starRand = hash2(starGrid);
    if (starRand > 0.84) {
        vec2 starCenter = vec2(hash2(starGrid + 0.1), hash2(starGrid + 0.2));
        float starDist = length(starLocal - starCenter);
        float twinkle = 0.5 + 0.5 * sin(t * (2.0 + starRand * 3.0) + starRand * 100.0);
        stars = smoothstep(0.08, 0.02, starDist) * twinkle * 0.3;
    }
    backgroundColor += vec3(0.9, 0.95, 1.0) * stars;

    backgroundColor += vec3(0.02, 0.01, 0.04);

    float alpha = min(1.0, totalAlpha + stars + 0.1);
    alpha = max(alpha, 0.15);

    fragColor = vec4(backgroundColor, alpha * vertexColor.a) * ColorModulator;
}
