#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 ScreenSize;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

float hash11(float value) {
    return fract(sin(value * 127.1) * 43758.5453123);
}

float hash12(vec2 value) {
    return fract(sin(dot(value, vec2(127.1, 311.7))) * 43758.5453123);
}

vec2 hash22(vec2 value) {
    float first = dot(value, vec2(127.1, 311.7));
    float second = dot(value, vec2(269.5, 183.3));
    return fract(sin(vec2(first, second)) * 43758.5453123);
}

float windowFade(float phase, float fadeInEnd, float fadeOutStart) {
    return smoothstep(0.0, fadeInEnd, phase)
        * (1.0 - smoothstep(fadeOutStart, 1.0, phase));
}

float dropPath(float baseX, float y, float seed, float amplitude) {
    float broadCurve = sin(y * (5.0 + hash11(seed + 1.0) * 4.0) + seed * 6.28318);
    float fineCurve = sin(y * 17.0 + seed * 11.7) * 0.35;
    return baseX + (broadCurve + fineCurve) * amplitude;
}

float dropBody(vec2 point, vec2 center, float radius, float stretch, float seed) {
    vec2 local = (point - center) / radius;
    local.x += sin(local.y * 2.6 + seed * 9.0) * 0.055;

    // A narrow shoulder and heavy leading edge avoid the synthetic capsule silhouette.
    float shoulder = smoothstep(-0.75, 1.15, local.y);
    float width = mix(1.12, 0.42, shoulder);
    local.x /= width;
    local.y /= stretch;

    float irregularity = 1.0
        + sin(atan(local.y, local.x) * 3.0 + seed * 13.0) * 0.035;
    float distanceToSurface = length(local) / irregularity;
    if (distanceToSurface >= 1.0) {
        return 0.0;
    }

    float dome = sqrt(max(0.0, 1.0 - distanceToSurface * distanceToSurface));
    return dome * radius;
}

float movingDropLayer(vec2 point, float aspect, float time, float columnCount, float layerSeed) {
    float columnWidth = aspect / columnCount;
    float currentColumn = floor(point.x / columnWidth);
    float height = 0.0;

    for (int offset = -1; offset <= 1; offset++) {
        float column = currentColumn + float(offset);
        float columnSeed = hash12(vec2(column, layerSeed));
        float rate = mix(0.055, 0.11, hash11(columnSeed + 2.7));
        float clock = time * rate + columnSeed * 8.0;
        float generation = floor(clock);
        float phase = fract(clock);
        float seed = hash12(vec2(column + generation * 31.7, layerSeed + generation));
        vec2 randomPair = hash22(vec2(seed + layerSeed, generation + column));

        // Missing generations leave natural gaps instead of filling every vertical lane.
        if (hash11(seed * 19.3 + 4.0) > 0.72) {
            continue;
        }

        float delay = mix(0.05, 0.30, hash11(seed + 8.1));
        float motion = clamp((phase - delay) / (1.0 - delay), 0.0, 1.0);
        float stepCount = floor(mix(4.0, 8.0, hash11(seed + 5.4)));
        float stepPosition = motion * stepCount;
        float stepPhase = fract(stepPosition);
        float steppedTravel = (floor(stepPosition) + smoothstep(0.62, 0.98, stepPhase)) / stepCount;
        float travel = mix(pow(motion, 1.18), steppedTravel, 0.62);
        float slip = smoothstep(0.60, 0.78, stepPhase)
            * (1.0 - smoothstep(0.94, 1.0, stepPhase));

        float baseX = (column + mix(0.14, 0.86, randomPair.x)) * columnWidth;
        float pathAmplitude = columnWidth * mix(0.035, 0.16, randomPair.y);
        float centerY = 1.15 - travel * 1.38;
        float centerX = dropPath(baseX, centerY, seed, pathAmplitude);
        float radius = columnWidth * mix(0.075, 0.18, hash11(seed + 12.0));
        radius = clamp(radius, 0.009, 0.042);

        float accumulatedMass = smoothstep(0.0, 0.42, motion);
        float stretch = mix(0.88, 1.35, accumulatedMass) + slip * 1.15;
        float body = dropBody(point, vec2(centerX, centerY), radius, stretch, seed);
        body *= windowFade(phase, delay + 0.04, 0.96);
        height = max(height, body);

        // The wet track follows the curved route and breaks into beads as it thins.
        float trailLength = radius * mix(3.0, 8.0, randomPair.y) * accumulatedMass;
        float trailDistance = point.y - centerY;
        if (trailDistance > 0.0 && trailDistance < trailLength) {
            float trailProgress = trailDistance / max(trailLength, 0.0001);
            float trailX = dropPath(baseX, point.y, seed, pathAmplitude);
            float trailWidth = radius * mix(0.24, 0.08, trailProgress);
            float across = abs(point.x - trailX) / max(trailWidth, 0.0001);
            float film = (1.0 - smoothstep(0.0, 1.0, across))
                * (1.0 - trailProgress) * radius * 0.32;

            float beadCell = floor(point.y / max(radius * 1.4, 0.008));
            float beadNoise = hash12(vec2(beadCell, seed + generation));
            float beadPhase = fract(point.y / max(radius * 1.4, 0.008));
            float bead = 1.0 - smoothstep(0.12, 0.46, abs(beadPhase - 0.5));
            bead *= smoothstep(0.56, 0.86, beadNoise) * radius * 0.22;
            bead *= 1.0 - smoothstep(0.4, 1.0, across);
            height = max(height, max(film, bead));
        }
    }

    return height;
}

float stationaryDropLayer(vec2 point, float time, float gridScale, float layerSeed) {
    vec2 gridPoint = point * gridScale;
    vec2 cellId = floor(gridPoint);
    vec2 localPoint = fract(gridPoint);
    float height = 0.0;

    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 neighbor = vec2(x, y);
            vec2 cell = cellId + neighbor;
            float seed = hash12(cell + layerSeed);
            if (seed > 0.42) {
                continue;
            }

            vec2 randomPair = hash22(cell + vec2(layerSeed, seed));
            vec2 center = neighbor + mix(vec2(0.12), vec2(0.88), randomPair);
            vec2 delta = localPoint - center;
            float radius = mix(0.045, 0.16, hash11(seed + 3.0));
            float life = fract(time * mix(0.018, 0.035, randomPair.x) + seed * 9.0);
            float fade = windowFade(life, 0.12, 0.80);

            delta.x *= mix(0.88, 1.24, randomPair.y);
            delta.y *= mix(0.92, 1.16, randomPair.x);
            float normalizedDistance = length(delta) / radius;
            if (normalizedDistance >= 1.0) {
                continue;
            }

            float dome = sqrt(max(0.0, 1.0 - normalizedDistance * normalizedDistance));
            height = max(height, dome * radius / gridScale * fade * 0.9);
        }
    }

    return height;
}

float mistDropLayer(vec2 point, float gridScale, float layerSeed) {
    vec2 gridPoint = point * gridScale;
    vec2 cellId = floor(gridPoint);
    vec2 localPoint = fract(gridPoint);
    float height = 0.0;

    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 neighbor = vec2(x, y);
            vec2 cell = cellId + neighbor;
            float seed = hash12(cell + layerSeed);
            if (seed > 0.13) {
                continue;
            }

            vec2 center = neighbor + hash22(cell + layerSeed * 2.0);
            float radius = mix(0.025, 0.075, hash11(seed + 7.0));
            float distanceToCenter = length(localPoint - center);
            if (distanceToCenter < radius) {
                height = max(height, (1.0 - distanceToCenter / radius) * radius / gridScale * 0.5);
            }
        }
    }

    return height;
}

float waterHeight(vec2 point, float aspect, float time) {
    float height = 0.0;
    height = max(height, movingDropLayer(point, aspect, time, 10.0, 11.0));
    height = max(height, movingDropLayer(point, aspect, time * 1.13, 17.0, 29.0) * 0.82);
    height = max(height, stationaryDropLayer(point, time, 13.0, 47.0));
    height = max(height, stationaryDropLayer(point, time * 0.81, 22.0, 83.0) * 0.65);
    height = max(height, mistDropLayer(point, 34.0, 101.0));
    return height;
}

void main() {
    float aspect = ScreenSize.x / max(ScreenSize.y, 1.0);
    vec2 point = vec2(texCoord.x * aspect, texCoord.y);
    vec2 pixelStep = vec2(aspect / max(ScreenSize.x, 1.0), 1.0 / max(ScreenSize.y, 1.0)) * 1.5;

    float height = waterHeight(point, aspect, Time);
    float heightX = waterHeight(point + vec2(pixelStep.x, 0.0), aspect, Time);
    float heightY = waterHeight(point + vec2(0.0, pixelStep.y), aspect, Time);
    vec2 gradient = vec2(heightX - height, heightY - height) / max(pixelStep, vec2(0.00001));

    vec2 refraction = vec2(gradient.x / aspect, gradient.y) * 0.0065;
    vec2 refractedUv = clamp(texCoord + refraction, vec2(0.001), vec2(0.999));
    vec3 scene = texture(Sampler1, refractedUv).rgb;

    float waterMask = smoothstep(0.0004, 0.008, height);
    vec3 normal = normalize(vec3(-gradient * 0.32, 1.0));
    vec3 lightDirection = normalize(vec3(-0.45, 0.65, 0.80));
    float rimLight = pow(1.0 - max(normal.z, 0.0), 2.2);
    float softHighlight = pow(max(dot(normal, lightDirection), 0.0), 18.0);

    vec3 result = scene * (1.0 - waterMask * 0.045);
    result += vec3(0.72, 0.82, 0.94) * waterMask * (rimLight * 0.10 + softHighlight * 0.16);
    fragColor = vec4(result, 1.0);
}
