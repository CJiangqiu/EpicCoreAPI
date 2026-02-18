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

float hash2(vec2 p) {
    return fract(sin(dot(p, vec2(269.5, 183.3))) * 27463.8291);
}

void main() {
    vec3 effects = vec3(0.0);
    float time = GameTime * 600.0;

    vec3 bgColor = vec3(0.1, 0.25, 0.12);
    float bgAlpha = 0.3;

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
    vec2 screenPos = mix(skyPos * 10.0, gl_FragCoord.xy * 0.01, useUvSpace);

    for (int layer = 0; layer < 3; layer++) {
        float layerOffset = float(layer) * 23.7;
        float gridSize = 1.5 + float(layer) * 0.8;
        vec2 grid = floor((screenPos + vec2(layerOffset)) / gridSize);
        vec2 localPos = fract((screenPos + vec2(layerOffset)) / gridSize);

        float spawnRandom = hash(grid + vec2(layerOffset, layerOffset * 0.83));

        if (spawnRandom > 0.45) {
            float fireflySpeed = 0.8 + hash(grid * 1.37 + layerOffset) * 0.6;
            float fireflyPhase = hash(grid * 2.19 + layerOffset) * 6.28318;

            vec2 centerBase = vec2(
                0.3 + hash(grid * 3.21 + layerOffset) * 0.4,
                0.3 + hash(grid * 4.53 + layerOffset) * 0.4
            );

            float moveTime = time * fireflySpeed + fireflyPhase;
            vec2 offset = vec2(
                sin(moveTime * 0.7) * 0.15,
                cos(moveTime * 0.5) * 0.15
            );
            vec2 fireflyCenter = centerBase + offset;

            float distToFirefly = distance(localPos, fireflyCenter);

            float sizeRandom = hash(grid * 5.67 + layerOffset);
            float size = 0.006 + sizeRandom * 0.004;

            float glowIntensity = smoothstep(size * 3.0, size * 0.5, distToFirefly);

            float blinkSpeed = 1.2 + hash(grid * 6.89 + layerOffset) * 0.8;
            float layerPhaseOffset = float(layer) * 3.14159;
            float blinkPhase = hash(grid * 7.13 + layerOffset) * 6.28318 + layerPhaseOffset;
            float brightness = 0.3 + 0.7 * sin(time * blinkSpeed + blinkPhase);
            brightness = smoothstep(0.0, 1.0, brightness);

            float colorMix = smoothstep(0.0, size * 2.0, distToFirefly);
            vec3 fireflyColor = mix(
                vec3(1.0, 1.0, 1.0),
                vec3(1.0, 0.9, 0.3),
                colorMix
            );

            effects += fireflyColor * glowIntensity * brightness;
        }
    }

    // Sample leaf texture for background
    vec2 leafUv = screenPos * 0.5;
    vec4 leafTex = texture(Sampler0, fract(leafUv));
    vec3 leafColor = leafTex.rgb * vec3(0.4, 0.6, 0.3);

    vec3 finalColor = mix(bgColor, leafColor, 0.6) * bgAlpha + effects;
    float finalAlpha = vertexColor.a;

    fragColor = vec4(finalColor, finalAlpha) * ColorModulator;
}
