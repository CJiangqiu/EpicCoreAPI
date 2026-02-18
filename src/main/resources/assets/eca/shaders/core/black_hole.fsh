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

vec3 getStarColor(float temp) {
    if (temp < 0.3) {
        return mix(vec3(1.0, 0.6, 0.3), vec3(1.0, 0.8, 0.5), temp / 0.3);
    } else if (temp < 0.6) {
        return mix(vec3(1.0, 0.9, 0.8), vec3(0.9, 0.95, 1.0), (temp - 0.3) / 0.3);
    } else {
        return mix(vec3(0.7, 0.85, 1.0), vec3(0.5, 0.7, 1.0), (temp - 0.6) / 0.4);
    }
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

    vec2 relativeUv;
    if (useUvSpace > 0.5) {
        // GUI mode - centered with aspect ratio correction
        float dx = abs(dFdx(texCoord0.x));
        float dy = abs(dFdy(texCoord0.y));
        float aspectRatio = (dy > 0.00001) ? (dy / dx) : 1.0;
        vec2 centered = texCoord0 * 2.0 - 1.0;
        centered.x *= aspectRatio;
        relativeUv = centered * 2.3;
    } else {
        // Sky mode - rotate view direction downward so black hole appears lower
        // This avoids 2D projection distortion by offsetting in 3D
        float bhPitch = 0.75;
        float sbh = sin(bhPitch);
        float cbh = cos(bhPitch);
        vec3 bhDir = vec3(dir.x, dir.y * cbh + dir.z * sbh, -dir.y * sbh + dir.z * cbh);

        vec2 bhSkyPos = bhDir.xz / max(0.15, 1.0 + bhDir.y);
        relativeUv = bhSkyPos * 4.6;
    }
    float distToCenter = length(relativeUv);

    // Black background
    vec3 result = vec3(0.01, 0.005, 0.02);

    // === Stars being pulled toward black hole ===
    float starCellSize = 2.0;
    vec2 starCellId = floor(relativeUv / starCellSize);

    for (int ox = -2; ox <= 2; ox++) {
        for (int oy = -2; oy <= 2; oy++) {
            vec2 neighborCell = starCellId + vec2(float(ox), float(oy));
            float starSeed = hash(neighborCell * 127.1);

            if (starSeed > 0.5) {
                // Star's base position
                vec2 starPos = vec2(hash(neighborCell + 0.1), hash(neighborCell + 0.2));
                vec2 worldStarPos = (neighborCell + starPos) * starCellSize;

                // Pull toward black hole center over time
                vec2 toCenter = -normalize(worldStarPos + vec2(0.0001));
                float starDistToHole = length(worldStarPos);
                float pullSpeed = 1.5 / (starDistToHole + 0.3);
                float pullPhase = fract(time * pullSpeed * 0.04 + hash(neighborCell + 0.5));

                // Star accelerates inward (quadratic easing)
                float easedPhase = pullPhase * pullPhase;
                vec2 pulledPos = worldStarPos + toCenter * easedPhase * starDistToHole * 0.9;

                float currentDistToHole = length(pulledPos);

                // Stretch only when very close to black hole
                vec2 toPulledCenter = normalize(pulledPos + vec2(0.0001));
                float stretchRatio = smoothstep(1.5, 0.4, currentDistToHole);

                vec2 delta = relativeUv - pulledPos;
                float alongDir = dot(delta, toPulledCenter);
                float perpDir = length(delta - toPulledCenter * alongDir);

                float starSize = 0.04 + hash(neighborCell + 0.3) * 0.04;
                float stretchedSize = starSize * (1.0 + stretchRatio * 10.0);
                float squeezedSize = starSize * (1.0 - stretchRatio * 0.5);

                float star = exp(-alongDir * alongDir / (stretchedSize * stretchedSize))
                           * exp(-perpDir * perpDir / (squeezedSize * squeezedSize));

                float temp = hash(neighborCell + 0.4);
                float twinkle = 0.7 + 0.3 * sin(time * (1.5 + starSeed * 2.0) + starSeed * 10.0);

                // Hide inside black hole
                float hideInHole = smoothstep(0.3, 0.7, currentDistToHole);

                vec3 starColor = getStarColor(temp);
                result += starColor * star * twinkle * hideInHole;
            }
        }
    }

    // === Accretion disk ===
    // Clockwise fast rotation (negative = clockwise)
    float diskRotation = -time * 0.3;

    // Elliptical disk (tilted view)
    float yScale = 4.0;
    float diskDist = sqrt(relativeUv.x * relativeUv.x + relativeUv.y * relativeUv.y * yScale * yScale);

    float diskInner = 0.5;
    float diskOuter = 2.0;
    float diskMask = smoothstep(diskInner, diskInner + 0.1, diskDist)
                   * smoothstep(diskOuter, diskOuter - 0.2, diskDist);

    // Fast rotating spiral flow pattern
    vec2 rotatedUv = vec2(
        relativeUv.x * cos(diskRotation) - relativeUv.y * sin(diskRotation),
        relativeUv.x * sin(diskRotation) + relativeUv.y * cos(diskRotation)
    );

    // Spiral streaks: angle-dependent pattern that creates flowing appearance
    float spiralAngle = atan(rotatedUv.y, rotatedUv.x);
    float spiralFlow = sin(spiralAngle * 6.0 + diskDist * 5.0) * 0.5 + 0.5;
    float diskNoise = fbm(rotatedUv * 3.0);
    float diskPattern = mix(spiralFlow, diskNoise, 0.3);

    // Doppler effect
    float dopplerShift = 0.4 + 0.6 * smoothstep(-diskOuter, diskOuter, rotatedUv.x);

    vec3 diskColorCool = vec3(0.4, 0.15, 0.7);
    vec3 diskColorHot = vec3(0.95, 0.4, 1.0);

    vec3 diskColor = mix(diskColorCool, diskColorHot, dopplerShift);

    float diskBrightness = diskMask * diskPattern * dopplerShift;
    result += diskColor * diskBrightness * 1.2;

    // === Photon sphere (bright ring at event horizon) ===
    float photonRadius = 0.5;
    float photonWidth = 0.06;
    float photonRing = exp(-pow(distToCenter - photonRadius, 2.0) / (photonWidth * photonWidth));

    float photonFlicker = 0.85 + 0.15 * sin(time * 2.0);
    vec3 photonColor = vec3(0.7, 0.5, 1.0);
    result += photonColor * photonRing * photonFlicker * 1.8;

    // === Event horizon glow ===
    float eventHorizonRadius = 0.45;
    float glowWidth = 0.15;
    float eventGlow = exp(-pow(distToCenter - eventHorizonRadius, 2.0) / (glowWidth * glowWidth));

    float glowPulse = 0.85 + 0.15 * sin(time * 1.5);
    vec3 eventColor = vec3(0.6, 0.3, 1.0);
    result += eventColor * eventGlow * glowPulse * 2.0;

    // === Black hole core (pure black) ===
    float coreRadius = 0.35;
    float coreFade = smoothstep(coreRadius + 0.1, coreRadius - 0.05, distToCenter);
    result = mix(result, vec3(0.0), coreFade);

    // === Outer purple haze ===
    float hazeRadius = 2.5;
    float haze = exp(-distToCenter / hazeRadius) * 0.3;
    vec3 hazeColor = vec3(0.3, 0.1, 0.5);
    result += hazeColor * haze;

    float finalAlpha = vertexColor.a;

    fragColor = vec4(result, finalAlpha) * ColorModulator;
}
