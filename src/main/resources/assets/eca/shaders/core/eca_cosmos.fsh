#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler3;
uniform mat4 InvViewProjMat;
uniform vec3 CameraPos;
uniform vec2 ScreenSize;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

const float PI = 3.14159265359;

float luminance(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// 世界空间方向 → 等距圆柱 UV（天空用，不再用于贴图采样，仅供星场坐标）
vec2 equirectUV(vec3 dir) {
    float u = atan(dir.z, dir.x) * (1.0 / (2.0 * PI)) + 0.5;
    float v = asin(clamp(dir.y, -1.0, 1.0)) * (1.0 / PI) + 0.5;
    return vec2(u, v);
}

// Dave Hoskins hash：无轴向相关，避免 fract(x*y) 那种网格状方块伪影
float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// 4 octave FBM，按振幅总和归一化到 [0,1]（均值约 0.5），使阈值直观可调
float fbm(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    float total = 0.0;
    for (int i = 0; i < 4; i++) {
        v += amp * valueNoise(p);
        total += amp;
        p *= 2.0;
        amp *= 0.5;
    }
    return v / total;
}

/* 紫色星云薄雾：FBM 密度取均值以上段并乘低强度，呈薄雾而非浓云；
   坐标随 Time 缓慢漂移，云团缓慢翻腾。色相由第二层 FBM 在深紫与品红间插值 */
vec3 nebula(vec2 p, float t) {
    vec2 drift = vec2(t * 0.008, t * 0.005);
    float density = smoothstep(0.45, 0.8, fbm(p + drift));
    float hue = fbm(p * 0.7 - drift * 0.5);
    vec3 col = mix(vec3(0.35, 0.1, 0.5), vec3(0.6, 0.2, 0.7), smoothstep(0.3, 0.7, hue));
    return col * density * 0.35;
}

// Dave Hoskins hash13：vec3 → float
float hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float valueNoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);
    float n000 = hash31(i);
    float n100 = hash31(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash31(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash31(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash31(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash31(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash31(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash31(i + vec3(1.0, 1.0, 1.0));
    float nx00 = mix(n000, n100, u.x);
    float nx10 = mix(n010, n110, u.x);
    float nx01 = mix(n001, n101, u.x);
    float nx11 = mix(n011, n111, u.x);
    return mix(mix(nx00, nx10, u.y), mix(nx01, nx11, u.y), u.z);
}

// 4 octave 3D FBM，归一化到 [0,1]
float fbm3(vec3 p) {
    float v = 0.0;
    float amp = 0.5;
    float total = 0.0;
    for (int i = 0; i < 4; i++) {
        v += amp * valueNoise3(p);
        total += amp;
        p *= 2.0;
        amp *= 0.5;
    }
    return v / total;
}

/* 球面星云：直接用 3D 方向向量采样 3D 噪声，球面上连续无接缝，
   消除等距圆柱投影在 ±π 经线处把星云切开错位的问题 */
vec3 nebulaSphere(vec3 dir, float t) {
    vec3 drift = vec3(t * 0.006, 0.0, t * 0.004);
    float density = smoothstep(0.45, 0.8, fbm3(dir * 2.5 + drift));
    float hue = fbm3(dir * 1.8 - drift * 0.5);
    vec3 col = mix(vec3(0.35, 0.1, 0.5), vec3(0.6, 0.2, 0.7), smoothstep(0.3, 0.7, hue));
    return col * density * 0.35;
}

/* 星星颜色按视觉偏好分布（白星为主，模拟夜空中肉眼可见的恒星比例）：
   A 型（白）    ~35%  — 纯白亮星，最普遍
   G 型（黄白）  ~25%  — 太阳型暖黄白
   F 型（黄白亮）~15%  — 偏冷的亮黄白
   K 型（橙）    ~12%  — 橙色
   B 型（蓝白）  ~8%   — 蓝白亮星
   M 型（橙红）  ~5%   — 暖橙红矮星，少量点缀
   各型内部再按 hash 子段微调，避免同型千星一色 */
vec3 stellarColor(float h) {
    if (h < 0.35) {
        // A 型白色，最普遍
        float v = h / 0.35;
        return mix(vec3(0.80, 0.85, 1.0), vec3(0.95, 0.97, 1.0), v);
    } else if (h < 0.60) {
        // G 型黄白（太阳型）
        float v = (h - 0.35) / 0.25;
        return mix(vec3(0.95, 0.88, 0.65), vec3(1.0, 0.97, 0.85), v);
    } else if (h < 0.75) {
        // F 型黄白亮星，偏冷
        float v = (h - 0.60) / 0.15;
        return mix(vec3(0.90, 0.92, 0.95), vec3(1.0, 1.0, 1.0), v);
    } else if (h < 0.87) {
        // K 型橙色
        float v = (h - 0.75) / 0.12;
        return mix(vec3(0.95, 0.70, 0.35), vec3(1.0, 0.85, 0.55), v);
    } else if (h < 0.95) {
        // B 型蓝白亮星
        float v = (h - 0.87) / 0.08;
        return mix(vec3(0.60, 0.70, 1.0), vec3(0.78, 0.85, 1.0), v);
    } else {
        // M 型橙红矮星，少量点缀
        float v = (h - 0.95) / 0.05;
        return mix(vec3(0.85, 0.40, 0.18), vec3(1.0, 0.60, 0.30), v);
    }
}

/* 程序化多彩星星：每格至多一颗，位置/颜色/相位各自哈希；
   sin(Time*1.8+相位) 周期约 3.5 秒，整颗缓慢明灭，避免逐像素抖动。
   颜色按真实恒星光谱分布概率随机选取 */
vec3 twinkleStars(vec2 uv, float t) {
    vec2 cell = floor(uv);
    vec2 local = fract(uv);
    float present = step(0.80, hash21(cell + 7.3));
    vec2 starPos = vec2(hash21(cell + 1.7), hash21(cell + 4.9));
    float star = smoothstep(0.091, 0.0, length(local - starPos)) * present;
    float phase = hash21(cell + 3.1);
    float twinkle = 0.5 + 0.5 * sin(t * 1.8 + phase * 6.2831);
    float bright = 0.7 + 0.3 * hash21(cell + 11.7);
    vec3 col = stellarColor(hash21(cell + 19.3));
    return col * star * twinkle * bright;
}

void main() {
    float depth = texture(Sampler0, texCoord).r;
    vec3 scene  = texture(Sampler1, texCoord).rgb;

    // 实体遮罩：该像素深度比地形快照更近，说明是实体，保留原色不应用滤镜
    float terrainDepth = texture(Sampler3, texCoord).r;
    if (depth < terrainDepth - 0.0001) {
        fragColor = vec4(scene, 1.0);
        return;
    }

    // 从深度反算相机相对世界坐标
    vec3 ndc     = vec3(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0);
    vec4 clip    = InvViewProjMat * vec4(ndc, 1.0);
    vec3 worldRel = clip.xyz / clip.w;
    vec3 worldPos = worldRel + CameraPos;

    if (depth >= 0.9999) {
        // 天空：纯黑底色 + 多彩星星（等距圆柱投影保持天球稳定）+ 球面星云薄雾
        vec3 dir = normalize(worldRel);
        vec2 skyUV = equirectUV(dir);
        vec3 sky = vec3(0.0);
        sky += twinkleStars(skyUV * 60.0, Time);
        sky += nebulaSphere(dir, Time);
        fragColor = vec4(sky, 1.0);
        return;
    }

    // 几何/实体表面：暗色基底 + 星点 + 星云，用原始明暗调制保留立体感
    vec3 surfaceCosmic = vec3(0.0);
    surfaceCosmic += twinkleStars(worldPos.xz * 0.15, Time);
    surfaceCosmic += nebula(worldPos.xz * 0.03, Time);
    float lum = luminance(scene);
    float shade = 0.3 + 0.7 * lum;
    fragColor = vec4(surfaceCosmic * shade, 1.0);
}
