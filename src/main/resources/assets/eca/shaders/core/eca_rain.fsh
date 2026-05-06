#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 ScreenSize;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

vec2 hash2(vec2 p) {
    float h = dot(p, vec2(127.1, 311.7));
    return fract(sin(vec2(h, dot(p, vec2(269.5, 183.3)))) * 43758.5453123);
}

float fadeWindow(float e0, float e1, float x) {
    return smoothstep(0.0, e0, x) * (1.0 - smoothstep(e1, 1.0, x));
}

/*
 * Scalar water-height field.
 * Returns 0 (dry) to ~1 (thick water film).
 */
float waterHeight(vec2 st, float aspect, float t) {
    float w = 0.0;
    float overScan = 0.5;               // buffer above/below screen for clean wrap
    float yRange = 1.0 + overScan * 2.0; // total slide range

    // ============================================================
    // SLIDER DROPS — Tier A (large)
    //   Each drop starts ROUND at screen top (with small random
    //   variation), then dynamically stretches + grows a tail as
    //   it descends.  All shape params evolve via mix(init, final, journey).
    // ============================================================
    {
        float cols  = 9.0;
        float colW  = aspect / cols;

        for (int dc = -1; dc <= 1; dc++) {
            float cid = floor(st.x / colW) + float(dc);
            float ch  = hash(vec2(cid, 10.0));
            if (ch > 0.22) continue;

            vec2  r    = hash2(vec2(cid, 11.0));
            vec2  r2   = hash2(vec2(cid, 12.0));
            vec2  r3   = hash2(vec2(cid, 13.0));
            float dropX = (cid + 0.08 + r.x * 0.84) * colW;

            float speed  = 0.018 + r.y * 0.07;
            float rawY   = fract(r.y * 1.83 - t * speed) * yRange - overScan;
            float dropY  = rawY;
            if (dropY < -0.1 || dropY > 1.1) continue;

            float journey = 1.0 - clamp(dropY, 0.0, 1.0); // 0=top, 1=bottom

            // Per-drop personality — each drop ends up different
            float initRound  = 0.88 + r3.y * 0.12;   // birth: nearly round (0.88-1.0)
            float finalLong  = 2.5  + r2.x * 4.0;    // death: elongated  (2.5-6.5)
            float finalTaper = 0.25 + r2.y * 0.4;    // top pointy-ness (0.25-0.65)
            float finalBulge = 0.55 + r3.x * 0.35;   // bottom width   (0.55-0.90)
            float finalWide  = 1.1  + r.x  * 0.7;    // horiz scale    (1.1-1.8)
            float trailMax   = 2.0  + r.y  * 5.0;    // max trail length in radii

            // === EVOLUTION (journey 0→1 drives EVERYTHING) ===
            float stretchY = mix(initRound, finalLong,  journey);
            float stretchX = mix(1.0,       finalWide,  journey);
            float taper    = mix(0.95,      finalTaper, journey);
            float bulge    = mix(0.95,      finalBulge, journey);
            float radius   = 0.015 + r.y * 0.05 + journey * 0.02;

            float dx = st.x - dropX;
            float dy = st.y - dropY;

            float sx = dx * stretchX;
            float sy = dy / stretchY;

            float topT = smoothstep(0.0, radius * 0.5, dy);
            sx /= mix(1.0, taper, topT);

            float botT = smoothstep(-radius * 0.5, 0.0, dy);
            sx *= mix(1.0, bulge, botT);

            float dist = length(vec2(sx, sy));
            if (dist >= radius) continue;

            float nd = dist / radius;
            float profile = sqrt(max(0.0, 1.0 - nd * nd));
            w = max(w, profile * radius * 2.2);

            // --- Trail: invisible at birth (journey=0), grows with descent ---
            float trailOnset = smoothstep(0.04, 0.14, journey);
            float trailH     = radius * trailMax * journey * trailOnset;
            float above      = dy;
            if (above > 0.0 && above < trailH && trailH > 0.001) {
                float tt  = above / trailH;
                float tw  = radius * 0.6 * (1.0 - tt);
                float tdx = abs(dx) / tw;
                if (tdx < 1.0) {
                    w = max(w, (1.0 - tdx) * (1.0 - tt) * 0.35);
                }
            }
        }
    }

    // ============================================================
    // SLIDER DROPS — Tier B (medium, same evolution principle)
    // ============================================================
    {
        float cols  = 14.0;
        float colW  = aspect / cols;

        for (int dc = -1; dc <= 1; dc++) {
            float cid = floor(st.x / colW) + float(dc);
            float ch  = hash(vec2(cid, 20.0));
            if (ch > 0.28) continue;

            vec2  r    = hash2(vec2(cid, 21.0));
            vec2  r2   = hash2(vec2(cid, 22.0));
            vec2  r3   = hash2(vec2(cid, 23.0));
            float dropX = (cid + 0.08 + r.x * 0.84) * colW;

            float speed  = 0.025 + r.y * 0.08;
            float rawY   = fract(r.y * 2.47 - t * speed) * yRange - overScan;
            float dropY  = rawY;
            if (dropY < -0.1 || dropY > 1.1) continue;

            float journey = 1.0 - clamp(dropY, 0.0, 1.0);

            float initRound  = 0.88 + r3.y * 0.12;
            float finalLong  = 2.2  + r2.x * 3.5;
            float finalTaper = 0.25 + r2.y * 0.4;
            float finalBulge = 0.55 + r3.x * 0.35;
            float finalWide  = 1.05 + r.x  * 0.6;
            float trailMax   = 1.5  + r.y  * 4.5;

            float stretchY = mix(initRound, finalLong,  journey);
            float stretchX = mix(1.0,       finalWide,  journey);
            float taper    = mix(0.95,      finalTaper, journey);
            float bulge    = mix(0.95,      finalBulge, journey);
            float radius   = 0.01 + r.y * 0.03 + journey * 0.015;

            float dx = st.x - dropX;
            float dy = st.y - dropY;

            float sx = dx * stretchX;
            float sy = dy / stretchY;

            float topT = smoothstep(0.0, radius * 0.5, dy);
            sx /= mix(1.0, taper, topT);

            float botT = smoothstep(-radius * 0.5, 0.0, dy);
            sx *= mix(1.0, bulge, botT);

            float dist = length(vec2(sx, sy));
            if (dist >= radius) continue;

            float nd = dist / radius;
            float profile = sqrt(max(0.0, 1.0 - nd * nd));
            w = max(w, profile * radius * 1.7);

            float trailOnset = smoothstep(0.04, 0.14, journey);
            float trailH     = radius * trailMax * journey * trailOnset;
            float above      = dy;
            if (above > 0.0 && above < trailH && trailH > 0.001) {
                float tt  = above / trailH;
                float tw  = radius * 0.55 * (1.0 - tt);
                float tdx = abs(dx) / tw;
                if (tdx < 1.0) {
                    w = max(w, (1.0 - tdx) * (1.0 - tt) * 0.3);
                }
            }
        }
    }

    // ============================================================
    // STATIC — small stationary drops with life cycle + absorption
    //   When a slider sweeps over a static drop, the drop flashes
    //   briefly then gets absorbed (disappears).
    // ============================================================
    {
        float grid = 12.0;
        vec2  gv   = st * grid;
        vec2  id   = floor(gv);
        vec2  fv   = fract(gv);

        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec2 cell = id + vec2(x, y);
                float ch  = hash(cell);
                if (ch > 0.38) continue;

                vec2 r = hash2(cell);

                float lifeSeed = hash(cell + vec2(73.9, 17.3));
                float life = fract(lifeSeed + t * 0.035);
                float fade = fadeWindow(0.10, 0.82, life);
                if (fade < 0.01) continue;

                float cx = 0.08 + r.x * 0.84;
                float cy = 0.05 + r.y * 0.9;
                vec2  diff = fv - vec2(x, y) - vec2(cx, cy);

                // Per-drop ellipticity: 0 = round, 1 = flattened
                float ellip  = hash(cell + vec2(37.1, 83.7));
                float sxScl  = mix(1.0, 1.25, ellip);
                float syScl  = mix(1.0, 0.72, ellip);

                float radius = 0.015 + r.y * 0.12;
                float sdist  = length(vec2(diff.x * sxScl, diff.y * syScl));
                if (sdist >= radius) continue;

                // --- Absorption by nearby sliders ---
                float sdWorldX = (id.x + x + cx) / grid;
                float sdWorldY = (id.y + y + cy) / grid;

                float absorbMul = 1.0;   // 1=normal, 0=gone, >1=splash flash

                // Check Tier A sliders
                {
                    float colWA = aspect / 9.0;
                    float acol  = floor(sdWorldX / colWA);
                    for (int dc = -1; dc <= 1; dc++) {
                        float cid = acol + float(dc);
                        float sch = hash(vec2(cid, 10.0));
                        if (sch > 0.22) continue;

                        vec2  sr  = hash2(vec2(cid, 11.0));
                        float sX  = (cid + 0.08 + sr.x * 0.84) * colWA;
                        float sSp = 0.018 + sr.y * 0.07;
                        float sY  = fract(sr.y * 1.83 - t * sSp) * yRange - overScan;

                        float hDist = abs(sdWorldX - sX);
                        float vDist = sY - sdWorldY;
                        if (hDist < colWA * 0.55) {
                            if (vDist > -0.012 && vDist < 0.018) {
                                absorbMul = max(absorbMul, 1.6); // contact flash
                            } else if (vDist >= 0.018 && vDist < 0.2) {
                                absorbMul = 0.0;                  // absorbed
                            }
                        }
                    }
                }

                // Check Tier B sliders
                {
                    float colWB = aspect / 14.0;
                    float acol  = floor(sdWorldX / colWB);
                    for (int dc = -1; dc <= 1; dc++) {
                        float cid = acol + float(dc);
                        float sch = hash(vec2(cid, 20.0));
                        if (sch > 0.28) continue;

                        vec2  sr  = hash2(vec2(cid, 21.0));
                        float sX  = (cid + 0.08 + sr.x * 0.84) * colWB;
                        float sSp = 0.025 + sr.y * 0.08;
                        float sY  = fract(sr.y * 2.47 - t * sSp) * yRange - overScan;

                        float hDist = abs(sdWorldX - sX);
                        float vDist = sY - sdWorldY;
                        if (hDist < colWB * 0.55) {
                            if (vDist > -0.012 && vDist < 0.018) {
                                absorbMul = max(absorbMul, 1.6);
                            } else if (vDist >= 0.018 && vDist < 0.2) {
                                absorbMul = 0.0;
                            }
                        }
                    }
                }

                float splashGlow = smoothstep(1.0, 1.6, absorbMul);
                float finalFade  = fade * clamp(absorbMul, 0.0, 1.0);

                float nd = sdist / radius;
                float profile = sqrt(max(0.0, 1.0 - nd * nd));
                w = max(w, profile * radius * 1.4 * (finalFade + splashGlow * 0.5));
            }
        }
    }

    // ============================================================
    // CONDENSATION — ultra-fine foggy droplets
    // ============================================================
    {
        float grid = 30.0;
        vec2  gv   = st * grid;
        vec2  id   = floor(gv);
        vec2  fv   = fract(gv);

        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec2 cell = id + vec2(x, y);
                float ch  = hash(cell);
                if (ch > 0.06) continue;

                vec2  r    = hash2(cell);
                vec2  diff = fv - vec2(x, y) - r;
                float dist = length(diff);
                float radius = 0.015 + ch * 0.06;
                if (dist >= radius) continue;

                w = max(w, (1.0 - dist / radius) * radius * 0.55);
            }
        }
    }

    return w;
}

void main() {
    float aspect = ScreenSize.x / ScreenSize.y;
    vec2  st     = vec2(texCoord.x * aspect, texCoord.y); // x∈[0,aspect], y∈[0,1]
    float t      = Time;

    // Height field + finite-difference gradient → surface normal
    float h   = waterHeight(st,            aspect, t);
    float hx  = waterHeight(st + vec2(0.002, 0.0), aspect, t);
    float hy  = waterHeight(st + vec2(0.0, 0.002), aspect, t);
    vec2 grad = vec2(hx - h, hy - h);

    // Refraction
    float strength = 0.5;
    vec2  refrUv = vec2(texCoord.x + grad.x * strength / aspect,
                        texCoord.y + grad.y * strength);
    refrUv = clamp(refrUv, vec2(0.001), vec2(0.999));

    vec3 scene = texture(Sampler1, refrUv).rgb;

    float water  = smoothstep(0.0, 0.25, h);
    vec3  result = scene * (1.0 - water * 0.11);
    result += water * vec3(0.76, 0.83, 0.93) * 0.04;

    fragColor = vec4(result, 1.0);
}
