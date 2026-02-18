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
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 5; i++) {
        value += amplitude * noise(p);
        p *= 2.0;
        amplitude *= 0.5;
    }
    return value;
}

float spiral(vec2 p, float t, int arms) {
    float r = length(p);
    float angle = atan(p.y, p.x);

    float rotation = -t * 0.15;
    angle += rotation;

    float spiralTightness = 2.5;
    float spiralAngle = angle + r * spiralTightness;

    float armCount = float(arms);
    float armPattern = sin(spiralAngle * armCount) * 0.5 + 0.5;

    float falloff = exp(-r * 1.5);

    float noiseDetail = fbm(p * 3.0 + t * 0.05) * 0.4;

    float spiral = armPattern * falloff + noiseDetail * falloff;

    float core = exp(-r * r * 8.0);
    spiral += core * 0.8;

    return clamp(spiral, 0.0, 1.0);
}

float crossStar(vec2 p, float size) {
    vec2 ap = abs(p);
    float cross = smoothstep(size, 0.0, ap.x) * smoothstep(size * 0.15, 0.0, ap.y);
    cross += smoothstep(size, 0.0, ap.y) * smoothstep(size * 0.15, 0.0, ap.x);
    float core = smoothstep(size * 0.3, 0.0, length(p));
    return max(cross, core);
}

float circleStar(vec2 p, float size) {
    return smoothstep(size, size * 0.3, length(p));
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
        // GUI/Entity mode: scale proportionally
        float dx = abs(dFdx(texCoord0.x));
        float dy = abs(dFdy(texCoord0.y));
        float aspectRatio = (dy > 0.00001) ? (dy / dx) : 1.0;

        vec2 centered = texCoord0 * 2.0 - 1.0;
        centered.x *= aspectRatio;
        uv = centered * 5.0;
    } else {
        uv = skyPos * 10.0;
    }

    float t = GameTime * 800.0;

    vec3 bgDeep = vec3(0.01, 0.02, 0.08);
    vec3 result = bgDeep;

    vec2 spiralUv = uv * 0.15;
    float spiralValue = spiral(spiralUv, t, 6);

    vec3 spiralColorCore = vec3(0.9, 0.95, 1.0);
    vec3 spiralColorMid = vec3(0.3, 0.5, 0.9);
    vec3 spiralColorOuter = vec3(0.1, 0.2, 0.5);

    float r = length(spiralUv);
    vec3 spiralColor;
    if (r < 0.3) {
        spiralColor = mix(spiralColorCore, spiralColorMid, r / 0.3);
    } else {
        spiralColor = mix(spiralColorMid, spiralColorOuter, clamp((r - 0.3) / 0.7, 0.0, 1.0));
    }

    result += spiralColor * spiralValue * 0.8;

    // Scale stars for GUI mode
    float starScale = (useUvSpace > 0.5) ? 2.5 : 1.0;

    float cellSize = 1.5;
    vec2 cellId = floor(uv / cellSize);
    vec2 cellUv = fract(uv / cellSize) - 0.5;

    float starSeed = hash(cellId);
    if (starSeed > 0.45) {
        vec2 starPos = vec2(hash(cellId + 0.1), hash(cellId + 0.2)) - 0.5;
        starPos *= 0.7;
        vec2 toStar = cellUv - starPos;

        float starSize = (0.05 + hash(cellId + 0.3) * 0.08) * starScale;
        float twinkle = 0.6 + 0.4 * sin(t * (1.0 + hash(cellId + 0.4) * 2.0) + hash(cellId + 0.5) * 6.28);

        // Layer 1: cross stars only
        float star = crossStar(toStar, starSize);

        result += vec3(0.95, 0.97, 1.0) * star * twinkle;
    }

    float cellSize2 = 0.7;
    vec2 cellId2 = floor(uv / cellSize2 + 0.5);
    vec2 cellUv2 = fract(uv / cellSize2 + 0.5) - 0.5;

    float starSeed2 = hash(cellId2 + 100.0);
    if (starSeed2 > 0.8) {
        vec2 starPos2 = vec2(hash(cellId2 + 100.1), hash(cellId2 + 100.2)) - 0.5;
        starPos2 *= 0.6;
        vec2 toStar2 = cellUv2 - starPos2;

        float starSize2 = (0.02 + hash(cellId2 + 100.3) * 0.03) * starScale;
        float twinkle2 = 0.5 + 0.5 * sin(t * (1.5 + hash(cellId2 + 100.4) * 2.0) + hash(cellId2 + 100.5) * 6.28);

        float star2 = circleStar(toStar2, starSize2);
        result += vec3(0.85, 0.9, 1.0) * star2 * twinkle2 * 0.6;
    }

    float alpha = 0.9;

    fragColor = vec4(result, alpha * vertexColor.a) * ColorModulator;
}
