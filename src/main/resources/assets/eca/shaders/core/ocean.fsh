#version 150

uniform vec4 ColorModulator;
uniform sampler2D Sampler0;
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

float wave(vec2 pos, float time) {
    float w = 0.0;
    w += sin(pos.x * 1.5 + time * 0.4) * 0.35;
    w += sin(pos.x * 3.0 + pos.y * 2.0 + time * 0.7) * 0.2;
    w += sin(pos.x * 5.0 - pos.y * 1.5 + time * 1.1) * 0.12;
    w += sin(pos.y * 2.5 + time * 0.5) * 0.15;
    return w;
}

void main() {
    float time = GameTime * 600.0;

    vec3 deepBlue = vec3(0.2, 0.5, 0.7);
    vec3 lightBlue = vec3(0.4, 0.75, 0.9);

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
    vec2 screenPos = mix(skyPos * 8.0, gl_FragCoord.xy * 0.008, useUvSpace);

    float waveHeight = wave(screenPos, time);
    float normalizedHeight = waveHeight * 0.5 + 0.5;

    vec3 waterColor = mix(deepBlue, lightBlue, normalizedHeight);

    vec3 finalColor = waterColor;

    // Bubbles rising from bottom with swaying motion
    for (int i = 0; i < 22; i++) {
        float seed = float(i) * 1.7319;
        float bubbleSpeed = 0.3 + hash(vec2(seed, 0.0)) * 0.2;
        float bubbleSize = 0.08 + hash(vec2(seed, 1.0)) * 0.06;

        // Horizontal position with sway
        float baseX = hash(vec2(seed, 2.0)) * 20.0 - 10.0;
        float swayAmount = 0.3 + hash(vec2(seed, 3.0)) * 0.2;
        float swaySpeed = 1.5 + hash(vec2(seed, 4.0)) * 1.0;
        float sway = sin(time * swaySpeed + seed * 10.0) * swayAmount;
        float bubbleX = baseX + sway;

        // Vertical position - rising from bottom
        float yRange = 8.0;
        float bubbleY = mod(time * bubbleSpeed + hash(vec2(seed, 5.0)) * yRange, yRange) - yRange * 0.5;

        vec2 bubblePos = vec2(bubbleX, bubbleY);
        vec2 toBubble = screenPos - bubblePos;

        // Sample bubble texture
        vec2 bubbleUV = toBubble / bubbleSize + 0.5;
        if (bubbleUV.x >= 0.0 && bubbleUV.x <= 1.0 && bubbleUV.y >= 0.0 && bubbleUV.y <= 1.0) {
            vec4 bubbleTex = texture(Sampler0, bubbleUV);
            finalColor = mix(finalColor, bubbleTex.rgb, bubbleTex.a * 0.7);
        }
    }

    float finalAlpha = vertexColor.a;

    fragColor = vec4(finalColor, finalAlpha) * ColorModulator;
}
