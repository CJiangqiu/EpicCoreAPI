#version 150

uniform vec4 ColorModulator;
uniform sampler2D Sampler2;
uniform float GameTime;
uniform float CameraYaw;
uniform float CameraPitch;

in vec4 vertexColor;
in vec2 texCoord0;
in vec2 texCoord2;
in vec3 skyDir;
out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float hash2(vec2 p) {
    return fract(sin(dot(p, vec2(269.5, 183.3))) * 27463.8291);
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
    for (int i = 0; i < 4; i++) {
        value += amplitude * noise(p);
        p *= 2.1;
        amplitude *= 0.5;
    }
    return value;
}

float sdIrregularPolygon(vec2 p, float r, float seed) {
    float angle = atan(p.y, p.x);
    float dist = length(p);

    // Create irregular edge using multiple sine waves
    float edge = r;
    edge += sin(angle * 3.0 + seed * 10.0) * r * 0.15;
    edge += sin(angle * 5.0 + seed * 7.0) * r * 0.1;
    edge += sin(angle * 7.0 + seed * 13.0) * r * 0.08;

    return dist - edge;
}

float sdBox(vec2 p, vec2 b) {
    vec2 d = abs(p) - b;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}

void main() {
    float time = GameTime * 400.0;

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
    vec2 screenPos = mix(skyPos * 6.0, gl_FragCoord.xy * 0.006, useUvSpace);

    // Procedural flowing lava
    vec3 lavaYellow = vec3(1.0, 0.85, 0.2);
    vec3 lavaOrange = vec3(1.0, 0.4, 0.05);
    vec3 lavaRed = vec3(0.7, 0.15, 0.05);

    float flow1 = fbm(screenPos * 2.0 + vec2(time * 0.025, time * 0.015));
    float flow2 = fbm(screenPos * 1.5 + vec2(-time * 0.02, time * 0.03));
    float combined = flow1 * 0.6 + flow2 * 0.4;

    vec3 lavaColor = mix(lavaRed, lavaOrange, smoothstep(0.3, 0.6, combined));
    lavaColor = mix(lavaColor, lavaYellow, smoothstep(0.6, 0.8, combined));

    float pulse = 0.9 + 0.1 * sin(time * 0.4);
    vec3 finalColor = lavaColor * pulse;

    // Floating irregular magma blocks
    vec3 rockColor = vec3(0.15, 0.08, 0.05);
    vec3 glowOrange = vec3(1.0, 0.5, 0.1);

    for (int i = 0; i < 10; i++) {
        float seed = float(i) * 1.731;

        // Drifting movement
        float speedX = 0.04 + hash(vec2(seed, 0.0)) * 0.03;
        float speedY = 0.03 + hash(vec2(seed, 1.0)) * 0.02;
        float phaseX = hash(vec2(seed, 2.0)) * 50.0;
        float phaseY = hash(vec2(seed, 3.0)) * 40.0;

        vec2 blockPos;
        blockPos.x = mod(time * speedX + phaseX, 14.0) - 7.0;
        blockPos.y = mod(time * speedY + phaseY, 10.0) - 5.0;

        // Random size and rotation
        float blockSize = 0.35 + hash(vec2(seed, 4.0)) * 0.3;
        float rotation = hash(vec2(seed, 5.0)) * 6.28 + time * 0.05;

        // Rotate point around block center
        vec2 toBlock = screenPos - blockPos;
        float c = cos(rotation);
        float s = sin(rotation);
        vec2 rotated = vec2(c * toBlock.x - s * toBlock.y, s * toBlock.x + c * toBlock.y);

        // Irregular polygon distance
        float dist = sdIrregularPolygon(rotated, blockSize, seed);

        // Sharp dark rock interior
        float rockMask = smoothstep(0.005, -0.005, dist);

        // Glowing edge
        float glowMask = smoothstep(0.12, 0.0, dist) * (1.0 - rockMask * 0.9);
        float edgePulse = 0.7 + 0.3 * sin(time * 0.5 + seed * 3.0);

        finalColor = mix(finalColor, rockColor, rockMask * 0.95);
        finalColor += glowOrange * glowMask * edgePulse * 0.5;
    }

    float finalAlpha = vertexColor.a;

    fragColor = vec4(finalColor, finalAlpha) * ColorModulator;
}
