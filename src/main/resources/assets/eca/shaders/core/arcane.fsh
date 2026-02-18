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
#define TAU 6.28318530718

float random(vec2 st) {
    return fract(sin(dot(st, vec2(12.9898, 78.233))) * 43758.5453123);
}

mat2 rotate2D(float angle) {
    float s = sin(angle);
    float c = cos(angle);
    return mat2(c, -s, s, c);
}

float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

float sdCircle(vec2 p, float r) {
    return length(p) - r;
}

float sdArc(vec2 p, float r, float a1, float a2) {
    float a = atan(p.y, p.x);
    if (a < a1) a += TAU;
    if (a > a2) a -= TAU;
    if (a >= a1 && a <= a2) {
        return abs(length(p) - r);
    }
    vec2 p1 = r * vec2(cos(a1), sin(a1));
    vec2 p2 = r * vec2(cos(a2), sin(a2));
    return min(length(p - p1), length(p - p2));
}

// ========== 24 Elder Futhark Runes ==========
float runeFehu(vec2 p) { // ᚠ
    float d = sdSegment(p, vec2(0, -0.5), vec2(0, 0.5));
    d = min(d, sdSegment(p, vec2(0, 0.5), vec2(0.3, 0.25)));
    d = min(d, sdSegment(p, vec2(0, 0.15), vec2(0.25, -0.05)));
    return d;
}

float runeUruz(vec2 p) { // ᚢ
    float d = sdSegment(p, vec2(-0.15, 0.5), vec2(-0.15, -0.3));
    d = min(d, sdSegment(p, vec2(-0.15, -0.3), vec2(0.15, -0.5)));
    d = min(d, sdSegment(p, vec2(0.15, -0.5), vec2(0.15, 0.5)));
    return d;
}

float runeThurisaz(vec2 p) { // ᚦ
    float d = sdSegment(p, vec2(0, -0.5), vec2(0, 0.5));
    d = min(d, sdSegment(p, vec2(0, 0.3), vec2(0.3, 0.0)));
    d = min(d, sdSegment(p, vec2(0.3, 0.0), vec2(0, -0.15)));
    return d;
}

float runeAnsuz(vec2 p) { // ᚨ
    float d = sdSegment(p, vec2(0, -0.5), vec2(0, 0.5));
    d = min(d, sdSegment(p, vec2(0, 0.3), vec2(0.3, 0.0)));
    d = min(d, sdSegment(p, vec2(0, 0.0), vec2(0.3, -0.3)));
    return d;
}

float runeRaidho(vec2 p) { // ᚱ
    float d = sdSegment(p, vec2(0, -0.5), vec2(0, 0.5));
    d = min(d, sdSegment(p, vec2(0, 0.5), vec2(0.25, 0.25)));
    d = min(d, sdSegment(p, vec2(0.25, 0.25), vec2(0, 0.05)));
    d = min(d, sdSegment(p, vec2(0, 0.05), vec2(0.3, -0.5)));
    return d;
}

float runeKenaz(vec2 p) { // ᚲ
    float d = sdSegment(p, vec2(0.2, 0.5), vec2(-0.1, 0.0));
    d = min(d, sdSegment(p, vec2(-0.1, 0.0), vec2(0.2, -0.5)));
    return d;
}

float runeGebo(vec2 p) { // ᚷ
    float d = sdSegment(p, vec2(-0.3, -0.4), vec2(0.3, 0.4));
    d = min(d, sdSegment(p, vec2(-0.3, 0.4), vec2(0.3, -0.4)));
    return d;
}

float runeWunjo(vec2 p) { // ᚹ
    float d = sdSegment(p, vec2(0, -0.5), vec2(0, 0.5));
    d = min(d, sdSegment(p, vec2(0, 0.5), vec2(0.25, 0.25)));
    d = min(d, sdSegment(p, vec2(0.25, 0.25), vec2(0, 0.1)));
    return d;
}

float runeHagalaz(vec2 p) { // ᚺ
    float d = sdSegment(p, vec2(-0.15, -0.5), vec2(-0.15, 0.5));
    d = min(d, sdSegment(p, vec2(0.15, -0.5), vec2(0.15, 0.5)));
    d = min(d, sdSegment(p, vec2(-0.15, 0.1), vec2(0.15, -0.1)));
    return d;
}

float runeNauthiz(vec2 p) { // ᚾ
    float d = sdSegment(p, vec2(0, -0.5), vec2(0, 0.5));
    d = min(d, sdSegment(p, vec2(-0.2, 0.2), vec2(0.2, -0.2)));
    return d;
}

float runeIsa(vec2 p) { // ᛁ
    return sdSegment(p, vec2(0, -0.5), vec2(0, 0.5));
}

float runeJera(vec2 p) { // ᛃ
    float d = sdSegment(p, vec2(-0.05, 0.5), vec2(0.2, 0.15));
    d = min(d, sdSegment(p, vec2(0.2, 0.15), vec2(-0.05, 0.0)));
    d = min(d, sdSegment(p, vec2(0.05, 0.0), vec2(-0.2, -0.15)));
    d = min(d, sdSegment(p, vec2(-0.2, -0.15), vec2(0.05, -0.5)));
    return d;
}

float runeEihwaz(vec2 p) { // ᛇ
    float d = sdSegment(p, vec2(0, -0.5), vec2(0, 0.5));
    d = min(d, sdSegment(p, vec2(0, 0.2), vec2(0.25, 0.45)));
    d = min(d, sdSegment(p, vec2(0, -0.2), vec2(-0.25, -0.45)));
    return d;
}

float runePerthro(vec2 p) { // ᛈ
    float d = sdSegment(p, vec2(-0.1, -0.5), vec2(-0.1, 0.5));
    d = min(d, sdSegment(p, vec2(-0.1, 0.5), vec2(0.2, 0.2)));
    d = min(d, sdSegment(p, vec2(0.2, 0.2), vec2(0.2, -0.2)));
    d = min(d, sdSegment(p, vec2(0.2, -0.2), vec2(-0.1, -0.5)));
    return d;
}

float runeAlgiz(vec2 p) { // ᛉ
    float d = sdSegment(p, vec2(0, -0.5), vec2(0, 0.3));
    d = min(d, sdSegment(p, vec2(0, 0.3), vec2(0.25, 0.5)));
    d = min(d, sdSegment(p, vec2(0, 0.3), vec2(-0.25, 0.5)));
    return d;
}

float runeSowilo(vec2 p) { // ᛊ
    float d = sdSegment(p, vec2(-0.15, 0.5), vec2(0.15, 0.15));
    d = min(d, sdSegment(p, vec2(0.15, 0.15), vec2(-0.15, -0.15)));
    d = min(d, sdSegment(p, vec2(-0.15, -0.15), vec2(0.15, -0.5)));
    return d;
}

float runeTiwaz(vec2 p) { // ᛏ
    float d = sdSegment(p, vec2(0, -0.5), vec2(0, 0.5));
    d = min(d, sdSegment(p, vec2(-0.25, 0.25), vec2(0, 0.5)));
    d = min(d, sdSegment(p, vec2(0, 0.5), vec2(0.25, 0.25)));
    return d;
}

float runeBerkano(vec2 p) { // ᛒ
    float d = sdSegment(p, vec2(0, -0.5), vec2(0, 0.5));
    d = min(d, sdSegment(p, vec2(0, 0.5), vec2(0.25, 0.25)));
    d = min(d, sdSegment(p, vec2(0.25, 0.25), vec2(0, 0.0)));
    d = min(d, sdSegment(p, vec2(0, 0.0), vec2(0.25, -0.25)));
    d = min(d, sdSegment(p, vec2(0.25, -0.25), vec2(0, -0.5)));
    return d;
}

float runeEhwaz(vec2 p) { // ᛖ
    float d = sdSegment(p, vec2(-0.15, -0.5), vec2(-0.15, 0.5));
    d = min(d, sdSegment(p, vec2(-0.15, 0.5), vec2(0.15, 0.0)));
    d = min(d, sdSegment(p, vec2(0.15, 0.0), vec2(-0.15, -0.5)));
    return d;
}

float runeMannaz(vec2 p) { // ᛗ
    float d = sdSegment(p, vec2(-0.15, -0.5), vec2(-0.15, 0.5));
    d = min(d, sdSegment(p, vec2(0.15, -0.5), vec2(0.15, 0.5)));
    d = min(d, sdSegment(p, vec2(-0.15, 0.5), vec2(0.0, 0.2)));
    d = min(d, sdSegment(p, vec2(0.0, 0.2), vec2(0.15, 0.5)));
    d = min(d, sdSegment(p, vec2(-0.15, 0.0), vec2(0.15, 0.0)));
    return d;
}

float runeLaguz(vec2 p) { // ᛚ
    float d = sdSegment(p, vec2(0, -0.5), vec2(0, 0.5));
    d = min(d, sdSegment(p, vec2(0, 0.5), vec2(0.25, 0.2)));
    return d;
}

float runeIngwaz(vec2 p) { // ᛜ
    float d = sdSegment(p, vec2(0, 0.4), vec2(0.25, 0.0));
    d = min(d, sdSegment(p, vec2(0.25, 0.0), vec2(0, -0.4)));
    d = min(d, sdSegment(p, vec2(0, -0.4), vec2(-0.25, 0.0)));
    d = min(d, sdSegment(p, vec2(-0.25, 0.0), vec2(0, 0.4)));
    return d;
}

float runeDagaz(vec2 p) { // ᛞ
    float d = sdSegment(p, vec2(-0.2, 0.4), vec2(0.2, 0.4));
    d = min(d, sdSegment(p, vec2(-0.2, -0.4), vec2(0.2, -0.4)));
    d = min(d, sdSegment(p, vec2(-0.2, 0.4), vec2(0.2, -0.4)));
    d = min(d, sdSegment(p, vec2(0.2, 0.4), vec2(-0.2, -0.4)));
    return d;
}

float runeOthala(vec2 p) { // ᛟ
    float d = sdSegment(p, vec2(-0.15, -0.5), vec2(-0.15, 0.0));
    d = min(d, sdSegment(p, vec2(0.15, -0.5), vec2(0.15, 0.0)));
    d = min(d, sdSegment(p, vec2(-0.15, 0.0), vec2(0, 0.25)));
    d = min(d, sdSegment(p, vec2(0.15, 0.0), vec2(0, 0.25)));
    d = min(d, sdSegment(p, vec2(0, 0.25), vec2(0, 0.5)));
    return d;
}

float drawRune(vec2 p, int index) {
    p *= 2.5;
    if (index == 0) return runeFehu(p);
    if (index == 1) return runeUruz(p);
    if (index == 2) return runeThurisaz(p);
    if (index == 3) return runeAnsuz(p);
    if (index == 4) return runeRaidho(p);
    if (index == 5) return runeKenaz(p);
    if (index == 6) return runeGebo(p);
    if (index == 7) return runeWunjo(p);
    if (index == 8) return runeHagalaz(p);
    if (index == 9) return runeNauthiz(p);
    if (index == 10) return runeIsa(p);
    if (index == 11) return runeJera(p);
    if (index == 12) return runeEihwaz(p);
    if (index == 13) return runePerthro(p);
    if (index == 14) return runeAlgiz(p);
    if (index == 15) return runeSowilo(p);
    if (index == 16) return runeTiwaz(p);
    if (index == 17) return runeBerkano(p);
    if (index == 18) return runeEhwaz(p);
    if (index == 19) return runeMannaz(p);
    if (index == 20) return runeLaguz(p);
    if (index == 21) return runeIngwaz(p);
    if (index == 22) return runeDagaz(p);
    return runeOthala(p);
}

// ========== 8 Planet Symbols ==========
float symbolSun(vec2 p) { // ☉
    float d = abs(sdCircle(p, 0.35)) - 0.02;
    d = min(d, sdCircle(p, 0.08));
    return d;
}

float symbolMoon(vec2 p) { // ☽
    float d = abs(sdCircle(p, 0.3)) - 0.02;
    float cut = sdCircle(p - vec2(0.15, 0.0), 0.25);
    d = max(d, -cut);
    return d;
}

float symbolMercury(vec2 p) { // ☿
    float d = abs(sdCircle(p - vec2(0, 0.05), 0.2)) - 0.02;
    d = min(d, sdSegment(p, vec2(0, -0.15), vec2(0, -0.45)));
    d = min(d, sdSegment(p, vec2(-0.15, -0.3), vec2(0.15, -0.3)));
    d = min(d, sdArc(p - vec2(0, 0.25), 0.15, 0.3, PI - 0.3));
    return d;
}

float symbolVenus(vec2 p) { // ♀
    float d = abs(sdCircle(p - vec2(0, 0.15), 0.22)) - 0.02;
    d = min(d, sdSegment(p, vec2(0, -0.07), vec2(0, -0.45)));
    d = min(d, sdSegment(p, vec2(-0.15, -0.25), vec2(0.15, -0.25)));
    return d;
}

float symbolMars(vec2 p) { // ♂
    float d = abs(sdCircle(p - vec2(-0.08, -0.08), 0.22)) - 0.02;
    d = min(d, sdSegment(p, vec2(0.08, 0.08), vec2(0.35, 0.35)));
    d = min(d, sdSegment(p, vec2(0.35, 0.35), vec2(0.35, 0.15)));
    d = min(d, sdSegment(p, vec2(0.35, 0.35), vec2(0.15, 0.35)));
    return d;
}

float symbolJupiter(vec2 p) { // ♃
    float d = sdSegment(p, vec2(-0.3, 0.0), vec2(0.3, 0.0));
    d = min(d, sdSegment(p, vec2(0.15, 0.4), vec2(0.15, -0.4)));
    d = min(d, sdArc(p - vec2(-0.1, 0.2), 0.2, -PI * 0.5, PI * 0.5));
    return d;
}

float symbolSaturn(vec2 p) { // ♄
    float d = sdSegment(p, vec2(-0.1, 0.45), vec2(0.15, 0.45));
    d = min(d, sdSegment(p, vec2(0.0, 0.45), vec2(0.0, -0.1)));
    d = min(d, sdArc(p - vec2(0.15, -0.1), 0.15, -PI * 0.5, PI * 0.5));
    d = min(d, sdSegment(p, vec2(0.15, -0.25), vec2(-0.1, -0.45)));
    return d;
}

float symbolUranus(vec2 p) { // ♅
    float d = abs(sdCircle(p - vec2(0, -0.25), 0.12)) - 0.02;
    d = min(d, sdSegment(p, vec2(0, -0.13), vec2(0, 0.35)));
    d = min(d, sdSegment(p, vec2(-0.2, 0.35), vec2(0.2, 0.35)));
    d = min(d, sdSegment(p, vec2(-0.2, 0.35), vec2(-0.2, 0.2)));
    d = min(d, sdSegment(p, vec2(0.2, 0.35), vec2(0.2, 0.2)));
    d = min(d, sdCircle(p - vec2(0, 0.45), 0.05));
    return d;
}

float drawPlanet(vec2 p, int index) {
    p *= 2.0;
    if (index == 0) return symbolSun(p);
    if (index == 1) return symbolMoon(p);
    if (index == 2) return symbolMercury(p);
    if (index == 3) return symbolVenus(p);
    if (index == 4) return symbolMars(p);
    if (index == 5) return symbolJupiter(p);
    if (index == 6) return symbolSaturn(p);
    return symbolUranus(p);
}

// ========== Magic Circle Layers ==========
float circleRing(vec2 p, float radius, float thickness) {
    return abs(length(p) - radius) - thickness;
}

float magicCircleLayer(vec2 p, float radius, float t, int layerIndex, float rotSpeed) {
    float d = 1.0;
    vec2 rp = p * rotate2D(t * rotSpeed);

    d = min(d, circleRing(rp, radius, 0.004));
    d = min(d, circleRing(rp, radius * 0.88, 0.002));

    int runeStartIndex = layerIndex * 4;
    float segmentAngle = TAU / 4.0;

    for (int i = 0; i < 4; i++) {
        float angle = float(i) * segmentAngle + segmentAngle * 0.5;
        vec2 runePos = vec2(cos(angle), sin(angle)) * radius * 0.94;

        int runeIndex = runeStartIndex + i;
        float runeSize = radius * 0.12;
        float runeDist = drawRune((rp - runePos) / runeSize, runeIndex) * runeSize;
        d = min(d, runeDist);

        float decorAngle1 = angle - 0.3;
        float decorAngle2 = angle + 0.3;
        vec2 decor1 = vec2(cos(decorAngle1), sin(decorAngle1)) * radius * 0.94;
        vec2 decor2 = vec2(cos(decorAngle2), sin(decorAngle2)) * radius * 0.94;
        d = min(d, sdSegment(rp, decor1, decor2) - 0.001);
    }

    if (layerIndex % 2 == 0) {
        for (int i = 0; i < 8; i++) {
            float angle = float(i) * TAU / 8.0;
            vec2 dotPos = vec2(cos(angle), sin(angle)) * radius * 0.91;
            d = min(d, sdCircle(rp - dotPos, radius * 0.015));
        }
    } else {
        for (int i = 0; i < 4; i++) {
            float angle = float(i) * TAU / 4.0;
            vec2 p1 = vec2(cos(angle), sin(angle)) * radius * 0.88;
            vec2 p2 = vec2(cos(angle + TAU / 4.0), sin(angle + TAU / 4.0)) * radius * 0.88;
            d = min(d, sdSegment(rp, p1, p2) - 0.001);
        }
    }

    return d;
}

float smallMagicCircle(vec2 center, vec2 p, float radius, float t, int planetIndex) {
    vec2 lp = p - center;
    float d = 1.0;

    vec2 rp = lp * rotate2D(t * 1.5);

    d = min(d, circleRing(rp, radius, 0.003));
    d = min(d, circleRing(rp, radius * 0.6, 0.002));

    float symbolSize = radius * 0.5;
    float symbolDist = drawPlanet(rp / symbolSize, planetIndex) * symbolSize;
    d = min(d, symbolDist);

    for (int i = 0; i < 8; i++) {
        float angle = float(i) * TAU / 8.0;
        vec2 dotPos = vec2(cos(angle), sin(angle)) * radius * 0.8;
        d = min(d, sdCircle(rp - dotPos, radius * 0.04));
    }

    return d;
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

    vec2 screenPos;
    if (useUvSpace > 0.5) {
        // GUI/Entity mode: scale proportionally to fit entire effect
        float dx = abs(dFdx(texCoord0.x));
        float dy = abs(dFdy(texCoord0.y));
        float aspectRatio = (dy > 0.00001) ? (dy / dx) : 1.0;

        vec2 centered = texCoord0 * 2.0 - 1.0;
        centered.x *= aspectRatio;
        screenPos = centered * 1.5;
    } else {
        // Sky mode: use projected skyDir
        screenPos = skyPos * 1.5;
    }

    vec3 backgroundColor = vec3(0.0);
    float t = GameTime * 800.0;

    vec3 coreColor = vec3(1.0, 0.15, 0.2);
    vec3 midColor = vec3(0.85, 0.1, 0.5);
    vec3 outerColor = vec3(0.6, 0.15, 0.9);
    vec3 smallCircleInner = vec3(0.5, 0.2, 0.95);
    vec3 smallCircleOuter = vec3(0.25, 0.35, 1.0);

    float totalGlow = 0.0;

    float layerRadii[6] = float[](0.85, 0.68, 0.54, 0.42, 0.32, 0.22);
    float layerSpeeds[6] = float[](0.2, -0.35, 0.5, -0.3, 0.45, -0.6);

    for (int i = 0; i < 6; i++) {
        float layerDist = magicCircleLayer(screenPos, layerRadii[i], t, i, layerSpeeds[i]);
        float layerGlow = smoothstep(0.012, 0.0, layerDist);

        float pulse = 0.7 + 0.3 * sin(t * 0.5 + float(i) * 0.8);
        layerGlow *= pulse;

        float colorMix = float(i) / 5.0;
        vec3 layerColor;
        if (colorMix < 0.5) {
            layerColor = mix(outerColor, midColor, colorMix * 2.0);
        } else {
            layerColor = mix(midColor, coreColor, (colorMix - 0.5) * 2.0);
        }

        backgroundColor += layerColor * layerGlow * (0.7 + 0.3 * (1.0 - colorMix));
        totalGlow += layerGlow;
    }

    for (int i = 0; i < 8; i++) {
        float angle = float(i) * TAU / 8.0 + t * 0.1;
        float orbitRadius = 1.15 + 0.08 * sin(t * 0.3 + float(i));
        vec2 circleCenter = vec2(cos(angle), sin(angle)) * orbitRadius;

        float circleRadius = 0.1 + 0.02 * sin(t * 0.5 + float(i) * 1.2);
        float smallDist = smallMagicCircle(circleCenter, screenPos, circleRadius, t + float(i), i);
        float smallGlow = smoothstep(0.008, 0.0, smallDist);

        float pulse = 0.6 + 0.4 * sin(t * 0.6 + float(i) * 0.7);
        smallGlow *= pulse;

        float colorMix = float(i) / 7.0;
        vec3 smallColor = mix(smallCircleInner, smallCircleOuter, colorMix);
        backgroundColor += smallColor * smallGlow * 0.6;
        totalGlow += smallGlow * 0.3;
    }

    float centerDist = length(screenPos);
    float centerGlow = smoothstep(0.18, 0.0, centerDist) * (0.5 + 0.5 * sin(t * 0.8));
    backgroundColor += mix(coreColor, vec3(1.0, 0.9, 0.95), 0.5) * centerGlow * 0.6;

    for (int i = 0; i < 6; i++) {
        float angle = float(i) * TAU / 6.0 + t * 0.15;
        vec2 flowDir = vec2(cos(angle), sin(angle));
        float flowPos = mod(t * 0.4 + float(i) * 0.5, 1.0);
        vec2 particlePos = flowDir * flowPos * 0.95;
        float particleDist = length(screenPos - particlePos);
        float energyGlow = smoothstep(0.025, 0.0, particleDist) * (1.0 - flowPos);
        backgroundColor += midColor * energyGlow * 0.5;
    }

    backgroundColor += vec3(0.06, 0.01, 0.1) * (1.0 - smoothstep(0.0, 1.5, centerDist));

    // Background floating runes - moving across the screen
    // Scale factor for GUI mode (wider aspect ratio needs larger runes)
    float runeScale = (useUvSpace > 0.5) ? 3.0 : 1.0;

    for (int i = 0; i < 25; i++) {
        float seed = float(i) * 1.7319;
        float seedX = random(vec2(seed, 0.0));
        float seedY = random(vec2(0.0, seed));

        // Movement pattern
        float speedX = 0.2 + seedX * 0.3;
        float speedY = 0.1 + seedY * 0.15;
        float dirX = (random(vec2(seed, 1.0)) > 0.5) ? 1.0 : -1.0;
        float dirY = (random(vec2(seed, 2.0)) > 0.5) ? 1.0 : -1.0;

        // Position with wrapping - match visible area
        float rangeX = 16.0;
        float rangeY = 1.5;
        float posX = mod(seedX * rangeX * 2.0 + t * speedX * dirX, rangeX * 2.0) - rangeX;
        float posY = (seedY - 0.5) * rangeY * 2.0 + sin(t * speedY + seed * 10.0) * 0.3;
        vec2 runePos = vec2(posX, posY);

        vec2 toRune = screenPos - runePos;

        // Rotation
        float runeRotation = t * 0.2 + seed * TAU;
        toRune = toRune * rotate2D(runeRotation);

        // Draw rune - scale up for GUI mode
        int runeIndex = int(mod(seed * 24.0, 24.0));
        float runeSize = (0.4 + random(vec2(seed, 3.0)) * 0.2) * runeScale;
        float runeDist = drawRune(toRune / runeSize, runeIndex) * runeSize;

        // Stronger glow
        float runeGlow = smoothstep(0.05 * runeScale, 0.0, runeDist);
        float pulse = 0.6 + 0.4 * sin(t * 0.6 + seed * TAU);
        runeGlow *= pulse;

        vec3 runeColor = mix(outerColor, smallCircleOuter, random(vec2(seed, 4.0)));
        backgroundColor += runeColor * runeGlow * 0.7;
        totalGlow += runeGlow * 0.2;
    }

    float alpha = min(1.0, totalGlow * 0.7 + centerGlow * 0.8 + 0.15);
    alpha = max(alpha, smoothstep(1.5, 0.0, centerDist) * 0.25);

    fragColor = vec4(backgroundColor, alpha * vertexColor.a) * ColorModulator;
}
