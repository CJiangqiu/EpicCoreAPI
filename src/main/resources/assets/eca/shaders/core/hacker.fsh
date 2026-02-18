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

float hash(float n) {
    return fract(sin(n) * 43758.5453123);
}

float hash2(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float digit0(vec2 p) {
    float d = abs(length(p) - 0.3);
    return smoothstep(0.1, 0.02, d);
}

float digit1(vec2 p) {
    float line = smoothstep(0.1, 0.02, abs(p.x));
    line *= step(-0.4, p.y) * step(p.y, 0.4);
    return line;
}

int getEcaBit(int idx) {
    if (idx == 0) return 0;
    if (idx == 1) return 1;
    if (idx == 2) return 0;
    if (idx == 3) return 0;
    if (idx == 4) return 0;
    if (idx == 5) return 1;
    if (idx == 6) return 0;
    if (idx == 7) return 1;
    if (idx == 8) return 0;
    if (idx == 9) return 1;
    if (idx == 10) return 0;
    if (idx == 11) return 0;
    if (idx == 12) return 0;
    if (idx == 13) return 0;
    if (idx == 14) return 1;
    if (idx == 15) return 1;
    if (idx == 16) return 0;
    if (idx == 17) return 1;
    if (idx == 18) return 0;
    if (idx == 19) return 0;
    if (idx == 20) return 0;
    if (idx == 21) return 0;
    if (idx == 22) return 0;
    if (idx == 23) return 1;
    return 0;
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
        uv = centered * 15.0;
    } else {
        uv = skyPos * 15.0;
    }

    float t = GameTime * 2000.0;

    vec3 green = vec3(0.0, 0.9, 0.3);

    float cellW = 0.5;
    float cellH = 0.7;

    vec2 cellId = floor(uv / vec2(cellW, cellH));
    vec2 cellUv = fract(uv / vec2(cellW, cellH)) - 0.5;

    float colSeed = hash(cellId.x * 0.123);
    float fallSpeed = 0.8 + colSeed * 1.2;
    float startOffset = hash(cellId.x * 0.456) * 50.0;

    float streamY = mod(t * fallSpeed * 0.03 + startOffset, 40.0);
    float distFromHead = streamY - cellId.y;

    float tailLen = 12.0 + colSeed * 8.0;

    int bitIdx = int(mod(cellId.x + 100.0, 24.0));
    float rnd = hash2(cellId + floor(t * 0.15));

    bool isOne;
    if (colSeed > 0.5) {
        isOne = getEcaBit(bitIdx) == 1;
    } else {
        isOne = rnd > 0.5;
    }

    float digit;
    if (isOne) {
        digit = digit1(cellUv);
    } else {
        digit = digit0(cellUv);
    }

    float brightness = 0.0;
    if (distFromHead > 0.0 && distFromHead < tailLen) {
        brightness = 1.0 - distFromHead / tailLen;
    }

    vec3 black = vec3(0.0, 0.01, 0.0);
    vec3 result = black + green * digit * brightness;

    float alpha = 0.9;

    fragColor = vec4(result, alpha * vertexColor.a) * ColorModulator;
}
