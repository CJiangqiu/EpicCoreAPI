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

float random(vec2 st) {
    return fract(sin(dot(st.xy, vec2(12.9898, 78.233))) * 43758.5453123);
}

float random1(float v) {
    return fract(sin(v * 91.3458) * 47453.5453);
}

vec4 sampleRotatedPetal(sampler2D tex, vec2 uv, vec2 center, float angle, float size) {
    vec2 d = (uv - center) / size;
    float c = cos(-angle);
    float s = sin(-angle);
    vec2 rotated = vec2(c * d.x - s * d.y, s * d.x + c * d.y);
    vec2 sampleUV = rotated * 0.5 + 0.5;

    if (sampleUV.x < 0.0 || sampleUV.x > 1.0 || sampleUV.y < 0.0 || sampleUV.y > 1.0) {
        return vec4(0.0);
    }

    return texture(tex, sampleUV);
}

void main() {
    vec4 baseTexture = texture(Sampler0, texCoord0);

    vec3 effects = vec3(0.0);
    vec2 cameraOffsetPetal = vec2(CameraYaw * 2.0, -CameraPitch * 3.5);
    vec2 screenPos = gl_FragCoord.xy * 0.007 + cameraOffsetPetal;
    float time = GameTime * 600.0;

    vec3 bgColor = vec3(1.0, 0.6, 0.75);
    float bgAlpha = 0.25;

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
    // Use screen coords but offset by camera rotation to appear fixed in sky
    vec2 cameraOffset = vec2(CameraYaw * 3.0, -CameraPitch * 5.0);
    vec2 starScreenPos = gl_FragCoord.xy * 0.01 + cameraOffset;
    float starTime = GameTime * 1600.0;

    for (int layer = 0; layer < 2; layer++) {
        float layerOffset = float(layer) * 19.3;
        float starSize = 0.28 + float(layer) * 0.22;
        vec2 starGrid = floor((starScreenPos + vec2(layerOffset)) / starSize);
        vec2 starLocal = fract((starScreenPos + vec2(layerOffset)) / starSize);
        float starSeed = dot(starGrid, vec2(127.1, 311.7)) + layerOffset;
        float starRandom = random(starGrid + vec2(layerOffset, layerOffset * 0.7));

        if (starRandom > 0.91 && starRandom <= 0.97) {
            vec2 starCenter = vec2(
                fract(sin(starSeed) * 12.9898),
                fract(cos(starSeed) * 78.233)
            );
            float distToStar = distance(starLocal, starCenter);

            float sizeRandom = fract(sin(starSeed * 7.123) * 43758.5453);
            float sizeMultiplier = 0.60 + sizeRandom * 0.35;

            float starIntensity = smoothstep(0.14 * sizeMultiplier, 0.055 * sizeMultiplier, distToStar);

            float twinkleSpeed = 0.65 + random1(starSeed * 0.53) * 1.30;
            float twinklePhase = random1(starSeed * 2.17) * 6.28318;
            float brightness = 0.40 + 0.60 * sin(starTime * twinkleSpeed + twinklePhase);
            brightness = 0.50 + 0.50 * brightness;

            vec3 starColor = vec3(0.95, 0.98, 1.0);
            effects += starColor * starIntensity * brightness;
        }

        if (starRandom > 0.97) {
            vec2 starCenter = vec2(
                fract(sin(starSeed) * 12.9898),
                fract(cos(starSeed) * 78.233)
            );

            vec2 toStar = starLocal - starCenter;
            float dist = length(toStar);

            float sizeRandom = fract(sin(starSeed * 7.123) * 43758.5453);
            float sizeMultiplier = 0.60 + sizeRandom * 0.35;

            float coreSize = 0.0375 * sizeMultiplier;
            float core = smoothstep(coreSize * 2.0, coreSize * 0.3, dist);

            vec2 absDir = abs(toStar);
            float crossDist = min(absDir.x, absDir.y);
            float rayLength = 0.45 * sizeMultiplier;
            float rayFalloff = 2.5;

            float horizontalRay = exp(-pow(absDir.y / (0.06 * sizeMultiplier), rayFalloff)) * smoothstep(rayLength, 0.0, absDir.x);
            float verticalRay = exp(-pow(absDir.x / (0.06 * sizeMultiplier), rayFalloff)) * smoothstep(rayLength, 0.0, absDir.y);

            float rays = max(horizontalRay, verticalRay);

            float twinkleSpeed = 0.55 + random1(starSeed * 0.37) * 1.10;
            float twinklePhase = random1(starSeed * 1.91) * 6.28318;
            float brightness = 0.45 + 0.55 * sin(starTime * twinkleSpeed + twinklePhase);
            brightness = 0.55 + 0.45 * brightness;

            float starIntensity = max(core, rays * 0.7);
            vec3 starColor = vec3(0.95, 0.98, 1.0);
            effects += starColor * starIntensity * brightness * 0.9;
        }
    }

    for (int lane = 0; lane < 2; lane++) {
        float laneOffset = float(lane) * 37.7;
        float laneSize = 2.3 + float(lane) * 1.1;
        vec2 laneUv = starScreenPos / laneSize;
        vec2 laneGrid = floor(laneUv);
        vec2 localPos = fract(laneUv);
        vec2 laneId = laneGrid + vec2(laneOffset, laneOffset * 0.63);

        float laneSpawn = random(laneId);
        if (laneSpawn > 0.78) {
            float segmentDuration = 2.4 + random(laneId + vec2(1.7, 4.9)) * 2.8;
            float segmentTime = starTime / segmentDuration + random(laneId + vec2(8.3, 2.1)) * 9.0;
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
                    vec3 meteorColor = mix(vec3(0.95, 0.98, 1.0), vec3(1.0), 0.35 + 0.65 * trailFade);

                    effects += meteorColor * (meteorIntensity * 1.25 + headGlow * 0.95);
                }
            }
        }
    }

    for (int layer = 0; layer < 3; layer++) {
        float lo = float(layer) * 13.7;
        float fallSpeed = 0.25 + float(layer) * 0.12;
        float petalSize = 0.018 + float(layer) * 0.005;
        float gridSize = 1.2 + float(layer) * 0.4;

        float windPhase = time * 0.08 + lo;
        float wind = sin(windPhase) * 0.4 + sin(windPhase * 2.3) * 0.15;
        vec2 drift = vec2(wind, time * fallSpeed);
        vec2 moving = screenPos + drift;

        vec2 cell = floor(moving / gridSize);
        vec2 local = fract(moving / gridSize);

        float spawn = hash(cell + lo);
        if (spawn > 0.55) {
            vec2 center = vec2(
                0.15 + hash(cell * 1.23 + lo) * 0.7,
                0.15 + hash(cell * 2.34 + lo) * 0.7
            );

            float spin = hash(cell * 3.45 + lo) * 6.28 + time * (0.3 + hash2(cell + lo) * 0.4);
            float sz = petalSize * (0.7 + hash(cell * 4.56 + lo) * 0.6);

            vec4 petalSample = sampleRotatedPetal(Sampler0, local, center, spin, sz);

            float v = hash2(cell * 5.67 + lo);
            vec3 petalColor = mix(
                vec3(1.0, 0.95, 0.98),
                vec3(1.0, 0.8, 0.9),
                v
            );

            float layerAlpha = 0.85 - float(layer) * 0.12;
            effects += petalColor * petalSample.rgb * petalSample.a * layerAlpha;
        }
    }

    vec3 finalColor = bgColor * bgAlpha + effects;
    float finalAlpha = vertexColor.a;

    fragColor = vec4(finalColor, finalAlpha) * ColorModulator;
}
