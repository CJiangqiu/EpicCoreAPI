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

float fbm(vec2 p, int octaves) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 6; i++) {
        if (i >= octaves) break;
        value += amplitude * noise(p);
        p *= 2.0;
        amplitude *= 0.5;
    }
    return value;
}

float supernova(vec2 p, float t) {
    float r = length(p);
    float angle = atan(p.y, p.x);

    float core = exp(-r * r * 50.0) * 2.0;
    float pulse = 0.8 + 0.2 * sin(t * 3.0);
    core *= pulse;

    float rays = 0.0;
    for (int i = 0; i < 12; i++) {
        float rayAngle = float(i) * PI / 6.0;
        float angleDiff = abs(mod(angle - rayAngle + PI, PI * 2.0) - PI);
        float rayWidth = 0.08 + 0.04 * sin(t * 2.0 + float(i));
        float ray = exp(-angleDiff * angleDiff / (rayWidth * rayWidth));
        ray *= exp(-r * (1.5 + 0.5 * sin(t + float(i))));
        ray *= 0.8 + 0.2 * sin(t * 4.0 + float(i) * 0.5);
        rays += ray;
    }

    float halo = exp(-r * 3.0) * 0.5;
    halo *= 1.0 + 0.3 * sin(t * 2.5);

    return core + rays * 0.4 + halo;
}

float energyWave(vec2 p, float t, float waveIndex) {
    float r = length(p);
    float speed = 0.3;
    float waveRadius = mod(t * speed + waveIndex * 0.4, 2.0);
    float thickness = 0.05 + 0.02 * sin(t + waveIndex * 2.0);
    float wave = exp(-pow(r - waveRadius, 2.0) / (thickness * thickness));
    float fade = smoothstep(2.0, 0.5, waveRadius);
    wave *= fade;
    float angle = atan(p.y, p.x);
    float distortion = 1.0 + 0.1 * sin(angle * 8.0 + t * 2.0);
    wave *= distortion;
    return wave;
}

float nebulaDust(vec2 p, float t) {
    float r = length(p);
    vec2 distorted = p + vec2(
        fbm(p * 2.0 + t * 0.1, 4) * 0.3,
        fbm(p * 2.0 - t * 0.1 + 10.0, 4) * 0.3
    );
    float dust = fbm(distorted * 1.5, 5);
    dust = smoothstep(0.3, 0.7, dust);
    float radialFade = smoothstep(0.2, 1.5, r) * smoothstep(2.5, 1.0, r);
    dust *= radialFade;
    float angle = atan(p.y, p.x);
    float swirl = sin(angle * 3.0 + r * 2.0 - t * 0.2) * 0.5 + 0.5;
    dust *= 0.7 + 0.3 * swirl;
    return dust;
}

// Spiral galaxy
float spiralGalaxy(vec2 p, float t, float seed, out float armCount) {
    // Rotation animation
    float rotSpeed = 0.03 + hash(vec2(seed, 2.0)) * 0.02;
    float rotAngle = t * rotSpeed;
    float c = cos(rotAngle);
    float s = sin(rotAngle);
    p = vec2(c * p.x - s * p.y, s * p.x + c * p.y);

    float r = length(p);
    float angle = atan(p.y, p.x);

    // Spiral arms 3-6
    armCount = 3.0 + mod(floor(seed), 4.0);
    float twist = 2.5 + hash(vec2(seed, 1.0)) * 1.5;
    float spiralAngle = angle + r * twist;
    float spiral = sin(spiralAngle * armCount) * 0.5 + 0.5;
    spiral = pow(spiral, 1.5);

    // Bright core
    float core = exp(-r * r * 15.0) * 1.5;

    // Disk fade
    float disk = exp(-r * 1.8);

    return core + spiral * disk * 0.8;
}

// Elliptical galaxy
float ellipticalGalaxy(vec2 p, float seed) {
    float scaleX = 0.6 + hash(vec2(seed, 3.0)) * 0.4;
    float scaleY = 0.4 + hash(vec2(seed, 4.0)) * 0.3;
    float rot = hash(vec2(seed, 5.0)) * 6.28;

    float c = cos(rot);
    float s = sin(rot);
    vec2 rp = vec2(c * p.x - s * p.y, s * p.x + c * p.y);
    rp.x /= scaleX;
    rp.y /= scaleY;

    float r = length(rp);
    return exp(-r * r * 5.0) * 1.2;
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
        // GUI/Entity mode: scale proportionally to fit entire effect
        float dx = abs(dFdx(texCoord0.x));
        float dy = abs(dFdy(texCoord0.y));
        float aspectRatio = (dy > 0.00001) ? (dy / dx) : 1.0;

        vec2 centered = texCoord0 * 2.0 - 1.0;
        centered.x *= aspectRatio;
        uv = centered * 4.0;
    } else {
        // Sky mode: use projected skyDir
        uv = skyPos * 8.0;
    }

    float t = GameTime * 800.0;

    vec3 bgDeep = vec3(0.01, 0.005, 0.02);
    vec3 bgMid = vec3(0.02, 0.01, 0.04);
    float bgGrad = smoothstep(-2.0, 2.0, uv.y);
    vec3 result = mix(bgDeep, bgMid, bgGrad);

    float dust = nebulaDust(uv * 0.5, t);
    vec3 dustColorInner = vec3(0.8, 0.4, 0.2);
    vec3 dustColorOuter = vec3(0.3, 0.2, 0.5);
    float r = length(uv);
    vec3 dustColor = mix(dustColorInner, dustColorOuter, smoothstep(0.5, 2.0, r));
    result += dustColor * dust * 0.4;

    float sn = supernova(uv * 0.8, t);
    vec3 snColorCore = vec3(1.0, 0.95, 0.9);
    vec3 snColorMid = vec3(1.0, 0.7, 0.4);
    vec3 snColorOuter = vec3(0.8, 0.3, 0.5);

    vec3 snColor;
    if (sn > 0.8) {
        snColor = mix(snColorMid, snColorCore, (sn - 0.8) / 0.2);
    } else if (sn > 0.3) {
        snColor = mix(snColorOuter, snColorMid, (sn - 0.3) / 0.5);
    } else {
        snColor = snColorOuter;
    }
    result += snColor * sn;

    vec3 waveColor = vec3(0.4, 0.6, 1.0);
    for (int i = 0; i < 4; i++) {
        float wave = energyWave(uv * 0.6, t, float(i));
        float hue = float(i) * 0.15;
        vec3 wc = mix(vec3(0.3, 0.5, 1.0), vec3(0.8, 0.4, 1.0), hue);
        result += wc * wave * 0.6;
    }

    // Random galaxies
    float galaxyCellSize = 4.0;
    vec2 galaxyCellId = floor(uv / galaxyCellSize);

    for (int gx = -1; gx <= 1; gx++) {
        for (int gy = -1; gy <= 1; gy++) {
            vec2 neighborGalaxy = galaxyCellId + vec2(float(gx), float(gy));
            float galaxySeed = hash(neighborGalaxy * 73.1);

            if (galaxySeed > 0.35) {
                vec2 galaxyOffset = vec2(hash(neighborGalaxy + 0.5), hash(neighborGalaxy + 0.6));
                vec2 galaxyCenter = (neighborGalaxy + galaxyOffset) * galaxyCellSize;

                vec2 toGalaxy = uv - galaxyCenter;
                float galaxy;
                vec3 galaxyColor;

                // Random color temperature like stars
                float colorTemp = hash(neighborGalaxy + 2.0);
                vec3 baseColor = getStarColor(colorTemp);

                // 60% spiral, 40% elliptical
                if (hash(neighborGalaxy + 0.8) > 0.4) {
                    float spiralSize = 0.4 + hash(neighborGalaxy + 0.7) * 0.3;
                    vec2 spiralUv = toGalaxy / spiralSize;
                    float armCount;
                    galaxy = spiralGalaxy(spiralUv, t, galaxySeed * 100.0, armCount);
                    galaxyColor = baseColor;
                } else {
                    float ellipseSize = 1.6 + hash(neighborGalaxy + 0.7) * 1.2;
                    vec2 ellipseUv = toGalaxy / ellipseSize;
                    galaxy = ellipticalGalaxy(ellipseUv, galaxySeed * 100.0);
                    galaxyColor = baseColor;
                }

                result += galaxyColor * galaxy * 0.7;
            }
        }
    }

    // Stars using tiled grid to cover entire area
    float starCellSize = 3.0;
    vec2 starCellId = floor(uv / starCellSize);
    vec2 starCellUv = fract(uv / starCellSize);

    for (int ox = -1; ox <= 1; ox++) {
        for (int oy = -1; oy <= 1; oy++) {
            vec2 neighborCell = starCellId + vec2(float(ox), float(oy));
            float starSeed = hash(neighborCell * 127.1);

            if (starSeed > 0.4) {
                vec2 starPos = vec2(hash(neighborCell + 0.1), hash(neighborCell + 0.2));
                vec2 worldStarPos = (neighborCell + starPos) * starCellSize;

                float dist = length(uv - worldStarPos);
                float starSize = 0.04 + hash(neighborCell + 0.3) * 0.06;
                float temp = hash(neighborCell + 0.4);
                float twinkle = 0.7 + 0.3 * sin(t * (2.0 + starSeed * 3.0) + starSeed * 10.0);

                float star = exp(-dist * dist / (starSize * starSize)) * twinkle;
                float glow = exp(-dist / (starSize * 3.0)) * 0.2 * twinkle;

                vec3 starColor = getStarColor(temp);
                result += starColor * (star + glow);
            }
        }
    }

    float centralGlow = exp(-length(uv) * 0.5) * 0.2;
    centralGlow *= 1.0 + 0.2 * sin(t * 1.5);
    result += vec3(1.0, 0.8, 0.6) * centralGlow;

    result = pow(result, vec3(0.95));

    float alpha = 0.92;

    fragColor = vec4(result, alpha * vertexColor.a) * ColorModulator;
}
