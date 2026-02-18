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

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 5; i++) {
        value += amplitude * noise(p);
        p *= 2.2;
        amplitude *= 0.5;
    }
    return value;
}

float lightning(vec2 uv, vec2 start, float seed, float time) {
    float intensity = 0.0;
    vec2 pos = start;
    float segmentLength = 0.08;

    for (int i = 0; i < 12; i++) {
        float fi = float(i);
        float offset = (hash(seed + fi * 7.13) - 0.5) * 0.15;
        vec2 nextPos = pos + vec2(offset, -segmentLength);

        vec2 dir = nextPos - pos;
        vec2 toPoint = uv - pos;
        float t = clamp(dot(toPoint, dir) / dot(dir, dir), 0.0, 1.0);
        vec2 closest = pos + t * dir;
        float dist = length(uv - closest);

        float glow = 0.012 / (dist + 0.005);
        float core = smoothstep(0.008, 0.0, dist);
        intensity += (glow + core * 3.0) * (1.0 - fi * 0.06);

        if (hash(seed + fi * 3.71) > 0.65 && i > 2 && i < 9) {
            vec2 branchPos = pos;
            float branchDir = (hash(seed + fi * 5.23) - 0.5) * 2.0;
            for (int j = 0; j < 5; j++) {
                float fj = float(j);
                float branchOffset = branchDir * 0.06 + (hash(seed + fi * 11.0 + fj) - 0.5) * 0.08;
                vec2 branchNext = branchPos + vec2(branchOffset, -segmentLength * 0.7);

                vec2 bdir = branchNext - branchPos;
                vec2 btoPoint = uv - branchPos;
                float bt = clamp(dot(btoPoint, bdir) / dot(bdir, bdir), 0.0, 1.0);
                vec2 bclosest = branchPos + bt * bdir;
                float bdist = length(uv - bclosest);

                float bglow = 0.006 / (bdist + 0.008);
                float bcore = smoothstep(0.005, 0.0, bdist);
                intensity += (bglow + bcore * 2.0) * (1.0 - fj * 0.15) * 0.5;

                branchPos = branchNext;
            }
        }

        pos = nextPos;
    }

    return intensity;
}

void main() {
    float time = GameTime * 300.0;

    vec3 bgColor = vec3(0.05, 0.06, 0.08);
    vec3 cloudWhite = vec3(0.7, 0.72, 0.75);
    vec3 lightningColor = vec3(1.0, 0.95, 0.7);

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

    vec2 screenPos;
    vec2 lightningPos;
    if (useUvSpace > 0.5) {
        float dx = abs(dFdx(texCoord0.x));
        float dy = abs(dFdy(texCoord0.y));
        float aspectRatio = (dy > 0.00001) ? (dy / dx) : 1.0;
        vec2 centered = texCoord0 * 2.0 - 1.0;
        centered.x *= aspectRatio;
        screenPos = centered * 3.0;
        lightningPos = centered * vec2(1.5, 1.0);
    } else {
        screenPos = skyPos * 6.0;
        lightningPos = skyPos * 2.0;
    }

    vec2 cloudUv = screenPos * 0.4 + vec2(-time * 0.08, time * 0.03);
    float cloudNoise = fbm(cloudUv);
    float cloudCover = smoothstep(0.15, 0.5, cloudNoise);

    vec3 cloudColor = mix(bgColor, cloudWhite, cloudCover);

    float lightningIntensity = 0.0;
    float flashIntensity = 0.0;

    float strikePeriod = 3.5;
    float strikeTime = mod(time, strikePeriod);
    float strikeIndex = floor(time / strikePeriod);

    float strikeChance = hash(strikeIndex * 17.31);
    if (strikeChance > 0.3) {
        float strikeDuration = 0.25;
        if (strikeTime < strikeDuration) {
            float flickerPhase = strikeTime * 25.0;
            float flicker = step(0.5, fract(flickerPhase)) * step(fract(flickerPhase * 0.7), 0.8);

            float startX = hash(strikeIndex * 23.17) * 1.6 - 0.8;
            vec2 strikeStart = vec2(startX, 0.9);

            float bolt = lightning(lightningPos, strikeStart, strikeIndex * 7.0, time);
            lightningIntensity = bolt * flicker;

            flashIntensity = flicker * 0.15 * (1.0 - strikeTime / strikeDuration);
        }
    }

    float secondPeriod = 5.7;
    float secondTime = mod(time + 2.0, secondPeriod);
    float secondIndex = floor((time + 2.0) / secondPeriod);

    float secondChance = hash(secondIndex * 29.71);
    if (secondChance > 0.5) {
        float secondDuration = 0.2;
        if (secondTime < secondDuration) {
            float flickerPhase = secondTime * 30.0;
            float flicker = step(0.4, fract(flickerPhase));

            float startX = hash(secondIndex * 31.13) * 1.4 - 0.7;
            vec2 strikeStart = vec2(startX, 0.85);

            float bolt = lightning(lightningPos, strikeStart, secondIndex * 11.0 + 100.0, time);
            lightningIntensity += bolt * flicker * 0.7;

            flashIntensity += flicker * 0.1 * (1.0 - secondTime / secondDuration);
        }
    }

    vec3 finalColor = cloudColor;
    finalColor += flashIntensity;
    finalColor += lightningColor * lightningIntensity;

    float finalAlpha = vertexColor.a;

    fragColor = vec4(finalColor, finalAlpha) * ColorModulator;
}
