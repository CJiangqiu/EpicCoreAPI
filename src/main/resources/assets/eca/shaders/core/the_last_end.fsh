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

float random(vec2 st) {
    return fract(sin(dot(st.xy, vec2(12.9898, 78.233))) * 43758.5453123);
}

float random1(float v) {
    return fract(sin(v * 91.3458) * 47453.5453);
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
    vec2 screenPos = mix(skyPos * 10.0, gl_FragCoord.xy * 0.01, useUvSpace);
    vec3 backgroundColor = vec3(0.0);
    float t = GameTime * 1600.0;

    for (int layer = 0; layer < 2; layer++) {
        float layerOffset = float(layer) * 19.3;
        float starSize = 0.28 + float(layer) * 0.22;
        vec2 starGrid = floor((screenPos + vec2(layerOffset)) / starSize);
        vec2 starLocal = fract((screenPos + vec2(layerOffset)) / starSize);
        float starSeed = dot(starGrid, vec2(127.1, 311.7)) + layerOffset;
        float starRandom = random(starGrid + vec2(layerOffset, layerOffset * 0.7));

        if (starRandom > 0.91) {
            vec2 starCenter = vec2(
                fract(sin(starSeed) * 12.9898),
                fract(cos(starSeed) * 78.233)
            );
            float distToStar = distance(starLocal, starCenter);

            float sizeRandom = fract(sin(starSeed * 7.123) * 43758.5453);
            float sizeMultiplier = 0.60 + sizeRandom * 0.35;

            float starIntensity = smoothstep(0.14 * sizeMultiplier, 0.055 * sizeMultiplier, distToStar);

            float twinkleSpeed = 0.55 + random1(starSeed * 0.37) * 1.10;
            float twinklePhase = random1(starSeed * 1.91) * 6.28318;
            float brightness = 0.45 + 0.55 * sin(t * twinkleSpeed + twinklePhase);
            brightness = 0.55 + 0.45 * brightness;

            vec3 starColor = mix(vec3(0.88, 0.93, 1.0), vec3(1.0), random1(starSeed * 2.73));
            backgroundColor += starColor * starIntensity * brightness;
        }
    }

    for (int lane = 0; lane < 2; lane++) {
        float laneOffset = float(lane) * 37.7;
        float laneSize = 2.3 + float(lane) * 1.1;
        vec2 laneUv = screenPos / laneSize;
        vec2 laneGrid = floor(laneUv);
        vec2 localPos = fract(laneUv);
        vec2 laneId = laneGrid + vec2(laneOffset, laneOffset * 0.63);

        float laneSpawn = random(laneId);
        if (laneSpawn > 0.78) {
            float segmentDuration = 2.4 + random(laneId + vec2(1.7, 4.9)) * 2.8;
            float segmentTime = t / segmentDuration + random(laneId + vec2(8.3, 2.1)) * 9.0;
            float segmentIndex = floor(segmentTime);
            float segmentPhase = fract(segmentTime);

            float segmentSpawn = random(laneId + vec2(segmentIndex, segmentIndex * 1.37));
            if (segmentSpawn > 0.80) {
                float startJitterX = random(laneId + vec2(segmentIndex * 2.1, 5.4));
                float startJitterY = random(laneId + vec2(7.6, segmentIndex * 3.2));
                float endJitterX = random(laneId + vec2(segmentIndex * 4.1, 1.3));
                float endJitterY = random(laneId + vec2(2.6, segmentIndex * 5.7));

                vec2 meteorStart = vec2(1.15 + startJitterX * 0.25, 1.05 + startJitterY * 0.35);
                vec2 meteorEnd = vec2(-0.15 - endJitterX * 0.25, -0.10 - endJitterY * 0.35);
                vec2 meteorPos = mix(meteorStart, meteorEnd, segmentPhase);

                vec2 meteorDir = normalize(meteorEnd - meteorStart);
                vec2 toMeteor = localPos - meteorPos;
                float alongTrail = dot(toMeteor, meteorDir);
                vec2 perpVec = vec2(-meteorDir.y, meteorDir.x);
                float perpDist = abs(dot(toMeteor, perpVec));

                float trailLength = 0.24 + random(laneId + vec2(3.3, segmentIndex)) * 0.24;
                float trailWidth = 0.014 + random(laneId + vec2(segmentIndex, 6.8)) * 0.016;

                if (alongTrail > -trailLength && alongTrail < 0.0 && perpDist < trailWidth) {
                    float trailFade = (alongTrail + trailLength) / trailLength;
                    float widthFade = 1.0 - (perpDist / trailWidth);
                    float meteorIntensity = trailFade * widthFade;

                    float headGlow = smoothstep(0.05, 0.0, distance(localPos, meteorPos));
                    vec3 meteorColor = mix(vec3(0.58, 0.28, 0.92), vec3(0.88, 0.62, 1.0), 0.35 + 0.65 * trailFade);

                    backgroundColor += meteorColor * (meteorIntensity * 1.25 + headGlow * 0.95);
                }
            }
        }
    }

    vec3 finalColor = backgroundColor;
    fragColor = vec4(finalColor, vertexColor.a) * ColorModulator;
}
